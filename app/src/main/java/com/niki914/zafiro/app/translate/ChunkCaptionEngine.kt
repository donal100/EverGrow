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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 单向下字幕的简化引擎：**固定 3 秒切片**，每片直接问 qwen3.5-omni-plus，
 * 拿到文字就上字幕。没有 VAD、没有 WebSocket、没有 commit/response.create。
 *
 * 为什么换掉实时会话：那一套要同时照顾服务端 VAD、手动提交、半双工静音、
 * 双麦仲裁，任何一处错都会表现成「听不见」或「乱编字幕」，而实测同一段
 * 远场录音 omni 的转录比实时会话更完整。少一层协议，少一类 bug。
 *
 * 代价：每条字幕落后约 2–3 秒（HTTP 往返 + 生成），单向字幕可以接受。
 */
class ChunkCaptionEngine(
    private val ctx: Context,
    private val instructions: String,
    private val onTranscript: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "niki914_zafiro_ChunkCaption"
        const val RATE_OUT = 16_000
        const val CHUNK_MS = 3_000
        /** 低于这个响度的一整片直接丢掉：房间底噪不值得上传。 */
        const val MIN_RMS = 700.0
        const val ENDPOINT = "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "chunk-asr") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (running) return
        running = true
        onState("connecting")
        thread = Thread { loop() }.apply { isDaemon = true; name = "chunk-mic"; start() }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        onState("stopped")
        Logger.i(TAG, "stopped")
    }

    private fun loop() {
        if (!running) return
        val usb = AudioRouter.usbMicWait(ctx)
        val stereo = usb != null
        val rate = if (stereo) 48_000 else RATE_OUT
        val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val nch = if (stereo) 2 else 1
        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(TAG, "mic create failed")
            onState("mic failed")
            return
        }
        usb?.let { runCatching { rec.setPreferredDevice(it) } }
        record = rec
        runCatching { rec.startRecording() }
        runCatching {
            val rd = rec.routedDevice
            Logger.i(TAG, "mic routed type=" + (rd?.type ?: -1) + " name=" + (rd?.productName ?: "?"))
        }
        onState("ready")

        val target = RATE_OUT * CHUNK_MS / 1000
        val acc = ShortArray(target)
        var fill = 0
        val buf = ShortArray(if (stereo) 9600 else 1600)   // 100ms

        while (running) {
            val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) continue
            if (stereo) {
                val frames = n / 2
                var p0 = 0
                var p1 = 0
                for (f in 0 until frames) {
                    val l = kotlin.math.abs(buf[2 * f].toInt())
                    val r = kotlin.math.abs(buf[2 * f + 1].toInt())
                    if (l > p0) p0 = l
                    if (r > p1) p1 = r
                }
                // 谁响用谁：两路都在说同一个人时，靠近的那路自然更响。
                val ch = if (p1 > p0 * 1.4) 1 else 0
                var k = 0
                while (k * 3 < frames && fill < target) {
                    acc[fill++] = buf[2 * (k * 3) + ch]
                    k++
                }
            } else {
                for (i in 0 until n) {
                    if (fill < target) acc[fill++] = buf[i]
                }
            }
            if (fill >= target) {
                val chunk = acc.copyOf(target)
                fill = 0
                io.execute { transcribe(chunk) }
            }
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
        Logger.i(TAG, "mic closed")
    }

    private fun transcribe(pcm: ShortArray) {
        var sum = 0L
        var peak = 0
        for (v in pcm) {
            val x = v.toInt()
            sum += x.toLong() * x
            if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
        }
        val rms = Math.sqrt(sum.toDouble() / pcm.size)
        if (rms < MIN_RMS) {
            Logger.i(TAG, "chunk skipped rms=" + rms.toInt())
            return
        }
        val wav = toWav(pcm)
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val key = runCatching { File(ctx.filesDir, "qwen_asr_key.txt").readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) { Logger.w(TAG, "no key"); return }
        val payload = JSONObject()
            .put("model", "qwen3.5-omni-plus")
            .put("modalities", JSONArray().put("text"))
            .put("max_tokens", 300)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                                .put("data", "data:audio/wav;base64," + b64).put("format", "wav")))
                            .put(JSONObject().put("type", "text").put("text", instructions)),
                    ),
                ),
            )
        val t0 = System.currentTimeMillis()
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer " + key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = runCatching {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "http " + resp.code + " " + body.take(120))
                    null
                } else {
                    JSONObject(body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                }
            }
        }.getOrNull()
        Logger.i(TAG, "chunk rms=" + rms.toInt() + " took=" + (System.currentTimeMillis() - t0) + "ms text=" + (text ?: "").take(60))
        if (!text.isNullOrBlank()) onTranscript(text)
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataLen)
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(RATE_OUT); le32(RATE_OUT * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataLen)
        for (v in pcm) { out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF) }
        return out.toByteArray()
    }
}
