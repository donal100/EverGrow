package com.niki914.zafiro.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File

/**
 * 通话模式（Listen mode）：像电话一样一直听着。
 *
 * 职责只到「听懂一句话」为止 —— 转写出来的文本交给调用方（发消息），
 * 播放回复仍是 [VoiceReplySpeaker] 的事。这样两条链路各自可替换，
 * 也不会出现「听」和「说」互相抢麦克风。
 *
 * 用系统 [SpeechRecognizer] 而不是自己开 AudioRecord + Qwen ASR：
 * 它自带端点检测（说完自动结束），这正是通话模式要的东西；自己写 VAD
 * 还得处理回声和设备差异。设备上的识别服务是 Google 的，可用。
 *
 * 回声处理：念回复时 pause()，念完 resume()。戴耳机时本来就没这问题，
 * 但外放时不停麦会把 agent 自己的声音当成用户输入。
 */
class CallModeController(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** 用户按开关打开时为 true；关掉后所有回调都变成空操作。 */
    private var active = false

    /** 自己念话（或等回复）时为 true：不收音，避免把自己的声音吃回去。 */
    private var paused = false
    private var lastUtterance = ""
    private var lastSpokenPrefix = ""

    /**
     * 是否已经有一次识别在跑。SpeechRecognizer 不允许重叠调用：
     * cancel() 还没落地就再 startListening() 会连发 ERROR_CLIENT(5)，
     * 日志里那一串 error=5 就是这么来的 —— 一条都没真正开始听。
     */
    private var listening = false

    /** 连续失败次数，用来退避；成功出结果就清零。 */
    private var errorStreak = 0

    /**
     * 重新开麦后的静默窗口。TTS 尾音还在空气里/还在耳机漏音时，
     * 识别器会先收到一段残缺的「自己」，这种结果不该当成用户说话。
     */
    private var ignoreUntilMs = 0L

    /** 交出去之后等这么久还没人 resume() 就自己接上，避免卡死在「已暂停」。 */
    private val watchdog = Runnable { resume() }

    private val restart = Runnable { startListening() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = log("ready")
        override fun onBeginningOfSpeech() = log("speech start")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            log("speech end")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // 只看最终结果：中间态会让 agent 收到半句话
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            listening = false
            log("error=" + error)
            // 6/7（no match / speech timeout）是正常的「静默」结果；5 是客户端状态没准备好。
            // 都退避重开，连续失败就拉长间隔，避免把识别器打成 error=5 风暴。
            errorStreak = (errorStreak + 1).coerceAtMost(5)
            schedule((300L * errorStreak * errorStreak).coerceAtMost(3000L))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            errorStreak = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            log("results=" + text.length + " chars: " + text.take(60))
            if (!active) return
            if (System.currentTimeMillis() < ignoreUntilMs) {
                log("dropped transcript inside the post-speech grace window")
                schedule(POST_ERROR_RESTART_MS)
                return
            }
            if (text.length < 2) {
                schedule(POST_ERROR_RESTART_MS)
                return
            }
            // 回声：麦克风会把自己刚念的内容听回来。只比开头 8 个字不够 ——
            // 实测把「That's the first time the loop has behaved…」整句收了进来。
            // 改成词集合重合度：超过一半的词一样，就当回声丢掉。
            if (lastSpokenPrefix.isNotEmpty() && looksLikeEcho(text, lastSpokenPrefix)) {
                log("dropped echo-like transcript: " + text.take(60))
                schedule(POST_ERROR_RESTART_MS)
                return
            }
            if (text == lastUtterance) {
                schedule(POST_ERROR_RESTART_MS)
                return
            }
            lastUtterance = text
            pause()
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MS)
            onUtterance(text)
        }
    }

    fun start() {
        if (active) return
        active = true
        paused = false
        lastUtterance = ""
        main.post {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                }
            }
            log("start")
            startListening()
        }
    }

    fun stop() {
        active = false
        paused = false
        listening = false
        main.removeCallbacks(restart)
        main.removeCallbacks(watchdog)
        main.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            log("stop")
        }
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        listening = false
        main.removeCallbacks(restart)
        main.post { runCatching { recognizer?.cancel() } }
        log("pause")
    }

    fun resume() {
        if (!active) return
        paused = false
        listening = false
        ignoreUntilMs = System.currentTimeMillis() + GRACE_MS
        main.removeCallbacks(watchdog)
        main.removeCallbacks(restart)
        // 稍等再开麦：TTS 的尾音还没散干净就收音会把结尾吃回去
        main.postDelayed(restart, RESUME_DELAY_MS)
        log("resume")
    }

    /** 让回声过滤器知道 agent 刚说过什么。 */
    fun noteSpoken(text: String) {
        lastSpokenPrefix = text.trim()
    }

    fun release() = stop()

    /** 判断一段转写是不是把我们自己刚念的话听回来了。 */
    private fun looksLikeEcho(heard: String, spoken: String): Boolean {
        fun words(s: String) = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 2 }
            .toSet()

        val h = words(heard)
        val s = words(spoken)
        if (h.isEmpty() || s.isEmpty()) return false
        val overlap = h.count { s.contains(it) }.toFloat() / h.size
        return overlap >= 0.5f
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(restart)
        main.postDelayed(restart, delayMs)
    }

    private fun startListening() {
        if (!active || paused || listening) return
        val r = recognizer ?: return
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 端点检测：停顿 900ms 就当一句话说完（默认更长，通话模式要快）
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MS)
        }
        runCatching { r.startListening(intent) }
            .onFailure { log("startListening failed: " + it.message) }
    }

    private fun log(msg: String) {
        runCatching {
            File(context.filesDir, "call_mode.log")
                .appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    private companion object {
        const val POST_ERROR_RESTART_MS = 350L
        const val RESUME_DELAY_MS = 900L
        const val GRACE_MS = 1_500L
        const val WATCHDOG_MS = 25_000L
        const val COMPLETE_SILENCE_MS = 900L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 600L
    }
}
