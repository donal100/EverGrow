package com.niki914.zafiro.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 把回答读出来。用系统 TTS（设备上的 com.google.android.tts），不是云端合成：
 * 即时、离线、免费，也不用再管一把 API key。
 *
 * 之前实测过 Qwen 的 qwen3-tts-flash（约 1.0s 出结果 + 下载 wav），音色更好但要联网，
 * 留作后续可替换实现——[SpeechOutput] 这一层接口先按最省事的路子来。
 *
 * 初始化是异步的：TextToSpeech 构造后要等 onInit 回调才能 speak。
 * 在此之前到达的文本先入队，就绪后按序播出，避免「第一条回答没声音」。
 */
class SpeechOutput(appContext: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pending = ArrayDeque<String>()

    /** 关闭后不再朗读（保留接口，方便以后接设置开关）。 */
    var enabled: Boolean = true

    init {
        tts = TextToSpeech(appContext) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                tts?.language = Locale.getDefault()
                while (pending.isNotEmpty()) speakNow(pending.removeFirst())
            } else {
                pending.clear()
            }
        }
    }

    /** 朗读一段文本；Markdown 会先被清洗，避免念出星号和代码围栏。 */
    fun speak(markdown: String) {
        if (!enabled) return
        val clean = toSpeakable(markdown)
        if (clean.isBlank()) return
        if (!isReady) {
            // 就绪前最多缓存一条，防止历史消息堆积后被连续播报
            pending.clear()
            pending.addLast(clean)
            return
        }
        speakNow(clean)
    }

    fun stop() {
        pending.clear()
        tts?.stop()
    }

    fun release() {
        pending.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply-" + System.currentTimeMillis())
    }

    companion object {
        /** 单次最多朗读的长度：长回答全念完会让人等太久，超出部分截断。 */
        private const val MAX_CHARS = 700

        /**
         * Markdown -> 可朗读纯文本。
         *
         * 顺序有讲究：先去掉代码块，否则里面的 # 和 * 会被后面的规则当成格式处理。
         */
        fun toSpeakable(raw: String): String {
            var t = raw
            t = t.replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
            t = Regex("`([^`]*)`").replace(t) { it.groupValues[1] }
            t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
            t = Regex("\\[([^\\]]*)]\\([^)]*\\)").replace(t) { it.groupValues[1] }
            t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            t = t.replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), " ")
            t = t.replace(Regex("[*_~>|#]"), "")
            t = t.replace(Regex("\\s+"), " ")
            t = t.trim()
            return if (t.length > MAX_CHARS) t.take(MAX_CHARS).trimEnd() + "..." else t
        }
    }
}
