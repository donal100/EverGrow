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

    private companion object {
        /** 开播到真正出声有几十到几百毫秒延迟，窗口起点往前让一点。 */
        const val LEAD_PAD_MS = 250L
        /** 播完的拖尾：播放器收尾 + 房间余响。 */
        const val TAIL_PAD_MS = 900L
    }

    /**
     * 播报时间窗：[playStartAt, playEndAt] 之外才是用户的声音。
     *
     * 之前外放时靠「播报开始静音、结束恢复」这套 mute/unmute 状态机挡回声，
     * 它每卡一次就永久聋掉（实测两次：一次播放结束后 10 秒再也没有音频，
     * 一次十二小时的 duration 垃圾值把麦克风睡死）。现在麦克风不停，
     * 只用时间戳把「我说话的那段」对应的转写丢掉 —— 没有状态可卡。
     */
    @Volatile private var playStartAt: Long = 0L
    @Volatile private var playEndAt: Long = 0L

    var bargeIn = false
    var onBargeIn: (() -> Boolean)? = null

    fun start() {
        // 上一次会话可能留下静音状态和半开的播报窗口，进来先清掉
        ListenBus.setMuted(false)
        playStartAt = 0L
        playEndAt = 0L
        ListenBus.onSentence = { text ->
            when {
                // 外放 + 落在播报时间窗内：这段一定是喇叭的回声，按时间戳丢弃。
                // 窗口有两个来源：app 自己的播报（micOff/micOn）和 py 播报工具
                // （它写 filesDir/speech_window.txt，因为那条路径没有经过 micOff/micOn）。
                !audioOnHeadset() && (
                    insidePlaybackWindow(System.currentTimeMillis()) ||
                        insideToolWindow(System.currentTimeMillis())
                    ) -> log(
                        "dropped by time window: app=" + (if (insidePlaybackWindow(System.currentTimeMillis())) "open" else "closed") +
                            " tool=" + (if (insideToolWindow(System.currentTimeMillis())) "open" else "closed") +
                            " text=" + text.take(30)
                    )
                // 正在播报时，麦克风听到的多半是我自己；外放没有打断功能，直接丢弃
                VoiceOutbox.isBusy() -> log("dropped while speaking: " + text.take(40))
                // 不再用「文本像不像我说过的话」来判回声：用户复述、引用、讨论我刚才
                // 说的内容都会命中，实测 21:29 连着两条正常发言被当成 echo 丢掉。
                // 回声已经由上面的时间窗解决，文本比对只在打断判断里保留。
                else -> onUtterance(text)
            }
        }
        // 打断只在戴耳机时启用。
        // 外放时麦克风必然听见喇叭，任何判断都会在「真打断」和「听见自己」之间摇摆
        // —— 这是行业里通行的取舍：Deepgram 等给出的生产建议就是外放走半双工、
        // 打断留给耳机。有线/蓝牙耳机都算。
        headphones = hasHeadphones()
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

    private var headphones = false

    /** 播报开始：外放时把麦克风静音，耳机时保持收听（用于打断）。 */
    fun micOff() {
        // 不再静音：只记下窗口起点。判断依据是「现在这一刻」的输出状态，
        // 而不是「有没有配对过耳机」—— 蓝牙连着但声音从扬声器出来也算外放。
        playStartAt = System.currentTimeMillis()
        playEndAt = 0L
        ListenBus.setMuted(false)
        if (audioOnHeadset()) {
            log("playback window opened (headset) - listening through")
        } else {
            log("playback window opened (speaker) - mic stays live, drop by timestamp")
        }
    }

    /** 播报结束：关窗，并留一段尾巴把扬声器拖尾也吞掉。 */
    fun micOn() {
        playEndAt = System.currentTimeMillis() + TAIL_PAD_MS
        ListenBus.setMuted(false)
        log("playback window closed (+" + TAIL_PAD_MS + "ms tail)")
    }


    /**
     * py 播报工具留下的时间窗：内容 "startMs endMs"（endMs=0 表示仍在播）。
     * 没有这一条，工具说的每句话都会被麦克风听回来当成用户发言 —— 实测 20:11:31 就是这样。
     */
    private fun insideToolWindow(now: Long): Boolean = runCatching {
        val f = java.io.File(context.filesDir, "speech_window.txt")
        if (!f.isFile) return@runCatching false
        val parts = f.readText().trim().split(Regex("\\s+"))
        if (parts.size < 2) return@runCatching false
        val start = parts[0].toLongOrNull() ?: return@runCatching false
        val end = parts[1].toLongOrNull() ?: 0L
        // 文件是「py 播报工具正在出声」的声明；工具被重启/杀掉时 end 永远写不进，
        // 窗口就永远是「开着」的 —— 实测这就是 21:19 那三条被丢掉的真正原因。
        // 所以：没写结束时间的窗口只认 20 秒，而且整体超过 2 分钟的记录一律作废。
        if (now - start > 120_000L) return@runCatching false
        if (end == 0L) return@runCatching (now - start) < 20_000L
        return@runCatching now <= end + TAIL_PAD_MS
    }.getOrDefault(false)

    /** 当前时刻是否落在「我正在说话」的时间窗里（含前后余量）。 */
    private fun insidePlaybackWindow(now: Long): Boolean {
        if (playStartAt == 0L) return false
        if (now < playStartAt - LEAD_PAD_MS) return false
        // 窗口只靠 micOff 打开、micOn 关闭。任何一个没配上（实测：播报结束回调没来），
        // 窗口就会一直开着，于是每一句都被当成回声扔掉 —— 日志里连着三条
        // "dropped by time window" 就是这么来的。
        // 所以：没有结束时间时，只认开窗后 30 秒内的声音。
        if (playEndAt == 0L) return (now - playStartAt) < 30_000L
        return now <= playEndAt
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

    /** 声音此刻是否真的从耳机出来（有线/蓝牙 A2DP/USB）。 */
    private fun audioOnHeadset(): Boolean = runCatching {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val wired = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        wired || am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }.getOrDefault(false)

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
