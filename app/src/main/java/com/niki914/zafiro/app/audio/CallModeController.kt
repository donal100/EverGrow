package com.niki914.zafiro.app.audio

import android.content.Context

/**
 * 通话模式外壳。麦克风一直在听，所以不需要 pause/resume ——
 * 播报期间转写到的内容，用「和自己刚说过的话比对」来丢掉。
 *
 * 比对规则：只在这个时间窗内、且词的重复率超过一半时才算回声。
 * 这样「好的」「九」这种正常回答不会被误杀。
 */
class CallModeController(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
) {

    @Volatile private var lastSpoken: String = ""
    @Volatile private var lastSpokenAt: Long = 0L

    private val engine = ListenEngine(context) { text ->
        if (!echoLikely(text)) onUtterance(text)
    }

    var bargeIn = false
    var onBargeIn: (() -> Boolean)? = null

    fun start() = engine.start()
    fun stop() = engine.stop()

    /** 播报期间照样在听，所以这些接口现在是空的。 */
    fun pause() = Unit
    fun resume() = Unit
    fun listenNow() = Unit
    fun setSpeaking(speaking: Boolean) = Unit

    /** 记住刚念出去的话，用于回声判断。 */
    fun noteSpoken(text: String) {
        lastSpoken = text
        lastSpokenAt = System.currentTimeMillis()
    }

    fun release() = engine.stop()

    private fun echoLikely(heard: String): Boolean {
        if (lastSpoken.isEmpty()) return false
        if (System.currentTimeMillis() - lastSpokenAt > 25_000) return false
        val h = heard.lowercase().split(Regex("[^a-z0-9\u4e00-\u9fff]+")).filter { it.length > 1 }
        val s = lastSpoken.lowercase()
        if (h.isEmpty()) return false
        val overlap = h.count { s.contains(it) }.toFloat() / h.size
        return overlap >= 0.6f
    }
}
