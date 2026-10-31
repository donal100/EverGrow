package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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
 * 双人模式的切片引擎：**一次请求送两路音频**，模型分别标注 A / B。
 *
 * 实测 qwen3.5-omni-plus 能同时收两段音频并分别给结果，所以不需要
 * 「仲裁选一个麦」，两个人同时说话也能各自出字幕。
 *
 * 每段按静音切句（两侧都安静 1.2 秒算说完，最长 6 秒），带最近 6 行上下文；
 * 相同的一句连着出现会跳过（切片边界常常覆盖同一句）。
 *
 * 说话：每行译文用 qwen3-tts-flash 合成（英文 Serena / 粤语 Rocky），
 * 播到「对面那只耳朵」——A 的话给 B（右声道），B 的话给 A（左声道）。
 */
class ChunkDuoEngine(
    private val ctx: Context,
    private val instructions: String,
    /** 说中文时用哪个音色：普通话用 Serena，粤语要 Rocky（实时音色表里没有粤语音色）。 */
    private val chineseVoice: String = "Serena",
    private val onLine: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "niki914_zafiro_ChunkDuo"
        const val RATE_OUT = 16_000
        const val MIN_CHUNK_MS = 1_200
        const val MAX_CHUNK_MS = 6_000
        const val SILENCE_MS = 1_200
        const val MIN_RMS = 700.0
        const val CONTEXT_LINES = 6
        const val ENDPOINT = "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
        const val TTS_ENDPOINT = "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        const val BLEED = 0.12f
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "duo-asr") }
    private val speakIo = Executors.newSingleThreadExecutor { r -> Thread(r, "duo-tts") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 每个说话人上一次上屏的文字：相邻两片常常重复同一句。 */
    private val lastLine = HashMap<String, String>()
    /** 滚动上下文：最近几句已上屏的字幕。 */
    private val context = ArrayDeque<String>()
    private var player: MediaPlayer? = null

    private val speechOn = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("translate_voice=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    fun start() {
        if (running) return
        running = true
        onState("connecting")
        Logger.i(TAG, "speech=" + speechOn)
        thread = Thread { loop() }.apply { isDaemon = true; name = "duo-mic"; start() }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { player?.release() }
        player = null
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

        val maxFrames = rate / 1000 * MAX_CHUNK_MS
        val minFrames = rate / 1000 * MIN_CHUNK_MS
        val silenceNeeded = rate / 1000 * SILENCE_MS
        val left = ShortArray(maxFrames)
        val right = ShortArray(maxFrames)
        var fill = 0
        var silent = 0
        val buf = ShortArray(9600)

        while (running) {
            val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (n <= 0) continue
            val frames = n / 2
            var peak = 0
            for (f in 0 until frames) {
                val lv = kotlin.math.abs(buf[2 * f].toInt())
                val rv = kotlin.math.abs(buf[2 * f + 1].toInt())
                if (lv > peak) peak = lv
                if (rv > peak) peak = rv
                if (fill < maxFrames) {
                    left[fill] = buf[2 * f]
                    right[fill] = buf[2 * f + 1]
                    fill++
                }
            }
            if (peak < 900) silent += frames else silent = 0
            val done = (fill >= minFrames && silent >= silenceNeeded) || fill >= maxFrames
            if (done) {
                val len = fill
                val l = left.copyOf(len)
                val r = right.copyOf(len)
                fill = 0
                silent = 0
                Logger.i(TAG, "chunk " + (len * 1000 / rate) + "ms")
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
            You are a two-person interpreter. Two audio streams follow: A is the person
            wearing transmitter A, B is the person wearing transmitter B. Both may speak at once.
            Recent conversation:
            $ctxText

            $instructions

            Reply with exactly two lines:
            A: <translation, or (skip) if this stream has no speech, is noise, or is not worth relaying>
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
            if (text == lastLine[who]) {
                Logger.i(TAG, "duo skip repeat " + who + ": " + text.take(40))
                continue
            }
            lastLine[who] = text
            context.addLast("$who: $text")
            while (context.size > CONTEXT_LINES) context.removeFirst()
            onLine("$who：$text")
            // A 的话进 B 的耳朵（右），B 的话进 A 的耳朵（左）。
            if (speechOn) {
                val toRight = who == "A"
                speakIo.execute { runCatching { say(text, toRight) } }
            }
        }
    }

    /** 合成一句并播到指定的一侧。中文用 Rocky，其余用 Serena。 */
    private fun say(text: String, toRight: Boolean) {
        val hasHan = text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
        val voice = if (hasHan) chineseVoice else "Serena"
        val lang = if (hasHan) "chinese" else "English"
        val key = runCatching { File(ctx.filesDir, "qwen_asr_key.txt").readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) return
        val payload = JSONObject()
            .put("model", "qwen3-tts-flash")
            .put("input", JSONObject().put("text", text).put("voice", voice).put("language_type", lang))
        val body = runCatching {
            http.newCall(
                Request.Builder().url(TTS_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) { Logger.w(TAG, "tts http " + resp.code); null }
                else JSONObject(resp.body?.string().orEmpty())
                    .getJSONObject("output").getJSONObject("audio").optString("url").ifEmpty { null }
            }
        }.getOrNull() ?: return
        val audio = runCatching { http.newCall(Request.Builder().url(body).build()).execute().use { it.body?.bytes() } }.getOrNull() ?: return
        val f = File(ctx.cacheDir, "duo_tts_" + System.currentTimeMillis() + ".mp3")
        runCatching { f.writeBytes(audio) }.getOrElse { return }
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(f.absolutePath)
                prepare()
                // 分声道：A 的话只进右耳，B 的话只进左耳，另一侧留一点底噪。
                if (toRight) setVolume(BLEED, 1f) else setVolume(1f, BLEED)
            }
        }.getOrNull() ?: return
        runCatching { player?.release() }
        player = mp
        runCatching { mp.start() }
        Logger.i(TAG, "say " + (if (toRight) "right" else "left") + " voice=" + voice + " " + text.take(30))
        runCatching { f.delete() }
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
