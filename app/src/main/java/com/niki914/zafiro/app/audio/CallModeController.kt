package com.niki914.zafiro.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.launch
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

    /**
     * 打断（barge-in）：念话期间仍然听，用户一开口就掐掉播放。
     * 只在戴耳机时开启 —— 外放时麦克风会把 agent 自己的声音当成人声，自己打断自己。
     */
    @Volatile
    var bargeIn = false

    /**
     * 用户开口打断时要做什么。返回 true 表示「确实打断了」；返回 false 就按正常收音处理 ——
     * 否则用陈旧状态误判一次，这句话就被吞掉了（用户看到的就是「听不见我」）。
     */
    var onBargeIn: (() -> Boolean)? = null

    /** agent 是否正在播报。 */
    @Volatile
    private var speakingNow = false

    /**
     * 识别器每次开麦/收尾都有自己的提示音，通话模式不停地重开，于是「哔、哔、哔」。
     * Android 没有关闭它的 API，只能在我们收音期间把系统/通知两路音量压到 0 ——
     * 媒体音量绝不碰：跑步时用户多半在放音乐。
     */
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 自己的提示音：TONE_PROP_ACK，12% 音量，走媒体流。
     * 12% 是实测选出来的 —— 安静时听得见，不刺耳；街上风大可能要往上调。
     */
    private val cue by lazy {
        runCatching { android.media.ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull()
    }

    /**
     * 静音模式：识别器每次开麦都会响提示音，Android 没有关闭它的 API。
     * 唯一的办法是在收音期间把系统/通知两路音量压到 0；媒体音量绝不碰，
     * 跑步时的音乐照常放。
     */
    private val mutedStreams = HashMap<Int, Int>()

    /** 同一路麦克风的备份录音：只有用户说「你听错了」时才需要它。 */
    private val recorder = UtteranceRecorder(context.filesDir)

    /** 交出去之后等这么久还没人 resume() 就自己接上，避免卡死在「已暂停」。 */
    private val watchdog = Runnable { resume() }

    private val restart = Runnable { startListening() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log("ready")
            runCatching { cue?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, CUE_MS) }
        }
        override fun onBeginningOfSpeech() {
            recorder.markSpeechStart()
            log("speech start")
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            recorder.markSpeechEnd()
            log("speech end")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // 只看最终结果：中间态会让 agent 收到半句话
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            listening = false
            log("error=" + error)
            // 12 = LANGUAGE_NOT_SUPPORTED，13 = LANGUAGE_UNAVAILABLE：
            // 这台机器没装离线模型，见到就当配置问题记一笔，不要死循环
            if (error == 12 || error == 13) {
                log("language unavailable for the recogniser - check the language setting")
            }
            // 6/7（no match / speech timeout）是正常的「静默」结果；5 是客户端状态没准备好。
            // 都退避重开，连续失败就拉长间隔，避免把识别器打成 error=5 风暴。
            // 6/7（没听到 / 静默超时）：识别器已经自己结束了，VAD 会在下次开口时重开它。
            // 这里不再定时重开 —— 那正是「空窗里说话被吃掉」的来源。
            if (error == 6 || error == 7) {
                errorStreak = 0
                return
            }
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
                return
            }
            if (text.length < 2) return
            // 回声：麦克风会把自己刚念的内容听回来。只比开头 8 个字不够 ——
            // 实测把「That's the first time the loop has behaved…」整句收了进来。
            // 改成词集合重合度：超过一半的词一样，就当回声丢掉。
            if (lastSpokenPrefix.isNotEmpty() && looksLikeEcho(text, lastSpokenPrefix)) {
                log("dropped echo-like transcript: " + text.take(60))
                return
            }
            if (text == lastUtterance) return
            lastUtterance = text
            // 先把音频存档（连同这次识别结果），万一听错了还能回头重听。
            // 注意：这里**不**自动重听。「削头就用 Qwen 补一遍」那条自动路径已经移除 ——
            // 背景音乐会让能量检测误判，每次都多花一次 Qwen 调用。要重听就手动来。
            runCatching { recorder.saveUtterance(text) }
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
        restoreCues()
        // 上一次若是异常退出，音量可能还停在 0：进来先把状态清干净
        main.post {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                }
            }
            log("start")
            // 自己听：只有检测到有人说话才开识别器。空闲时它完全关着 ——
            // 没有超时、没有空窗、也没有那串空闲提示音。
            recorder.onVoiceActivity = { speaking ->
                main.post {
                    if (!active) return@post
                    if (speakingNow && bargeIn && speaking) {
                        val handled = onBargeIn?.invoke() ?: false
                        if (handled) {
                            log("vad: user spoke while we were talking -> barge in")
                            return@post
                        }
                    }
                    if (paused) return@post
                    if (speaking) {
                        if (!listening) log("vad: speech onset -> open recogniser")
                        startListening()
                    } else {
                        log("vad: speech end")
                    }
                }
            }
            recorder.start()
        }
    }

    fun stop() {
        active = false
        paused = false
        listening = false
        restoreCues()
        main.removeCallbacks(restart)
        main.removeCallbacks(watchdog)
        main.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            recorder.onVoiceActivity = null
            recorder.stop()
            log("stop")
        }
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        listening = false
        restoreCues()
        main.removeCallbacks(restart)
        main.post { runCatching { recognizer?.cancel() } }
        log("pause")
    }

    fun resume() {
        if (!active) return
        paused = false
        listening = false
        // 静音窗口：TTS 尾音还在空气里时，VAD 可能把余音当成人声
        ignoreUntilMs = System.currentTimeMillis() + GRACE_MS
        main.removeCallbacks(watchdog)
        main.removeCallbacks(restart)
        // 不再主动开麦：VAD 检测到用户开口才开。这是「不轮询」的关键。
        log("resume")
    }

    /** 立刻开麦（打断之后用），不等下一次 VAD 上升沿。 */
    fun listenNow() {
        if (!active) return
        paused = false
        listening = false
        ignoreUntilMs = 0L
        main.post { startListening() }
    }

    /** 播报开始/结束时告诉控制器，打断逻辑靠它判断。 */
    fun setSpeaking(speaking: Boolean) {
        speakingNow = speaking
    }

    /** 让回声过滤器知道 agent 刚说过什么。 */
    fun noteSpoken(text: String) {
        lastSpokenPrefix = text.trim()
    }

    fun release() {
        stop()
        recorder.release()
    }

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
        muteCues()
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



    private fun muteCues() {
        val am = audio ?: return
        for (stream in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            runCatching {
                val current = am.getStreamVolume(stream)
                if (current > 0 && !mutedStreams.containsKey(stream)) {
                    mutedStreams[stream] = current
                    am.setStreamVolume(stream, 0, 0)
                }
            }
        }
    }

    private fun restoreCues() {
        val am = audio ?: return
        mutedStreams.forEach { (stream, volume) -> runCatching { am.setStreamVolume(stream, volume, 0) } }
        mutedStreams.clear()
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
        /** 提示音：ACK，12% 音量，60ms。 */
        const val CUE_VOLUME = 12
        const val CUE_MS = 60
        const val GRACE_MS = 1_500L
        const val WATCHDOG_MS = 25_000L
        const val COMPLETE_SILENCE_MS = 900L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 600L
    }
}
