package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.io.File

/**
 * 把听写当成「一通电话」，而不是「放音乐」。
 *
 * 语音对话和媒体播放的音频需求不同：对话要的是一条双向、持续、低延迟的通路，
 * 而且麦克风应该来自耳机而不是手机本体。Android 的模型正好对应 —— 通话模式
 * （MODE_IN_COMMUNICATION + 语音通话焦点 + SCO/HFP）。
 *
 * 代价是外放/耳机的输出也会降到通话音质（窄带、单声道），这是这笔交易的另一半：
 * 换来的是耳机麦克风可用、不用在 A2DP 和 SCO 之间来回切、没有切换的爆音，
 * 打断也能正常工作。语音对话里这个取舍是划算的，ChatGPT 语音模式同样这么做。
 *
 * 用 voice_tuning.txt 里的 call_audio=0 可以关掉（不用重新编译）。
 */
class VoiceCallAudio(private val context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null

    @Volatile private var active = false

    fun enabled(): Boolean = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching true
        val v = f.readLines().firstOrNull { it.trim().startsWith("call_audio=") }
            ?.substringAfter("=")?.trim()
        v != "0"
    }.getOrDefault(true)

    fun enter() {
        if (active) return
        if (!enabled()) {
            log("skip: call_audio=0 in voice_tuning")
            return
        }
        active = true
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            focus = req
            val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            log("enter: focus=" + granted)

            if (!hasHeadset()) {
                // 没有耳机就走原来的路：手机麦克风本来就能用，通话模式只会白降音质
                runCatching { am.isSpeakerphoneOn = true }
                log("no headset: staying NORMAL mode, phone mic, mode=" + am.mode)
                return@runCatching
            }

            // 有耳机才尝试 SCO。关键：不要自己 setBluetoothScoOn(true) ——
            // 那只是把标志位点亮，真链路没建起来，录音器就被指到不存在的通道上，
            // 结果是一片静音（实测 error=7 连发十几次，用户说「什么都听不到」）。
            // 真正的判据是输入设备列表里出现 SCO/蓝牙耳机输入。
            runCatching { am.stopBluetoothSco() }
            am.startBluetoothSco()
            Thread {
                var waited = 0
                var scoInput: AudioDeviceInfo? = null
                while (waited < 2000) {
                    scoInput = scoInputDevice()
                    if (scoInput != null) break
                    runCatching { Thread.sleep(200) }
                    waited += 200
                }
                if (scoInput != null) {
                    // 只在链路真的存在时才进通话模式
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    log("sco: input=" + scoInput.productName + " after " + waited + "ms mode=" + am.mode)
                } else {
                    runCatching { am.stopBluetoothSco() }
                    am.mode = AudioManager.MODE_NORMAL
                    log("sco: unavailable after " + waited + "ms - phone mic, mode=" + am.mode)
                }
                log("inputs=" + devices(AudioManager.GET_DEVICES_INPUTS))
            }.apply { isDaemon = true }.start()
        }.onFailure { log("enter failed: " + it.message) }
    }

    fun exit() {
        if (!active) return
        active = false
        runCatching {
            am.isBluetoothScoOn = false
            runCatching { am.stopBluetoothSco() }
            am.isSpeakerphoneOn = false
            // 必须还原：卡在通话模式会让其他 app 的音频一直像打电话
            am.mode = AudioManager.MODE_NORMAL
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            log("exit: mode=NORMAL")
        }.onFailure { log("exit failed: " + it.message) }
    }

    private fun scoInputDevice(): AudioDeviceInfo? = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 /* BLE_HEADSET */
        }
    }.getOrNull()

    private fun firstInputName(): String = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull()?.productName ?: "none"
    }.getOrDefault("?")

    /**
     * 播报结束后把通话路由拉回来。
     *
     * 实测故障：听写进行中我播了一段回复，回复走媒体通道，耳机被交还给 A2DP，
     * SCO 麦克风链路跟着消失 —— 播完之后没有任何东西把它建回去。之后的每一次
     * 说话都是 onBeginningOfSpeech / onEndOfSpeech + error=7，一句都识别不出，
     * 日志里只有 error=7 和 "writer: Stream Closed"，看起来像听写坏了。
     */
    fun restoreAfterPlayback(force: Boolean = false) {
        if (!enabled()) return
        if (!force && !active) return
        if (!hasHeadset()) return
        if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            log("route restore skipped: mode=" + am.mode)
            return
        }
        Thread {
            val before = firstInputName()
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            runCatching { am.stopBluetoothSco() }
            runCatching { am.startBluetoothSco() }
            var waited = 0
            var dev: AudioDeviceInfo? = null
            while (waited < 2000) {
                dev = scoInputDevice()
                if (dev != null) break
                runCatching { Thread.sleep(100) }
                waited += 100
            }
            if (dev == null) {
                // 拉不回来就别硬撑通话模式，退回手机麦克风，至少还能听见
                runCatching { am.stopBluetoothSco() }
                runCatching { am.mode = AudioManager.MODE_NORMAL }
            }
            log(
                "route restored after playback: before=" + before +
                    " after=" + (dev?.productName ?: "none") +
                    " waited=" + waited + "ms mode=" + am.mode
            )
        }.apply { isDaemon = true }.start()
    }

    private fun hasHeadset(): Boolean = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
    }.getOrDefault(false)

    private fun devices(flag: Int): String = runCatching {
        am.getDevices(flag).joinToString(",") { it.type.toString() + ":" + it.productName }
    }.getOrDefault("?")

    private fun log(msg: String) {
        runCatching {
            File(context.filesDir, "call_mode.log")
                .appendText(System.currentTimeMillis().toString() + "  [call] " + msg + "\n")
        }
    }

    companion object {
        /** 给 Python（py_speak_qwen 等）用：播完就调一次，把 SCO 麦克风拉回来。 */
        fun restoreRoute(context: Context) {
            runCatching { VoiceCallAudio(context).restoreAfterPlayback(force = true) }
        }
    }
}
