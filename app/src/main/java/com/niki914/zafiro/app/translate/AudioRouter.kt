package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 耳机单向口译的音频路由。
 *
 * 目标：麦克风仍然用手机的（贴身、离说话人近），译文只走耳机。
 * 这样喇叭的声音完全不进麦克风 —— 回声问题从根上消失，麦克风可以一直开着。
 *
 * 用的是 MODE_IN_COMMUNICATION + 关闭外放：系统会把输出交给已连接的耳机
 * （有线/蓝牙/USB），同时输入可按设备挑。蓝牙走 SCO 时音质是通话音质，
 * 延迟也更明显，这点没办法，是蓝牙协议的问题。
 */
object AudioRouter {

    fun routeToEarphones(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "no audio service"
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoAvailableOffCall) {
                runCatching { am.startBluetoothSco() }
                runCatching { am.isBluetoothScoOn = true }
            }
        }
        val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val pick = out.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return pick?.let { describe(it.type) } ?: "phone speaker (no earphones connected)"
    }

    fun reset(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (am.isBluetoothScoOn) am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /** 手机内置麦克风：即使插着耳机也坚持用它。 */
    fun builtInMic(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun describe(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        else -> "output " + type
    }
}
