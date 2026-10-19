package com.niki914.zafiro.app.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 通话模式外壳：听写在 ListenService（前台服务）里跑，这样锁屏也还在听。
 * 这里只负责启动/停止服务，并接住转写结果。
 */
class CallModeController(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
) {

    @Volatile private var lastSpoken: String = ""
    @Volatile private var lastSpokenAt: Long = 0L

    var bargeIn = false
    var onBargeIn: (() -> Boolean)? = null

    fun start() {
        ListenBus.onSentence = { text -> if (!echoLikely(text)) onUtterance(text) }
        ListenBus.onSpeechStart = { heard ->
            // 打断也要过回声过滤：麦克风会听见我自己，半个词就足以把回答掐掉
            // （实测识别出一个 "here" 就停了整句）。
            if (VoiceOutbox.isBusy() && heard.length >= 4 && !echoLikely(heard)) {
                log("barge-in on: " + heard.take(40))
                VoiceOutbox.stop()
            }
        }
        val intent = Intent(context, ListenService::class.java).setAction(ListenService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun stop() {
        ListenBus.onSentence = null
        ListenBus.onSpeechStart = null
        runCatching { context.stopService(Intent(context, ListenService::class.java)) }
    }

    fun pause() = Unit
    fun resume() = Unit
    fun listenNow() = Unit
    fun setSpeaking(speaking: Boolean) = Unit

    fun noteSpoken(text: String) {
        lastSpoken = text
        lastSpokenAt = System.currentTimeMillis()
    }

    fun release() = stop()

    private fun log(msg: String) {
        runCatching {
            val f = java.io.File(context.filesDir, "call_mode.log")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    private fun echoLikely(heard: String): Boolean {
        if (lastSpoken.isEmpty()) return false
        if (System.currentTimeMillis() - lastSpokenAt > 25_000) return false
        val h = heard.lowercase().split(Regex("[^a-z0-9\u4e00-\u9fff]+")).filter { it.length > 1 }
        val s = lastSpoken.lowercase()
        if (h.isEmpty()) return false
        return h.count { s.contains(it) }.toFloat() / h.size >= 0.6f
    }
}
