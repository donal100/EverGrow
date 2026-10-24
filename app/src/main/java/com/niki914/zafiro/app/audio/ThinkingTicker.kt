package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 听写模式的「收到，正在做」提示音。
 *
 * 拿到问题先「嗒嗒」两声 —— 这是回执，告诉你话已经收到了、开始处理；
 * 之后每隔一秒轻轻「嗒」一下，表示还在干活；一开口说话就停。
 *
 * 为什么短促低音量：它是背景回执，不是通知。太长会被当成播报内容，或者被识别器当成说话。
 *
 * 开关：voice_tuning.txt 里 thinking_tick=0 关掉；thinking_tick_ms 调间隔（默认 1200）。
 * 40 秒上限自动停，异常情况下不会一直响。
 */
class ThinkingTicker(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 12) }.getOrNull() }
    @Volatile private var running = false
    @Volatile private var startedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() - startedAt > MAX_MS) { stop(); return }
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 60) }
            main.postDelayed(this, intervalMs())
        }
    }

    /** 收到问题：先两声回执，然后进入「还在做」的节奏。 */
    fun acknowledge() {
        if (!enabled()) return
        startedAt = System.currentTimeMillis()
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 70) }
        main.postDelayed({ runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 70) } }, 140L)
        if (!running) {
            running = true
            main.postDelayed(tick, 900L)
            log("tick start (every " + intervalMs() + "ms)")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        runCatching { tone?.stopTone() }
        log("tick stop")
    }

    fun release() {
        stop()
        runCatching { tone?.release() }
    }

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

    private companion object { const val MAX_MS = 40_000L }
}
