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
        log("engine=qwen started (hold=" + holdMs() + "ms min=" + minMs() + "ms)")
    }

    fun stop() {
        active = false
        holdSeq++
        recorder.onVoiceActivity = null
        runCatching { recorder.stop() }
        log("engine=qwen stopped")
    }

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
        if (!active) { file.delete(); return }
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
        pending.append(if (pending.isEmpty()) text else " " + text)
        val seq = ++holdSeq
        val wait = holdMs()
        log("hold: wait=" + wait + "ms pending=" + pending.length)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!active || seq != holdSeq) return@postDelayed
            val merged = pending.toString().trim()
            pending.setLength(0)
            if (merged.isNotEmpty()) {
                log("hold: release " + merged.take(60))
                onSentence(merged)
            }
        }, wait)
    }

    private fun tuning(key: String): String? = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching null
        f.readLines().firstOrNull { it.trim().startsWith("$key=") }?.substringAfter("=")?.trim()
    }.getOrNull()

    private fun holdMs(): Long = tuning("endpoint_hold_ms")?.toLongOrNull() ?: 900L
    private fun minMs(): Long = tuning("gate_min_ms")?.toLongOrNull() ?: 400L

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  [qwen] " + msg + "\n")
        }
    }
}
