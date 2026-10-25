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
        val pick = earbudsIn(out)
        return pick?.let { describe(it.type) } ?: "phone speaker (no earphones connected)"
    }

    /** 同一副耳机有两个输出设备时，优先挑能放立体声的那个（见 earphones 的注释）。 */
    private fun earbudsIn(out: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val order = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        for (type in order) {
            val hit = out.firstOrNull { it.type == type }
            if (hit != null) return hit
        }
        return null
    }

    fun reset(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (am.isBluetoothScoOn) am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    /**
     * 耳机输出设备（给 AudioTrack.setPreferredDevice 用）。
     *
     * 顺序是重点，不是随手写的：一副蓝牙耳机会同时挂 A2DP 和 SCO 两个输出设备，
     * 实测 HUAWEI FreeClip 2 的 SCO(id 5055) 排在 A2DP(id 5065) 前面。
     * 把媒体音轨绑到没有激活的 SCO 上 = 彻底没声音（插着耳机就没声、拔了就有声，
     * 现象和「分声道坏了」一模一样）。所以先挑 A2DP，SCO 只当兜底。
     */
    fun earphones(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        for (type in order) {
            val hit = out.firstOrNull { it.type == type }
            if (hit != null) return hit
        }
        return null
    }

    /** 这个设备能不能分左右声道：A2DP / 有线 / USB 都是立体声，SCO 是窄带单声道。 */
    fun isStereoOut(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> true
        else -> false
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
