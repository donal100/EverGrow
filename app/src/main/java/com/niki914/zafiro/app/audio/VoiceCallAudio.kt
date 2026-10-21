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
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // 读回来，别相信设置成功：缺 MODIFY_AUDIO_SETTINGS 时 setMode 会被静默忽略
            // （实测 getMode 立刻回到 0），日志却说自己设成功了 —— 那是骗自己。
            log("enter: mode=" + am.mode + " wanted=" + AudioManager.MODE_IN_COMMUNICATION + " focus=" + granted)

            if (hasHeadset()) {
                // SCO 是耳机麦克风唯一的路；没有 BLUETOOTH_CONNECT 的话这一步会静默失效
                runCatching { am.stopBluetoothSco() }
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                // SCO 建链是异步的：放到后台线程等，绝不能在主线程 sleep（会 ANR）
                Thread {
                    var waited = 0
                    while (waited < 3000 && !am.isBluetoothScoOn) {
                        runCatching { Thread.sleep(200) }
                        waited += 200
                    }
                    log("sco: on=" + am.isBluetoothScoOn + " after " + waited + "ms")
                    log("inputs=" + devices(AudioManager.GET_DEVICES_INPUTS))
                }.apply { isDaemon = true }.start()
            } else {
                am.isSpeakerphoneOn = true
                log("no headset: speakerphone on, phone mic")
            }
            log("inputs=" + devices(AudioManager.GET_DEVICES_INPUTS))
            log("outputs=" + devices(AudioManager.GET_DEVICES_OUTPUTS))
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
}
