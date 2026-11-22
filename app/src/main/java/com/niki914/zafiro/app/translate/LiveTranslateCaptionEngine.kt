package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.niki914.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「什么都翻译成英文」这条单路字幕模式，直连**专用实时翻译模型**
 * （`qwen3.8-livetranslate-flash-realtime`）。
 *
 * 为什么不复用 RealtimeTranslator：那条路是「通用 omni + 本地 TTS + 我们自己的
 * 断句计时器」。实测（2026-11，同一段英文）：
 *   - 专用模型：说完 180ms 后译文文字就到，而且**说话中途**就已经开始出结果
 *     （speech_started 0.71s → response.created 1.17s，人还在说）
 *   - 我们原来的路：4 秒定时器提交一次，最坏情况字幕晚 4 秒
 * 所以这条路上不再有：TTS 往返、等待输入转写、静音计时器、半双工。
 *
 * 配置跟提示词无关，是**参数**：目标语言 + 热词表。串音/串语言这类事在单路
 * 字幕模式下本来就不存在（只有一支麦克风、一个方向）。
 */
class LiveTranslateCaptionEngine(
    private val ctx: Context,
    /** 目标语言：en / zh。 */
    private val targetLang: String,
    /** 「听见的是什么」——只写日志，不上屏（字幕必须是译文本身）。 */
    private val onHeard: (String) -> Unit = {},
    /** 正在译的这一句（还没收尾）——先上屏，让人立刻看到有字。 */
    private val onPartial: (String) -> Unit = {},
    private val onCaption: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "niki914_zafiro_LiveTranslate"
        const val RATE = 16_000
        const val URL = "wss://maas.qwencloudapi.com/api-ws/v1/realtime" +
            "?model=qwen3.8-livetranslate-flash-realtime"
        /** 断句：默认 1000ms 太慢，这里 300ms —— 用户明确接受「碎一点、但要快」。 */
        const val SILENCE_MS = 300
        /**
         * 一句话的多段译文，攒这么久没有新字就**提交**（写进历史行）。
         * 600 → 200ms：实测旧值时每次字幕都白等 665ms（见 caption(Nms) 日志）。
         * 只影响「提交」，不影响上屏 —— 上屏由 partial 直接推。
         */
        const val FLUSH_MS = 200L
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    private var gen = 0

    private val buf = StringBuilder()
    @Volatile private var lastDeltaAt = 0L
    private var lastCaption = ""
    private var flushThread: Thread? = null

    /** voice_tuning.txt 里的整数旋钮；读不到就用默认值。 */
    private fun intCfg(key: String, def: Int, lo: Int, hi: Int): Int = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith(key + "=") }
            ?.substringAfter("=")?.trim()?.toIntOrNull()?.coerceIn(lo, hi) ?: def
    }.getOrDefault(def)

    /** 没有新字多久算这句说完（提交）。 */
    private val flushMs by lazy { intCfg("translate_flush_ms", FLUSH_MS.toInt(), 80, 2000) }
    /** flusher 自己的轮询间隔，越短提交越准时。 */
    private val flushPollMs by lazy { intCfg("translate_flush_poll_ms", 50, 20, 300) }
    /** 半句上屏的最小间隔：眼镜光学件刷新太快会抖，10Hz 是实测能接受的。 */
    private val partialMs by lazy { intCfg("translate_partial_ms", 100, 50, 1000) }
    /** 每次送给模型的音频帧长（毫秒），越小越跟手。 */
    private val frameMs by lazy { intCfg("translate_frame_ms", 20, 10, 100) }

    /** 这一句第一段译文到手的时刻，用来自报「模型出字 → 上屏」的真实延迟。 */
    @Volatile private var firstDeltaAt = 0L
    @Volatile private var lastPartialAt = 0L
    private var lastPartialSent = ""

    private val gain: Double by lazy {
        runCatching {
            File(ctx.filesDir, "voice_tuning.txt").readLines()
                .firstOrNull { it.trim().startsWith("translate_gain=") }
                ?.substringAfter("=")?.trim()?.toDoubleOrNull()
        }.getOrNull() ?: 1.5
    }

    fun start() {
        if (running) return
        running = true
        onState("connecting")
        prefetchDns()
        open()
        startFlusher()
    }

    fun stop() {
        running = false
        ready = false
        runCatching { socket?.send(JSONObject().put("type", "session.close").toString()) }
        runCatching { socket?.close(1000, "bye") }
        socket = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        micThread = null
        onState("stopped")
    }

    /**
     * 先把域名解析掉：实测冷启动 DNS 278ms、TLS 259ms，全都落在「第一条字幕之前」。
     * 解析结果进系统缓存，后面的 WebSocket 握手就不用再等 DNS。
     */
    private fun prefetchDns() {
        Thread {
            runCatching { java.net.InetAddress.getAllByName("maas.qwencloudapi.com") }
                .onFailure { Logger.w(TAG, "dns prefetch failed") }
        }.apply { isDaemon = true; name = "caption-dns"; start() }
    }

    private fun open() {
        val key = runCatching {
            File(ctx.filesDir, "qwen_asr_key.txt").readText().trim()
        }.getOrNull().orEmpty()
        if (key.isEmpty()) { onState("no qwen key"); running = false; return }
        val myGen = ++gen
        socket = http.newWebSocket(
            Request.Builder().url(URL).addHeader("Authorization", "Bearer " + key).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val session = JSONObject()
                        .put("output_modalities", org.json.JSONArray().put("text"))
                        .put(
                            "audio",
                            JSONObject().put(
                                "input",
                                JSONObject()
                                    .put("format", JSONObject().put("type", "pcm").put("sample_rate", RATE))
                                    .put(
                                        "turn_detection",
                                        JSONObject()
                                            .put("type", "server_vad")
                                            .put("threshold", 0.4)
                                            .put("prefix_padding_ms", 200)
                                            .put("silence_duration_ms", SILENCE_MS),
                                    ),
                            ),
                        )
                        .put("translation", JSONObject().put("language", targetLang))
                    webSocket.send(
                        JSONObject().put("type", "session.update").put("session", session).toString(),
                    )
                    onState("connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (myGen != gen) return
                    runCatching { handle(text) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGen != gen) return
                    Logger.w(TAG, "socket failed " + (t.message ?: t.javaClass.simpleName))
                    onState("socket failed")
                    reconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGen != gen) return
                    Logger.i(TAG, "closed " + code)
                    if (running) reconnect()
                }
            },
        )
    }

    /** 断线就重连：会话状态会丢（模型听不到重连之前的音频），但至少不会一直哑着。 */
    private fun reconnect() {
        if (!running) return
        ready = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        // 800 → 200ms：断线时这段是纯静音，重连本身已经要一次握手。
        runCatching { Thread.sleep(200) }
        if (running) { Logger.i(TAG, "reconnecting"); open() }
    }

    private fun handle(text: String) {
        val ev = JSONObject(text)
        when (ev.optString("type")) {
            "session.updated" -> {
                ready = true
                onState("ready")
                startMic()
            }
            "response.text.delta", "response.output_text.delta" -> {
                buf.append(ev.optString("delta"))
                lastDeltaAt = System.currentTimeMillis()
                if (firstDeltaAt == 0L) firstDeltaAt = lastDeltaAt
                maybeEmitPartial()
            }
            "response.text.done" -> {
                // done 只是「这一段结束」，一句人话可能分好几段；交给 flusher 去合并。
                lastDeltaAt = System.currentTimeMillis()
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val heard = ev.optString("transcript").trim()
                if (heard.isNotEmpty()) runCatching { onHeard(heard) }
            }
            "error" -> {
                val msg = ev.optJSONObject("error")?.optString("message").orEmpty()
                Logger.w(TAG, "error: " + msg)
                onState("error")
            }
            else -> Logger.d(TAG, "event " + ev.optString("type"))
        }
    }

    /** 攒够一小段就上屏：600ms 没有新字就认为这段说完了。 */
    private fun startFlusher() {
        if (flushThread != null) return
        flushThread = Thread {
            while (running) {
                try { Thread.sleep(flushPollMs.toLong()) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                if (buf.isEmpty() || now - lastDeltaAt < flushMs) continue
                val text = buf.toString().trim()
                buf.setLength(0)
                firstDeltaAt = 0L
                lastPartialSent = ""
                if (text.isEmpty()) continue
                val line = trimOverlap(lastCaption, text)
                if (line.isBlank()) continue
                lastCaption = line
                Logger.i(TAG, "caption(" + (System.currentTimeMillis() - lastDeltaAt) + "ms) " + line.take(70))
                runCatching { onCaption(line) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * 半句先上屏：模型在**人还在说话时**就吐译文（实测 speech_started 后 0.47s 开始出字），
     * 旧的实现把这段字攥在 buf 里等静默，等于白扔半秒。
     * 这里按 partialMs 节流推上去；提交时仍以 trimOverlap 之后的结果为准。
     */
    private fun maybeEmitPartial() {
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < partialMs) return
        val raw = buf.toString().trim()
        if (raw.isEmpty()) return
        val line = trimOverlap(lastCaption, raw)
        if (line.isBlank() || line == lastPartialSent) return
        lastPartialAt = now
        lastPartialSent = line
        Logger.i(TAG, "partial(+" + (now - firstDeltaAt) + "ms) " + line.take(60))
        runCatching { onPartial(line) }
    }

    /** 上一段结尾和这一段开头重叠时切掉重复部分（实时译文常把半句重说一遍）。 */
    private fun trimOverlap(prev: String, next: String): String {
        val pw = prev.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nw = next.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (pw.isEmpty() || nw.isEmpty()) return next
        var best = 0
        for (k in minOf(8, pw.size, nw.size) downTo 1) {
            val tail = pw.subList(pw.size - k, pw.size).joinToString(" ").lowercase()
            val head = nw.subList(0, k).joinToString(" ").lowercase()
            if (tail == head) { best = k; break }
        }
        return if (best == 0) next else nw.subList(best, nw.size).joinToString(" ")
    }

    private fun startMic() {
        if (micThread != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, RATE * 2),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(TAG, "mic create failed")
            onState("mic failed")
            return
        }
        // 眼镜的麦克风不要：AudioRouter 已经按名字排除了。
        runCatching { AudioRouter.preferredMic(ctx)?.let { rec.setPreferredDevice(it) } }
        record = rec
        runCatching { rec.startRecording() }
        Logger.i(TAG, "mic " + (rec.routedDevice?.productName ?: "?"))
        micThread = Thread {
            // 100ms 一帧 → 默认 20ms：送得越碎，服务端 VAD 和模型看到的越及时。
            val frame = (RATE * frameMs / 1000).coerceAtLeast(160)
            val pcm = ShortArray(frame)
            val bytes = ByteArray(frame * 2)
            while (running && ready && record === rec) {
                val n = runCatching { rec.read(pcm, 0, pcm.size) }.getOrDefault(-1)
                if (n <= 0) continue
                var p = 0
                for (i in 0 until n) {
                    val v = (pcm[i] * gain).toInt().coerceIn(-32768, 32767)
                    bytes[p++] = (v and 0xFF).toByte()
                    bytes[p++] = ((v shr 8) and 0xFF).toByte()
                }
                val payload = Base64.encodeToString(bytes.copyOf(p), Base64.NO_WRAP)
                socket?.send(
                    JSONObject().put("type", "input_audio_buffer.append").put("audio", payload).toString(),
                )
            }
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }.also { it.isDaemon = true; it.start() }
    }
}
