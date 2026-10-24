package com.niki914.zafiro.app.audio

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 听写引擎：本地 VAD 切句 + Qwen ASR 识别，完全不经过系统 SpeechRecognizer。
 *
 * 为什么换掉系统识别器：它要走 ParcelFileDescriptor 管道喂音频、每句重开一次会话，
 * 实测会连着 error=7 / error=11，然后整条链路静默死掉（日志里只有 speech begins 没有
 * 文本）。Qwen 这条路没有会「死」的会话：切一句、发一次、拿回文本，失败就记一行日志等下一句。
 *
 * 省钱的四个本地闸门（全部不发请求）：
 *   1. 能量 VAD —— UtteranceRecorder 的噪声底自适应，静音根本不会触发；
 *   2. 时长 < 400ms 直接丢；
 *   3. 峰值没超过噪声底 3 倍丢（用 VAD 已经判过，这里只用时长和字数）；
 *   4. 字数 < 1 丢。
 * 每一次丢弃都写日志，方便事后判断有没有把真话丢掉。
 *
 * 开关：voice_tuning.txt 里 asr_engine=qwen|google（默认 qwen）。
 */
class QwenListenEngine(
    private val context: Context,
    private val onSentence: (String) -> Unit,
) {

    private val recorder = UtteranceRecorder(context.filesDir)
    private val asr = QwenAsrClient(File(context.filesDir, "qwen_asr_key.txt"))
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "qwen-asr").apply { isDaemon = true } }

    @Volatile private var active = false
    @Volatile private var speechStartedAt = 0L
    @Volatile private var holdSeq = 0
    private val pending = StringBuilder()

    fun start() {
        if (active) return
        if (!asr.hasKey()) {
            log("engine=qwen blocked: no qwen_asr_key.txt")
            return
        }
        active = true
        recorder.onVoiceActivity = { speaking ->
            runCatching { if (speaking) onSpeechStart() else onSpeechEnd() }
        }
        recorder.start()
        startInputWatcher()
        log("engine=qwen started (hold=" + holdMs() + "ms min=" + minMs() + "ms)")
    }

    fun stop() {
        active = false
        inputWatch = null
        holdSeq++
        recorder.onVoiceActivity = null
        runCatching { recorder.stop() }
        log("engine=qwen stopped")
    }

    /**
     * 输入设备跟随：有 SCO/蓝牙耳机的输入设备就用它，否则退回手机麦克风。
     *
     * 之前这块是空的 —— AudioRecord 一律用系统默认，于是戴着耳机也一直在收手机麦克风，
     * 录进来的自然是离嘴最远的那个声音。每 2 秒看一次，变了就切换并记一行日志。
     */
    private fun startInputWatcher() {
        if (inputWatch != null) return
        inputWatch = Thread {
            var last = "?"
            while (active) {
                val name = runCatching {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val dev = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == 26 /* BLE_HEADSET */
                    }
                    recorder.preferredInput = dev
                    dev?.productName?.toString() ?: "phone mic"
                }.getOrDefault("?")
                if (name != last) {
                    log("input: " + name)
                    last = name
                }
                runCatching { Thread.sleep(2000) }
            }
        }.apply { isDaemon = true; name = "qwen-input" }
        inputWatch?.start()
    }

    @Volatile private var inputWatch: Thread? = null

    private fun onSpeechStart() {
        speechStartedAt = System.currentTimeMillis()
        recorder.markSpeechStart()
    }

    private fun onSpeechEnd() {
        recorder.markSpeechEnd()
        val startedAt = speechStartedAt
        speechStartedAt = 0L
        val dur = if (startedAt > 0) System.currentTimeMillis() - startedAt else -1L
        val file = runCatching { recorder.saveUtterance("") }.getOrNull()
        if (file == null) {
            log("skip: no audio (dur=" + dur + "ms)")
            return
        }
        if (dur in 0 until minMs()) {
            log("skip: too short dur=" + dur + "ms")
            file.delete()
            return
        }
        // 本地检查，音频不出手机：太短、太静、几乎没帧超过阈值的一律不发。
        // 实测（24 次请求里 5 次是空）：噪声片段 peak≈800 rms 13–69，只有 1/10 帧像语音；
        // 真人语音 peak>25000 rms 2000+，18/36 帧以上。阈值落在两者中间的空档里。
        val stats = runCatching { analyse(file) }.getOrNull()
        if (stats == null) { log("skip: unreadable clip"); file.delete(); return }
        val (fileMs, rms, speechFrames, totalFrames) = stats
        if (rms < MIN_RMS) {
            log("skip: too quiet rms=" + rms + " dur=" + fileMs + "ms")
            file.delete()
            return
        }
        if (speechFrames < MIN_SPEECH_FRAMES) {
            log("skip: mostly silence frames=" + speechFrames + "/" + totalFrames)
            file.delete()
            return
        }
        if (!active) { file.delete(); return }
        log("send: dur=" + fileMs + "ms rms=" + rms + " frames=" + speechFrames + "/" + totalFrames)
        worker.execute { transcribe(file, dur) }
    }

    private fun transcribe(file: File, dur: Long) {
        val t0 = System.currentTimeMillis()
        val result = runCatching { runBlocking { asr.transcribe(file) } }.getOrNull()
        val text = result?.getOrNull()?.trim().orEmpty()
        val ms = System.currentTimeMillis() - t0
        if (text.length < 1) {
            log("asr: empty after " + ms + "ms dur=" + dur + "ms " + (result?.exceptionOrNull()?.message ?: ""))
            return
        }
        log("asr: " + ms + "ms dur=" + dur + "ms text=" + text.take(60))
        if (!active) return
        hold(text)
    }

    /** 句末停顿延长期：这段时间内又来一句就并成一句（和 ListenEngine 的行为保持一致）。 */
    private fun hold(text: String) {
        if (text.isNotEmpty()) pending.append(if (pending.isEmpty()) text else " " + text)
        scheduleRelease(0)
    }

    private fun scheduleRelease(attempt: Int) {
        val seq = ++holdSeq
        val wait = holdMs()
        log("hold: wait=" + wait + "ms pending=" + pending.length)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!active || seq != holdSeq) return@postDelayed
            val merged = pending.toString().trim()
            // 说到一半的句子不要交付：再等一轮，让下半句进来并进去。
            // 上限 MAX_EXTENDS 次，避免一句「嗯」把整轮对话拖住。
            if (attempt < MAX_EXTENDS && looksUnfinished(merged)) {
                log("hold: extend attempt=" + (attempt + 1) + " unfinished=" + merged.take(50))
                scheduleRelease(attempt + 1)
                return@postDelayed
            }
            pending.setLength(0)
            if (merged.isNotEmpty()) {
                log("hold: release " + merged.take(60))
                onSentence(merged)
            }
        }, wait)
    }

    /**
     * 是不是说到一半。判断只看结尾：连词、冠词、介词、逗号、省略号 —— 这些都是
     * 「还有下文」的信号。宁可多等一秒，也不要把半句话交给 agent。
     */
    private fun looksUnfinished(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        val last = t.last()
        if (last == ',' || last == ':' || last == '-' || last == '、' || last == '，' || last == '：') return true
        if (t.endsWith("...") || t.endsWith("…")) return true
        val tail = t.trimEnd('.', '!', '?', '。', '！', '？').split(' ', '，', ',', '。').lastOrNull()?.trim().orEmpty()
        if (tail.isEmpty()) return false
        return tail in UNFINISHED_TAIL
    }

    /** 读 16k/16bit 单声道 wav，返回 (时长ms, RMS, 超过阈值的帧数, 总帧数)。帧按 100ms 切。 */
    private fun analyse(f: File): List<Int> {
        val bytes = f.readBytes()
        if (bytes.size <= 44) return listOf(0, 0, 0, 0)
        val n = (bytes.size - 44) / 2
        var sumSq = 0.0
        var speech = 0
        var frames = 0
        var i = 44
        var framePeak = 0
        var inFrame = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val v = (hi shl 8) or lo
            val s = if (v >= 32768) v - 65536 else v
            sumSq += (s.toDouble() * s)
            val a = if (s < 0) -s else s
            if (a > framePeak) framePeak = a
            inFrame++
            if (inFrame >= 1600) {
                frames++
                if (framePeak > ENERGY_ON) speech++
                framePeak = 0
                inFrame = 0
            }
            i += 2
        }
        val rms = if (n > 0) kotlin.math.sqrt(sumSq / n).toInt() else 0
        val ms = (n / 16.0).toInt()
        return listOf(ms, rms, speech, frames)
    }

    private fun tuning(key: String): String? = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching null
        f.readLines().firstOrNull { it.trim().startsWith("$key=") }?.substringAfter("=")?.trim()
    }.getOrNull()

    private fun holdMs(): Long = tuning("endpoint_hold_ms")?.toLongOrNull() ?: 900L

    private companion object {
        /** 句尾出现这些词，基本可以断定话没说完。 */
        val UNFINISHED_TAIL = setOf(
            "and", "but", "or", "so", "because", "if", "when", "which", "that", "than",
            "the", "a", "an", "of", "to", "with", "for", "from", "in", "on", "at", "by",
            "is", "are", "was", "were", "will", "would", "can", "could", "should",
            "my", "your", "our", "their", "his", "her", "its",
            "um", "uh", "er", "hmm", "like"
        )
        /** 最多再等几轮。3 轮 ≈ 多等 2.7 秒，够说完一句话。 */
        const val MAX_EXTENDS = 3
        /** 低于这个 RMS 一律不发：噪声实测 13–69，语音 2000+。 */
        const val MIN_RMS = 300
        /** 至少这么多帧（每帧 100ms）要超过能量阈值，否则就是纯静音。 */
        const val MIN_SPEECH_FRAMES = 3
        /** 与 UtteranceRecorder 的 ENERGY_ON 对齐。 */
        const val ENERGY_ON = 600
    }
    /** 最短可发送时长。实测：最短的真人句子 1.1s，噪声碎片 0.6–1.4s，所以落在 900ms。 */
    private fun minMs(): Long = tuning("gate_min_ms")?.toLongOrNull() ?: 900L

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  [qwen] " + msg + "\n")
        }
    }
}
