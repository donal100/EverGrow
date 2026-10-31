package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.niki914.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 双人模式的切片引擎：**一次请求送两路音频**。
 *
 * 为什么要这样做：实测 qwen3.5-omni-plus 一次能收两段音频，并且能分别标注
 * A / B（两个文件各自听完再分别翻译）。于是不需要「仲裁选一个麦」，
 * 也不需要两条会话 —— 两个人同时说话也能各自出字幕。
 *
 * 每次请求带上一小段滚动上下文（最近 6 行），让模型知道「它」「那个」指什么；
 * 不带全量转写，那既贵又没用。
 *
 * 输出：每路一行，格式 `A: <英文>` / `B: <英文>`；模型判定为噪声或无关的
 * 会返回 `A: (skip)`，我们就不上屏。
 */
class ChunkDuoEngine(
    private val ctx: Context,
    private val instructions: String,
    private val onLine: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "niki914_zafiro_ChunkDuo"
        const val RATE_OUT = 16_000
        const val CHUNK_MS = 3_000
        const val MIN_RMS = 700.0
        const val CONTEXT_LINES = 6
        const val ENDPOINT = "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "duo-asr") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    /** 滚动上下文：最近几句已上屏的字幕。 */
    private val context = ArrayDeque<String>()

    fun start() {
        if (running) return
        running = true
        onState("connecting")
        thread = Thread { loop() }.apply { isDaemon = true; name = "duo-mic"; start() }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        onState("stopped")
    }

    private fun loop() {
        val usb = AudioRouter.usbMicWait(ctx)
        if (usb == null) { Logger.w(TAG, "no usb mic"); onState("no usb mic"); return }
        val rate = 48_000
        val mask = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(TAG, "mic create failed"); onState("mic failed"); return
        }
        runCatching { rec.setPreferredDevice(usb) }
        record = rec
        runCatching { rec.startRecording() }
        onState("ready")

        val target = rate / 1000 * CHUNK_MS      // frames per chunk
        val left = ShortArray(target)
        val right = ShortArray(target)
        var fill = 0
        val buf = ShortArray(9600)               // 100 ms stereo

        while (running) {
            val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) continue
            val frames = n / 2
            for (f in 0 until frames) {
                if (fill < target) {
                    left[fill] = buf[2 * f]
                    right[fill] = buf[2 * f + 1]
                    fill++
                }
            }
            if (fill >= target) {
                val l = left.copyOf(target)
                val r = right.copyOf(target)
                fill = 0
                io.execute { ask(l, r) }
            }
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    private fun ask(left48: ShortArray, right48: ShortArray) {
        val l = decimate(left48, 3)
        val r = decimate(right48, 3)
        if (rms(l) < MIN_RMS && rms(r) < MIN_RMS) {
            Logger.i(TAG, "chunk skipped (both quiet)")
            return
        }
        val key = runCatching { File(ctx.filesDir, "qwen_asr_key.txt").readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) { Logger.w(TAG, "no key"); return }
        val ctxText = if (context.isEmpty()) "(none yet)" else context.joinToString("\n")
        val prompt = """
            You are a two-person interpreter. Two audio streams follow: A is the person wearing
            transmitter A, B is the person wearing transmitter B. Both may speak at once.
            Recent conversation:
            $ctxText

            $instructions

            Reply with exactly two lines:
            A: <English translation, or (skip) if this stream has no speech, is noise, or is not worth relaying>
            B: <same rule>
            No other text.
        """.trimIndent()
        val payload = JSONObject()
            .put("model", "qwen3.5-omni-plus")
            .put("modalities", JSONArray().put("text"))
            .put("max_tokens", 300)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                JSONArray()
                    .put(audioPart(l))
                    .put(audioPart(r))
                    .put(JSONObject().put("type", "text").put("text", prompt)),
            )))
        val t0 = System.currentTimeMillis()
        val request = Request.Builder().url(ENDPOINT)
            .addHeader("Authorization", "Bearer " + key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = runCatching {
            http.newCall(request).execute().use { resp ->
                val b = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Logger.w(TAG, "http " + resp.code + " " + b.take(120)); null }
                else JSONObject(b).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim()
            }
        }.getOrNull() ?: return
        Logger.i(TAG, "duo took=" + (System.currentTimeMillis() - t0) + "ms " + body.replace("\n", " ").take(100))
        for (line in body.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val isA = t.startsWith("A:", true)
            val isB = t.startsWith("B:", true)
            if (!isA && !isB) continue
            val text = t.substring(2).trim()
            if (text.isEmpty() || text.startsWith("(skip", true)) continue
            val who = if (isA) "A" else "B"
            context.addLast("$who: $text")
            while (context.size > CONTEXT_LINES) context.removeFirst()
            onLine("$who：$text")
        }
    }

    private fun audioPart(pcm: ShortArray): JSONObject {
        val b64 = android.util.Base64.encodeToString(toWav(pcm), android.util.Base64.NO_WRAP)
        return JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", "data:audio/wav;base64," + b64).put("format", "wav"))
    }

    private fun decimate(src: ShortArray, factor: Int): ShortArray {
        val out = ShortArray(src.size / factor)
        for (i in out.indices) out[i] = src[i * factor]
        return out
    }

    private fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (v in pcm) { val x = v.toDouble(); sum += x * x }
        return Math.sqrt(sum / maxOf(1, pcm.size))
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(RATE_OUT); le32(RATE_OUT * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataLen)
        for (v in pcm) { out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF) }
        return out.toByteArray()
    }
}
