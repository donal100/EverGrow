package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.niki914.logging.Logger

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

    private const val TAG = "niki914_zafiro_AudioRouter"

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

    /** 耳机自己的麦克风：蓝牙 SCO / BLE 耳机 / USB / 有线耳麦。没有就返回 null。 */
    fun earbudsMic(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val order = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        for (type in order) {
            val hit = ins.firstOrNull { it.type == type }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 切到「通话」模式并等 SCO 真的亮起来：蓝牙耳麦的麦克风只在 SCO 上，
     * 没亮就录不到东西（这里历史上踩过一次，麦克风录到的全是静音）。
     */
    fun enterCallAudio(ctx: Context, waitMs: Long = 1500): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoAvailableOffCall) am.startBluetoothSco()
        }
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            if (am.isBluetoothScoOn) return true
            if (!am.isBluetoothScoAvailableOffCall) return false
            Thread.sleep(100)
        }
        return am.isBluetoothScoOn
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
    /**
     * USB 输入（会议麦克风、声卡）：比手机内置麦克风更近、更宽，也不占蓝牙链路。
     * 用户要求：插着 USB 就用 USB，没插才回落到手机麦克风。
     */
    fun usbMic(ctx: Context): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
    }

    /** 翻译/听写默认输入：USB 优先，否则手机内置麦克风。 */
    fun preferredMic(ctx: Context): AudioDeviceInfo? = usbMic(ctx) ?: builtInMic(ctx)

    /**
     * 等一会儿再判「有没有 USB 麦克风」。
     *
     * 插上之后系统要过一小会儿才会把它放进输入列表，而翻译只在开始时查一次，
     * 于是「刚插上就开翻译」会被判成没有 USB（14:56 那次就是这样退回手机麦克风的）。
     * 这里每 250ms 重查一次，最多等 timeoutMs，并把看到几个输入记进日志。
     */
    fun usbMicWait(ctx: Context, timeoutMs: Long = 1500L): AudioDeviceInfo? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var tries = 0
        while (true) {
            tries++
            val ins = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
            val usb = ins?.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            Logger.i(
                TAG,
                "usbMicWait try=" + tries + " inputs=" + (ins?.size ?: -1) +
                    " usb=" + (usb != null),
            )
            if (usb != null) return usb
            if (System.currentTimeMillis() >= deadline) return null
            runCatching { Thread.sleep(250L) }
        }
    }

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
