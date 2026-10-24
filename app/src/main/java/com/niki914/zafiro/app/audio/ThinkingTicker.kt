package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * 听写模式的「收到，正在做」提示音。
 *
 * 第一版用 ToneGenerator + STREAM_MUSIC，实测一声都没有 —— 通话模式下 STREAM_MUSIC
 * 被路由到 HFP 的窄带通路，音乐流进去就没了。改成自己合成一段正弦波，用
 * AudioTrack 以 USAGE_VOICE_COMMUNICATION 播放：和我的说话走同一条路，
 * 人耳听得到，也说明「这条路是活的」。
 *
 * 第一声是回执（两声短促的「嗒嗒」），之后每 1.2 秒一声，开口说话就停。
 * voice_tuning.txt: thinking_tick=0 关闭，thinking_tick_ms 调间隔。
 */
class ThinkingTicker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var startedAt = 0L

    private val beep: ShortArray by lazy { tone(880.0, 60) }
    private val beepLow: ShortArray by lazy { tone(660.0, 50) }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // 我一开始说话就停：py 播报走的是另一条路径，不会调用 micOff，
            // 所以这里自己看 speech_window.txt（py_speak* 播放前会写它）。
            if (ownVoicePlaying()) { log("tick stop: our voice started"); stop(); return }
            if (System.currentTimeMillis() - startedAt > MAX_MS) { stop(); return }
            play(beepLow)
            main.postDelayed(this, intervalMs())
        }
    }

    /** py 播报工具写的「我正在出声」时间窗；窗口内就别再嘀了。 */
    private fun ownVoicePlaying(): Boolean = runCatching {
        val f = File(context.filesDir, "speech_window.txt")
        if (!f.isFile) return@runCatching false
        val parts = f.readText().trim().split(Regex("\\s+"))
        if (parts.size < 2) return@runCatching false
        val start = parts[0].toLongOrNull() ?: return@runCatching false
        val end = parts[1].toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - start > 120_000L) return@runCatching false
        if (end == 0L) return@runCatching (now - start) < 20_000L
        now in start..(end + 2_500L)
    }.getOrDefault(false)

    fun acknowledge() {
        if (!enabled()) return
        startedAt = System.currentTimeMillis()
        play(beep)
        main.postDelayed({ play(beep) }, 130L)
        if (!running) {
            running = true
            main.postDelayed(tick, 900L)
            log("tick start every " + intervalMs() + "ms (vol=" + mediaVolume() + ")")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        log("tick stop")
    }

    fun release() = stop()

    /** 直接写一段 PCM 到 AudioTrack：不开线程、不占焦点，几毫秒就播完。 */
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
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        }.getOrNull()
        if (track == null) { log("play failed: no AudioTrack"); return }
        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
            // STATIC 模式播完会自动停；这里留一点时间再释放，避免被截断
            main.postDelayed({ runCatching { track.release() } }, 400L)
        }.onFailure { log("play error: " + it.message) }
    }

    /** 60ms 的正弦，带 8ms 淡入淡出，避免咔哒声。 */
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

    private fun mediaVolume(): Int = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    }.getOrDefault(-1)

    private fun enabled(): Boolean = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching true
        f.readLines().firstOrNull { it.trim().startsWith("thinking_tick=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    private fun intervalMs(): Long = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching 1200L
        f.readLines().firstOrNull { it.trim().startsWith("thinking_tick_ms=") }
            ?.substringAfter("=")?.trim()?.toLongOrNull() ?: 1200L
    }.getOrDefault(1200L).coerceIn(400L, 5000L)

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  [tick] " + msg + "\n")
        }
    }

    private companion object {
        const val RATE = 24_000
        const val AMPLITUDE = 9000.0
        const val MAX_MS = 20_000L
    }
}
