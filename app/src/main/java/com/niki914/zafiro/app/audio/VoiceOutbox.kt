package com.niki914.zafiro.app.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 唯一出口：把 agent 的最终文本交给 TTS。
 *
 * 设计上刻意做得笨：上面把「当前这条回答的全文」推过来，这里只负责
 *   1) 跟上次念过的比，算出新增部分（追加或整段替换都能处理）；
 *   2) 交给 VoiceReplySpeaker；
 *   3) 按钮一按就 stop()。
 *
 * 不猜 turn id、不看 UI 列表、不做时序推断 —— 之前所有 bug 都出在「猜哪段文本是新的」。
 */
object VoiceOutbox {

    private var speaker: VoiceReplySpeaker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否启用（通话模式开启时置真）。 */
    @Volatile
    var enabled: Boolean = false

    /** 上一次已经交给 TTS 的全文，用来算增量。 */
    private var lastFull: String = ""

    /** 用户按下按钮时调用：立刻停声并清空记忆，下一次回答从头念。 */
    fun stop() {
        speaker?.stop()
        lastFull = ""
    }

    /** 一句话念完（队列空了）时回调；通话模式用它重新开麦。 */
    var onSpeechIdle: (() -> Unit)? = null

    fun attach(context: Context) {
        if (speaker == null) {
            speaker = VoiceReplySpeaker(context.applicationContext).also { sp ->
                sp.onIdle = {
                    val hook = onSpeechIdle
                    if (hook != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { hook() }
                    }
                }
            }
        }
    }

    fun isBusy(): Boolean = speaker?.isBusy() == true

    fun release() {
        speaker?.release()
        speaker = null
        lastFull = ""
    }

    /**
     * 推入「当前回答的全文」。会被流式地反复调用，只有新增部分会被念。
     */
    fun onAssistantText(fullText: String) {
        val clean = fullText.trim()
        if (clean.isEmpty()) return
        if (!enabled) {
            lastFull = clean
            return
        }
        val previous = lastFull
        val fresh = when {
            clean == previous -> return
            clean.startsWith(previous) -> clean.substring(previous.length)
            else -> clean          // 文本被替换（短回答常见）
        }
        lastFull = clean
        val chunk = fresh.trim()
        if (chunk.isEmpty()) return
        val sp = speaker ?: return
        sp.submit(chunk)
    }

    /** 新一轮开始：允许把同样的文本再念一次。 */
    fun beginTurn() {
        lastFull = ""
    }
}
