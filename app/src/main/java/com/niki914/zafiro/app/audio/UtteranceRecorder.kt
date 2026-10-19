package com.niki914.zafiro.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.Executors

/**
 * 通话模式的「耳朵备份」：系统识别器在听的同时，把同一路麦克风音频留在环形缓冲里。
 *
 * 动机：SpeechRecognizer 只给文本，不给音频。可真听错的时候，只有原始音频能救回来
 * （送去 Qwen ASR 重听一遍）。所以要留一份，但**不能一直往云端发** —— 平时零成本，
 * 只在用户说「你听错了」时才转写。
 *
 * 起点/终点直接用识别器的 onBeginningOfSpeech / onEndOfSpeech 打标记：
 * 它已经做了端点检测，没必要再写一套 VAD。
 *
 * 存储：filesDir/utt/ 下 wav + 同名 txt（识别器当时给的文本），只留最近 [KEEP_FILES] 条，
 * 并且超过 [MAX_AGE_MS] 的一律删掉 —— 跑步一小时也不会把手机塞满。
 */
class UtteranceRecorder(private val filesDir: File) {

    private val sampleRate = 16_000
    private val ringSeconds = 60
    private val ring = ShortArray(sampleRate * ringSeconds)
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "utt-writer").apply { isDaemon = true } }

    private var writeIndex = 0
    private var totalWritten = 0L
    private var speechStart = -1L
    private var speechEnd = -1L


    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private fun dir(): File = File(filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2),
            )
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        record = rec
        running = true
        runCatching { rec.startRecording() }
        thread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                synchronized(lock) {
                    for (i in 0 until n) {
                        ring[writeIndex] = buf[i]
                        writeIndex = (writeIndex + 1) % ring.size
                    }
                    totalWritten += n
                }
            }
        }.also { it.isDaemon = true; it.start() }
        purge()
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
    }

    fun markSpeechStart() {
        speechStart = totalWritten
    }


    fun markSpeechEnd() {
        speechEnd = totalWritten
    }

    /**
     * 把上一句话写成 wav。没有可用的起止标记就放弃（比如那次只识别到噪声）。
     */
    /**
     * 把上一句话写成 wav，并返回文件；识别被削掉开头时，起点改用我们自己的能量起点，
     * 这样重听整段时前面几个词还在。
     */
    fun saveUtterance(recognisedText: String): File? {
        val googleStart = speechStart
        val end = speechEnd
        speechStart = -1L
        speechEnd = -1L
        if (googleStart < 0 || end <= googleStart) return null
        val start = googleStart
        val samples = end - start
        if (samples < sampleRate / 4 || samples > sampleRate * ringSeconds) return null

        val pcm = ShortArray(samples.toInt())
        synchronized(lock) {
            var readIdx = ((start % ring.size) + ring.size).toInt() % ring.size
            for (i in pcm.indices) {
                pcm[i] = ring[readIdx]
                readIdx = (readIdx + 1) % ring.size
            }
        }
        val stamp = System.currentTimeMillis()
        val f = File(dir(), "utt_" + stamp + ".wav")
        return runCatching {
            f.writeBytes(wavBytes(pcm))
            File(dir(), "utt_" + stamp + ".txt").writeText(recognisedText)
            io.execute { runCatching { purge() } }
            f
        }.getOrNull()
    }

    /** 只留最近几条 + 删掉过老的，避免长期占用空间。 */
    fun purge() {
        val now = System.currentTimeMillis()
        val clips = dir().listFiles { f -> f.name.startsWith("utt_") && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.name } ?: return
        clips.forEachIndexed { index, f ->
            val stamp = f.name.removePrefix("utt_").removeSuffix(".wav").toLongOrNull() ?: 0L
            val tooOld = stamp > 0 && now - stamp > MAX_AGE_MS
            if (index >= KEEP_FILES || tooOld) {
                f.delete()
                File(dir(), f.name.removeSuffix(".wav") + ".txt").delete()
            }
        }
    }

    private fun wavBytes(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        fun writeInt(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
            out[offset + 2] = ((value shr 16) and 0xff).toByte()
            out[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte()
            out[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        writeInt(4, 36 + dataSize)
        "WAVEfmt ".toByteArray().copyInto(out, 8)
        writeInt(16, 16)
        writeShort(20, 1)
        writeShort(22, 1)
        writeInt(24, sampleRate)
        writeInt(28, sampleRate * 2)
        writeShort(32, 2)
        writeShort(34, 16)
        "data".toByteArray().copyInto(out, 36)
        writeInt(40, dataSize)
        var i = 44
        for (s in pcm) {
            out[i] = (s.toInt() and 0xff).toByte()
            out[i + 1] = ((s.toInt() shr 8) and 0xff).toByte()
            i += 2
        }
        return out
    }

    fun release() {
        stop()
        io.shutdown()
    }

    private companion object {
        const val DIR = "utt"
        const val KEEP_FILES = 8
        const val MAX_AGE_MS = 20 * 60 * 1000L
    }
}
