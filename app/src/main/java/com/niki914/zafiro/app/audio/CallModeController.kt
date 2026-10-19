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
        ListenBus.onSentence = { text ->
            when {
                // 正在播报时，麦克风听到的多半是我自己；外放没有打断功能，直接丢弃
                VoiceOutbox.isBusy() -> log("dropped while speaking: " + text.take(40))
                echoLikely(text) -> log("dropped as echo: " + text.take(40))
                else -> onUtterance(text)
            }
        }
        // 打断只在戴耳机时启用。
        // 外放时麦克风必然听见喇叭，任何判断都会在「真打断」和「听见自己」之间摇摆
        // —— 这是行业里通行的取舍：Deepgram 等给出的生产建议就是外放走半双工、
        // 打断留给耳机。有线/蓝牙耳机都算。
        val headphones = hasHeadphones()
        log("barge-in=" + (if (headphones) "on" else "off") + " (headphones=" + headphones + ")")
        ListenBus.onSpeechStart = if (!headphones) null else { heard ->
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

    /** 最近说过的全部文字（滚动保留），回声判断要看整体，不是最后一句。 */
    private val recent = StringBuilder()

    fun noteSpoken(text: String) {
        lastSpoken = text
        lastSpokenAt = System.currentTimeMillis()
        recent.append(" ").append(text)
        if (recent.length > 1200) recent.delete(0, recent.length - 1200)
    }

    fun release() = stop()

    /** 当前是否有耳机输出（有线/蓝牙/USB）。 */
    private fun hasHeadphones(): Boolean = runCatching {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }.getOrDefault(false)

    private fun log(msg: String) {
        runCatching {
            val f = java.io.File(context.filesDir, "call_mode.log")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    private fun echoLikely(heard: String): Boolean {
        if (lastSpoken.isEmpty()) return false
        if (System.currentTimeMillis() - lastSpokenAt > 45_000) return false
        val h = heard.lowercase().split(Regex("[^a-z0-9\u4e00-\u9fff]+")).filter { it.length > 1 }
        val s = recent.toString().lowercase()
        if (h.isEmpty()) return false
        // 实测我自己的话被听回来时，只能对上三四成词，0.6 太严
        return h.count { s.contains(it) }.toFloat() / h.size >= 0.4f
    }
}
