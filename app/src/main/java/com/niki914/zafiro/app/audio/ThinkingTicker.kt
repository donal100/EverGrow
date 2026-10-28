package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * 听写模式的回执提示音：**一声**短音，表示「收到了，正在做」。
 *
 * 走到这一版试过三种，都失败了，所以写在这里免得再来一次：
 * 1. 两声「嗒嗒」—— 听着像出错，不像回执。
 * 2. 每 1.2 秒循环一声 —— 像卡住，而且一直响。
 * 3. 念一句「I am working on it」—— 外放时麦克风把它听回去，识别成用户的请求，
 *    于是又回执、又被听见……自己和自己对话（2026-10-28 09:46 的日志）。
 *
 * 所以：一声，不循环，不出人声。voice_tuning.txt 里 ack_voice=0 可整条关掉。
 */
class ThinkingTicker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val beep: ShortArray by lazy { tone(BEEP_HZ, BEEP_MS) }

    /** 收到一句用户的话：响一声就走。 */
    fun acknowledge() {
        if (!enabled()) return
        play(beep)
        log("ack: beep")
    }

    /** 老接口：循环已经没有了，保留空实现以免调用方改动。 */
    fun stop() = Unit

    fun release() = Unit

    /** 直接写一段 PCM 到 AudioTrack：不开线程、不占焦点，几十毫秒播完。 */
    private fun play(samples: ShortArray) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        }.getOrNull()
        if (track == null) {
            log("play failed: no AudioTrack")
            return
        }
        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, 600L)
        }.onFailure { log("play error: " + it.message) }
    }

    /** 70ms 的正弦，带 8ms 淡入淡出，避免咔哒声。 */
    private fun tone(hz: Double, ms: Int): ShortArray {
        val n = (RATE * ms / 1000.0).toInt()
        val out = ShortArray(n)
        val fade = (RATE * 8 / 1000.0).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val v = sin(2 * PI * hz * i / RATE) * env * AMPLITUDE
            out[i] = v.toInt().toShort()
        }
        return out
    }

    private fun enabled(): Boolean = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching true
        f.readLines().firstOrNull { it.trim().startsWith("ack_voice=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  [tick] " + msg + "\n")
        }
    }

    private companion object {
        const val RATE = 24_000
        const val AMPLITUDE = 4500.0
        const val BEEP_HZ = 880.0
        const val BEEP_MS = 70
        /** 默认 50% 音量：原来的 9000 太响，用户要求减半。 */
    }
}
