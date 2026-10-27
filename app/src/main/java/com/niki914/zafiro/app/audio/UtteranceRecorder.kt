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

    /**
     * VAD：我们自己判断「有人开始说话 / 说完了」。识别器只在有语音时打开，
     * 不再靠定时轮询 —— 这正是首词被吃掉的根源。
     * 回调在录音线程上执行，调用方自己切主线程。
     */
    @Volatile
    var onVoiceActivity: ((Boolean) -> Unit)? = null

    /** 当前这一帧的电平（PCM16 峰值）。电平触发比边沿触发可靠：
     *  边沿触发一旦没回到「静音」状态，就再也不会有新的「开口」事件 —— 麦克风永远不开。 */
    @Volatile
    var currentLevel = 0
        private set

    /**
     * 自适应噪声底：房间里的音乐/风扇/街道声会把这个值顶起来。
     * 判定「有人说话」要求明显高于这个底（3 倍），所以音乐再响也不会被当成人声 ——
     * 固定阈值做不到这一点：要么被音乐触发，要么在安静处又听不见。
     */
    @Volatile
    private var noiseFloor = 800.0

    private var speakingDetected = false
    private var aboveCount = 0
    private var belowCount = 0


    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private fun dir(): File = File(filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 录音器自己的日志：静默失败以前完全查不到原因，这里补上。 */
    private fun log(msg: String) {
        runCatching {
            val f = File(filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  [rec] " + msg + "\n")
        }
    }

    fun start() {
        if (running) {
            log("start: ignored, already running")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            log("start: minBuf=" + minBuf)
            return
        }
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2),
            )
        }.getOrNull() ?: run {
            log("start: AudioRecord create failed")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            log("start: state=" + rec.state)
            return
        }
        record = rec
        runCatching { preferredInput?.let { rec.setPreferredDevice(it) } }
        running = true
        runCatching { rec.startRecording() }
        log("start ok: state=" + rec.state + " thr=" + dynamicThreshold().toInt())
        thread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val v = kotlin.math.abs(buf[i].toInt())
                    if (v > peak) peak = v
                }
                currentLevel = peak
                // 噪声底：上升快、下降慢。
                // 之前只在「判定没人说话」时更新，一旦误触发成 speaking 就再也学不到底噪；
                // 而这台手机静音时的 peak 也有 700–1100，永远掉不到 ENERGY_OFF(450) 以下，
                // 状态就卡在「还在说话」，一句话都交不出去（20:38 的日志就是这样）。
                if (!speakingDetected || peak < endThreshold()) {
                    noiseFloor = if (peak > noiseFloor) {
                        noiseFloor * 0.7 + peak * 0.3
                    } else {
                        noiseFloor * 0.98 + peak * 0.02
                    }
                }
                if (peak > dynamicThreshold()) {
                    aboveCount++
                    belowCount = 0
                } else if (peak < endThreshold()) {
                    belowCount++
                    aboveCount = 0
                }
                if (!speakingDetected && aboveCount >= ONSET_FRAMES) {
                    speakingDetected = true
                    // 记下真正的开口位置。识别器的 onBeginningOfSpeech 通常晚 0.5-1.5 秒，
                    // 用它存盘会把整句开头切掉（实测丢了 "Okay, now translate"）。
                    vadStart = totalWritten
                    speakFrames = 0
                    log("vad on peak=" + peak + " thr=" + dynamicThreshold().toInt() + " cb=" + (onVoiceActivity != null))
                    runCatching { onVoiceActivity?.invoke(true) }
                } else if (speakingDetected) {
                    speakFrames++
                    val silent = belowCount
                    val forced = speakFrames >= MAX_SPEAK_FRAMES
                    if (silent >= endFrames() || forced) {
                        speakingDetected = false
                        belowCount = 0
                        log(
                            if (forced) "vad force end frames=" + speakFrames
                            else "vad off silent=" + silent + " thr=" + endThreshold().toInt(),
                        )
                        runCatching { onVoiceActivity?.invoke(false) }
                    }
                }
                synchronized(lock) {
                    for (i in 0 until n) {
                        ring[writeIndex] = buf[i]
                        writeIndex = (writeIndex + 1) % ring.size
                    }
                    totalWritten += n
                }
                // 转写引擎的入口：同一个 AudioRecord 的样本分一份给它，
                // 不再开第二个录音器（两个 AudioRecord 会互相抢麦克风）。
                runCatching { onSamples?.invoke(buf, n) }
                // 滚动存档（默认最近 5 分钟，10 秒一个文件），只写盘，不参与识别。
                // 好处：识别器晚开/听错/没反应时，事后还能从磁盘上重听那几秒。
                archiveCopy(buf, n)
            }
        }.also { it.isDaemon = true; it.start() }
        purge()
    }

    fun stop() {
        log("stop: running=" + running)
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
    }

    /** 相对噪声底的判定阈值：至少 600，且不低于底噪的 2 倍。
     *  之前是 900 / 3 倍，安静房间里开始说话的第一个字常常达不到，被整段跳过。 */
    private fun dynamicThreshold(): Double = maxOf(ENERGY_ON.toDouble(), noiseFloor * FLOOR_MULT)

    /** 收尾阈值：比抬起阈值低一档，但必须高于实测底噪，否则静音也判成有声。 */
    private fun endThreshold(): Double = maxOf(ENERGY_OFF.toDouble(), noiseFloor * 1.4)

    /** 静音多久才算「说完了」。默认 1000ms（以前写死 6 帧 = 600ms）：
     *  实测 600ms 会在句中换气的地方就把句子切断，后半句变成一段没有文字的碎片。
     *  voice_tuning.txt 里 vad_end_ms=N 可以调，1 帧约 100ms。 */
    private fun endFrames(): Int {
        val ms = runCatching {
            val f = File(filesDir, "voice_tuning.txt")
            if (!f.isFile) return@runCatching 1000
            f.readLines().firstOrNull { it.trim().startsWith("vad_end_ms=") }
                ?.substringAfter("=")?.trim()?.toIntOrNull() ?: 1000
        }.getOrDefault(1000)
        return (ms / 100).coerceIn(1, 60)
    }

    /** 现在是否有人声（电平触发用）。 */
    fun isLoud(): Boolean = running && currentLevel > dynamicThreshold()

    /** 打断用的「强信号」判断：2.5 倍阈值。普通环境噪声达不到，真说话可以。 */
    fun isSpike(): Boolean = running && currentLevel > dynamicThreshold() * 2.5

    /** 当前噪声底，诊断用。 */
    fun floor(): Int = noiseFloor.toInt()

    /** 存档用的 10 秒段缓冲。 */
    private val seg = ShortArray(sampleRate * SEGMENT_SECONDS)
    private var segFill = 0

    /**
     * 把刚读到的样本追加进当前段，满了就丢给 io 线程写 wav。
     * 写完顺手清掉超过 ARCHIVE_MINUTES 的旧段 —— 磁盘占用恒定在 5 分钟 ≈ 10MB。
     */
    private fun archiveCopy(buf: ShortArray, n: Int) {
        var copied = 0
        while (copied < n) {
            val take = minOf(n - copied, seg.size - segFill)
            System.arraycopy(buf, copied, seg, segFill, take)
            segFill += take
            copied += take
            if (segFill >= seg.size) {
                val snapshot = seg.copyOf()
                val startMs = System.currentTimeMillis() - SEGMENT_SECONDS * 1000L
                segFill = 0
                io.execute { runCatching { writeSegment(snapshot, startMs); purgeArchive() } }
            }
        }
    }

    private fun writeSegment(pcm: ShortArray, startMs: Long) {
        val d = File(filesDir, ARCHIVE_DIR)
        if (!d.exists()) d.mkdirs()
        File(d, "seg_" + startMs + ".wav").writeBytes(wavBytes(pcm))
    }

    private fun purgeArchive() {
        val cutoff = System.currentTimeMillis() - ARCHIVE_MINUTES * 60_000L
        File(filesDir, ARCHIVE_DIR).listFiles()?.forEach { f ->
            val stamp = f.name.removePrefix("seg_").removeSuffix(".wav").toLongOrNull() ?: return@forEach
            if (stamp < cutoff) runCatching { f.delete() }
        }
    }

    /** 指定的输入设备（例如蓝牙耳机麦克风）；为 null 时用系统默认。 */
    @Volatile
    var preferredInput: android.media.AudioDeviceInfo? = null

    /** 原始样本回调：给实时转写引擎喂数据用。 */
    @Volatile
    var onSamples: ((ShortArray, Int) -> Unit)? = null

    /** VAD 判定的开口位置（帧计数），比识别器早，用于存盘时保住句首。 */
    private var vadStart = -1L
    private var speakFrames = 0

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
        val vad = vadStart
        val end = speechEnd
        speechStart = -1L
        speechEnd = -1L
        vadStart = -1L
        if (googleStart < 0 || end <= googleStart) return null
        // 起点用能量起点（VAD）和识别器起点里更早的那个：识别器晚开时，
        // 存档里仍然保留下整段话，事后可以用 Qwen 重听补回来。
        val start = if (vad in 0 until googleStart) vad else googleStart
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
        /** VAD 阈值（PCM16）。正常说话 2000-8000，静音 <300。 */
        const val ENERGY_ON = 600
        /** 噪声底倍数：2 倍即可分开人声与空调/风扇噪声 */
        const val FLOOR_MULT = 2.0
        const val ENERGY_OFF = 450
        /** 每帧约 100ms：1 帧起说话即触发，6 帧（≈600ms）静音算说完。 */
        const val ONSET_FRAMES = 1
        /** 连续「说话」超过 200 帧（约 20 秒）强制收尾，防状态卡死。 */
        const val MAX_SPEAK_FRAMES = 200
        const val END_FRAMES = 6
        const val MAX_AGE_MS = 20 * 60 * 1000L
        /** 滚动存档：每段 10 秒，只保留最近 5 分钟（≈10MB） */
        const val SEGMENT_SECONDS = 10
        const val ARCHIVE_MINUTES = 5L
        const val ARCHIVE_DIR = "voice_ring"
    }
}
