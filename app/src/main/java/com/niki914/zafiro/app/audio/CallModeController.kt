package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 听写引擎 —— 刻意做简单。
 *
 * 旧版本用 Android 的 SpeechRecognizer：它自己判断句子在哪结束，我们自己也判断。
 * 两套判断必然打架，一晚上出了三类 bug（error=5 风暴、说完立刻暂停、半句被重启丢掉）。
 *
 * 现在的分工只有一句话：
 *   能量检测（UtteranceRecorder）负责「哪一段是活」，
 *   Qwen ASR 负责「这一段说了什么」。
 * 没有别的状态机，没有 onEndOfSpeech/onResults 的时序博弈，也没有「重启识别器」这种动作。
 *
 * 代价：每句多约 1 秒、需要联网、每次几分钱。换来的是不会再因为状态错乱丢句子。
 */
class CallModeController(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = UtteranceRecorder(context.filesDir)
    private val asr = QwenAsrClient(File(context.filesDir, "qwen_asr_key.txt"))

    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var transcribing = false
    private var lastText = ""

    // 兼容旧接口：这些在简化后不再需要，但保持不变以免调用点大改
    var bargeIn = false
    var onBargeIn: (() -> Boolean)? = null

    fun start() {
        if (active) return
        active = true
        paused = false
        lastText = ""
        recorder.onVoiceActivity = { speaking ->
            if (!active) return@onVoiceActivity
            if (speaking) {
                if (paused) return@onVoiceActivity
                recorder.markSpeechStart()
                log("speech start")
            } else {
                // 播报期间录到的都在喇叭里，不该被当成用户说话
                if (paused) return@onVoiceActivity
                recorder.markSpeechEnd()
                log("speech end")
                finishUtterance()
            }
        }
        recorder.start()
        log("start")
    }

    fun stop() {
        active = false
        recorder.onVoiceActivity = null
        recorder.stop()
        log("stop")
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        log("pause")
    }

    fun resume() {
        if (!active) return
        paused = false
        log("resume")
    }

    /** 打断之后立刻继续听（简化版没有 barge-in，这里保留接口）。 */
    fun listenNow() = resume()

    fun setSpeaking(speaking: Boolean) = Unit

    fun noteSpoken(text: String) = Unit

    fun release() = stop()

    /**
     * 一句话结束：存盘 + 送 ASR。
     * 正在转写时不重入 —— 多说的部分自然并入下一段，不会互相踩。
     */
    private fun finishUtterance() {
        if (transcribing) {
            log("busy, segment already in flight")
            return
        }
        val file = recorder.saveUtterance("") ?: return
        transcribing = true
        scope.launch {
            try {
                val text = asr.transcribe(file).getOrNull()?.trim().orEmpty()
                runCatching { file.delete() }
                log("asr=" + text.length + " chars: " + text.take(60))
                if (!active || paused || text.length < 2) return@launch
                if (text == lastText) return@launch
                lastText = text
                main.post { onUtterance(text) }
            } finally {
                transcribing = false
            }
        }
    }

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }
}
