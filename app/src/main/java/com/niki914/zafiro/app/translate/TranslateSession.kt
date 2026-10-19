package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时翻译的收音端。
 *
 * 与听写模式的差别：
 *  - 用 VOICE_COMMUNICATION 音源，并挂上系统回声消除（AEC）。外放时喇叭的声音
 *    会被抵消掉一大半，于是「说话的同时还能听」才有可能。
 *  - 不做整段环形缓冲，只保留「这一句」：VAD 判到开口开始收集，静音 700ms 收尾，
 *    立刻写成 wav 交给上层去识别+翻译。
 */
class TranslateSession(
    private val ctx: Context,
    private val onUtterance: (File) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null

    private val sampleRate = 16_000
    private val buf = ShortArray(1600)            // 100ms
    private val utterance = ArrayList<Short>(sampleRate * 30)
    private var speaking = false
    private var silenceFrames = 0
    private var aboveFrames = 0
    private var noiseFloor = 300.0

    fun start() {
        if (running) return
        running = true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION：外放+通话那种路径，系统 AEC 才对它生效
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2),
            )
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return
        }
        record = rec
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            }
        }
        runCatching { rec.startRecording() }
        thread = Thread {
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                if (paused) continue
                handle(buf, n)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handle(buf: ShortArray, n: Int) {
        var peak = 0
        for (i in 0 until n) {
            val v = kotlin.math.abs(buf[i].toInt())
            if (v > peak) peak = v
        }
        if (!speaking) noiseFloor = noiseFloor * 0.98 + peak * 0.02
        val on = maxOf(700.0, noiseFloor * 2.0)
        if (peak > on) {
            aboveFrames++
            silenceFrames = 0
        } else if (peak < on * 0.6) {
            silenceFrames++
            aboveFrames = 0
        }

        if (!speaking && aboveFrames >= 1) {
            speaking = true
            utterance.clear()
        }
        if (speaking) {
            for (i in 0 until n) utterance.add(buf[i])
            // 700ms 静音算说完；至少 0.4 秒才当成一句话，避免咳嗽、关门
            if (silenceFrames >= 7) {
                speaking = false
                if (utterance.size > sampleRate * 4 / 10) flush()
                utterance.clear()
                silenceFrames = 0
            }
        }
    }

    private fun flush() {
        val samples = utterance.toShortArray()
        val f = File(ctx.cacheDir, "tr_" + System.currentTimeMillis() + ".wav")
        runCatching {
            f.writeBytes(wav(samples))
            onUtterance(f)
        }
    }

    /** 播放译文时先把收音停掉：半双工最保险，AEC 只是第二道防线。 */
    fun pause() { paused = true }
    fun resume() { paused = false; utterance.clear(); speaking = false; silenceFrames = 0 }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { aec?.release() }
        record = null
        aec = null
        thread = null
        val busy = AtomicBoolean(true); busy.set(false)
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val bytes = ByteArray(pcm.size * 2 + 44)
        fun put32(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
            bytes[off + 2] = ((v shr 16) and 0xFF).toByte()
            bytes[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put16(off: Int, v: Int) {
            bytes[off] = (v and 0xFF).toByte()
            bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val dataLen = pcm.size * 2
        "RIFF".toByteArray().copyInto(bytes, 0)
        put32(4, dataLen + 36)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
        put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, sampleRate); put32(28, sampleRate * 2)
        put16(32, 2); put16(34, 16)
        "data".toByteArray().copyInto(bytes, 36)
        put32(40, dataLen)
        var p = 44
        for (s in pcm) {
            bytes[p] = (s.toInt() and 0xFF).toByte()
            bytes[p + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            p += 2
        }
        return bytes
    }
}
