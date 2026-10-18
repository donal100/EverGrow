package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 录音，直接写 16 kHz 单声道 16-bit WAV。
 *
 * 为什么不用 MediaRecorder 的 AAC/m4a：实测同一条 ASR 接口，WAV 到 30s（约 940 KB）都能识别，
 * 而 MediaRecorder 产出的 m4a 一旦超过约 130 KB（≈11s）就稳定返回
 * "400 The audio format is illegal and cannot be opened"（同一个文件重试三次全 400，
 * 10.4s 的那个重试三次全 200）。文件本身结构完整（ftyp/free/mdat/moov 齐全），
 * 是对方解码器对这类 m4a 不认，不是我们写坏了。
 *
 * 于是改用 AudioRecord 自己采 PCM 并补 WAV 头：不压缩，体积略大（32 KB/s），
 * 但格式确定、体积上限只受时长约束。16 kHz 正好是 ASR 的原生采样率，不必重采样。
 */
class VoiceRecorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var currentFile: File? = null
    private var startedAtMs = 0L

    @Volatile
    private var capturing = false

    val isRecording: Boolean get() = record != null

    /** 开始录音，返回目标文件。失败时抛异常，由调用方提示。 */
    fun start(): File {
        release()
        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val file = File(dir, "rec_" + System.currentTimeMillis() + ".wav")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) error("AudioRecord unavailable")
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            error("AudioRecord init failed")
        }

        val out = FileOutputStream(file)
        out.write(ByteArray(WAV_HEADER_SIZE))   // 先占位，stop() 时回填真实长度

        ar.startRecording()
        record = ar
        currentFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        capturing = true

        worker = Thread {
            val buf = ByteArray(bufSize)
            try {
                while (capturing) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            } catch (_: Throwable) {
                // 读取中断时保留已写入部分，交给 stop() 判断
            } finally {
                try { out.flush(); out.close() } catch (_: Throwable) { }
            }
        }.also { it.start() }

        return file
    }

    /** 停止并返回 (文件, 毫秒)；太短或写入失败返回 null。 */
    fun stop(): Pair<File, Long>? {
        val ar = record ?: return null
        val file = currentFile
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        capturing = false
        record = null
        currentFile = null
        try { ar.stop() } catch (_: Throwable) { } finally { ar.release() }
        worker?.join(1500)
        worker = null

        if (file == null) return null
        if (elapsed < MIN_DURATION_MS) {
            file.delete()
            return null
        }
        val ok = finalizeWav(file)
        val good = ok && file.exists() && file.length() > WAV_HEADER_SIZE
        return if (good) file to elapsed else { file.delete(); null }
    }

    /** 放弃当前录音并清理。 */
    fun cancel() {
        val ar = record ?: return
        val file = currentFile
        capturing = false
        record = null
        currentFile = null
        try { ar.stop() } catch (_: Throwable) { } finally { ar.release() }
        worker?.join(1000)
        worker = null
        file?.delete()
    }

    fun release() = cancel()

    /** 回填 WAV 头：数据长度只有录完才知道。 */
    private fun finalizeWav(file: File): Boolean {
        val dataSize = (file.length() - WAV_HEADER_SIZE).toInt()
        if (dataSize <= 0) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write("RIFF".toByteArray())
                raf.write(intLE(36 + dataSize))
                raf.write("WAVE".toByteArray())
                raf.write("fmt ".toByteArray())
                raf.write(intLE(16))
                raf.write(shortLE(1))                       // PCM
                raf.write(shortLE(CHANNELS))
                raf.write(intLE(SAMPLE_RATE))
                raf.write(intLE(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE))
                raf.write(shortLE(CHANNELS * BYTES_PER_SAMPLE))
                raf.write(shortLE(16))
                raf.write("data".toByteArray())
                raf.write(intLE(dataSize))
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    companion object {
        private const val RECORDINGS_DIR = "recordings"
        private const val MIN_DURATION_MS = 300L
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_SIZE = 44
    }
}
