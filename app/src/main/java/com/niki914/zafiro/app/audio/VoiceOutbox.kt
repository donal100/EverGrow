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

    /**
     * 还没念出去的增量。流式回答是一小片一小片来的，直接送进 TTS 会把句子切碎：
     * 实测「Berlin」先到，随后到的是「.」，那个句号自己进去就变成了一句「dot」
     * （Qwen 对纯标点直接 400，于是兜底的本机 TTS 把句号念了出来）。
     * 所以先攒着，遇到句子结束符或够长了才发。
     */
    private val pending = StringBuilder()

    /** 用户按下按钮时调用：立刻停声并清空记忆，下一次回答从头念。 */
    fun stop() {
        speaker?.stop()
        pending.setLength(0)
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
        pending.append(fresh)
        maybeFlush()
    }

    /**
     * 攒够了才发。目标是「一段话一个音频文件」，而不是「一个句子一个文件」：
     * 每发一次就是一次独立的 Qwen 请求、一个独立的 wav、一次独立的播放，
     * 接缝处语调会重置，听起来像换了口型。
     *
     * 规则：
     *  - 段落结束（空行）立刻发；
     *  - 句末标点且已经攒到 MIN_CLIP_CHARS（约两句话）才发；
     *  - 超过 MAX_CLIP_CHARS 无论如何先发，长回答不能等太久；
     *  - 回合结束时 flush() 把尾巴发掉。
     */
    private fun maybeFlush() {
        val trimmed = pending.toString().trim()
        if (trimmed.isEmpty()) {
            pending.setLength(0)
            return
        }
        val endsParagraph = pending.contains("\n\n") || pending.contains("\n\r")
        val endsSentence = trimmed.last() in ".!?;:。！？；：\n"
        val longEnough = trimmed.length >= MIN_CLIP_CHARS
        val tooLong = trimmed.length >= MAX_CLIP_CHARS
        if (!(endsParagraph || (endsSentence && longEnough) || tooLong)) return
        pending.setLength(0)
        submitSafe(trimmed)
    }

    /** 只发「有字」的内容：纯标点、纯空白一律丢掉（TTS 会 400）。 */
    private fun submitSafe(chunk: String) {
        if (chunk.none { it.isLetterOrDigit() }) return
        speaker?.submit(chunk)
    }

    /** 回合结束：把攒着的尾巴念掉。 */
    fun flush() {
        val text = pending.toString().trim()
        pending.setLength(0)
        if (text.isNotEmpty()) submitSafe(text)
    }

    private companion object {
        /** 一段音频至少这么长（约两句话，8-10 秒语音）才发出去，接缝更少。 */
        const val MIN_CLIP_CHARS = 200
        /** 上限：再长就先发，别让长回答迟迟不出声。 */
        const val MAX_CLIP_CHARS = 420
    }

    /** 新一轮开始：允许把同样的文本再念一次。 */
    fun beginTurn() {
        pending.setLength(0)
        lastFull = ""
    }
}
