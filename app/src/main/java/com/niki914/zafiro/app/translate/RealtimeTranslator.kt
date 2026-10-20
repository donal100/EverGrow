package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.niki914.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 实时口译：一条 WebSocket 搞定「听中文、说英文」。
 *
 * 实测（2026-10，同一段中文音频）：
 *   - 第一段译文音频 1.14s 后到，整条回复 1.59s 完成（其中含 1s 我补的静音）
 *   - 对比三段式（ASR→翻译→合成）3.9s：快四倍，而且是流式的。
 *
 * 协议是 OpenAI realtime 风格：
 *   上行 session.update（instructions 直接写「翻译成英文」）→ input_audio_buffer.append（base64 PCM16）
 *   下行 response.audio.delta（24kHz PCM）与 response.audio_transcript.delta
 * 断句由服务端 VAD 负责（800ms 静音），所以这里不需要自己做端点检测。
 */
class RealtimeTranslator(
    private val ctx: Context,
    private val instructions: String,
    /** true：只要文字，不要语音。单人模式看字幕用 —— 少一跳合成，也没有回声问题。 */
    private val textOnly: Boolean = false,
    /** 音色：粤语必须用专门的粤语音色，普通话音色念粤字就是「硬念」。 */
    private val voice: String = "Serena",
    /** 外放时为 true：模型说话期间不再上传麦克风，否则它会听见自己并自问自答。 */
    private val halfDuplex: Boolean,
    private val onTranscript: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    /** 服务器正在返回译文：外放模式下这期间不送麦克风音频。 */
    @Volatile private var speaking = false
    /** 本地播报期间把麦克风掐掉，别把自己念的声音再送上去（粤语走本地合成时会用到）。 */
    @Volatile var muted: Boolean = false
    /** 服务端说完一句了，但 AudioTrack 里可能还在排队 —— 播放完之前不能重新开麦。 */
    @Volatile private var responseDone = false
    private var audioBytes = 0L
    private val audioWarned = java.util.concurrent.atomic.AtomicBoolean(false)
    private var micBytes = 0L
    private var micLogAt = 0L
    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    private var track: AudioTrack? = null
    private val playback = LinkedBlockingQueue<ByteArray>()
    private var playThread: Thread? = null

    private val transcript = StringBuilder()

    fun start() {
        if (running) return
        running = true
        // 顺序很重要：必须先建连接、发 session.update、等 session.updated，
        // 才能开始送麦克风音频。否则服务器已经「开始处理音频」，再改配置会被拒绝：
        // "Cannot update 'input_audio_format' after session has started processing audio."
        if (!textOnly) startPlayback()
        openSocket()
    }

    private fun openSocket() {
        val key = runCatching {
            File(ctx.filesDir, "qwen_asr_key.txt").readText().trim()
        }.getOrNull().orEmpty()
        if (key.isEmpty()) { onState("no qwen key"); running = false; return }
        val req = Request.Builder()
            .url("wss://maas.qwencloudapi.com/api-ws/v1/realtime?model=qwen-audio-3.1-realtime-plus")
            .addHeader("Authorization", "Bearer " + key)
            .build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val session = JSONObject()
                    .put("modalities", if (textOnly) JSONArray().put("text")
                    else JSONArray().put("text").put("audio"))
                    .put("instructions", instructions)
                    .put("input_audio_format", "pcm16")
                if (!textOnly) {
                    session.put("voice", voice).put("output_audio_format", "pcm16")
                }
                webSocket.send(JSONObject().put("type", "session.update").put("session", session).toString())
                onState("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleEvent(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState("socket failed: " + (t.message ?: t.javaClass.simpleName))
                running = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState("closed " + code)
                running = false
            }
        })
    }

    private fun handleEvent(text: String) {
        val ev = JSONObject(text)
        when (ev.optString("type")) {
            "session.updated" -> {
                onState("ready")
                startMic()
            }
            "response.created" -> { speaking = true; responseDone = false }
            "response.audio.delta" -> {
                val b64 = ev.optString("delta")
                if (b64.isNotEmpty()) {
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    audioBytes += pcm.size
                    playback.put(pcm)
                }
            }
            "response.audio_transcript.delta", "response.text.delta", "response.output_text.delta" -> {
                transcript.append(ev.optString("delta"))
            }
            "response.audio_transcript.done", "response.text.done", "response.done" -> {
                // 这里不再直接把 speaking 置回 false：服务端的 done 早于本地播放结束，
                // 立刻开麦就会把自己的译文再听一遍（实测同一句话被译了三遍）。
                responseDone = true
                if (transcript.isNotEmpty()) {
                    onTranscript(transcript.toString())
                    transcript.setLength(0)
                }
            }
            "error" -> onState("error: " + ev.optJSONObject("error")?.optString("message"))
            // 未知事件一律记一行：协议字段名一旦变了（text 模态的事件名尤其容易变），
            // 没有这行就只能靠猜。
            else -> Logger.d(LOG_TAG, "event " + ev.optString("type"))
        }
    }

    /** 麦克风：手机自带麦克风，16kHz 单声道，100ms 一片直接推上去。 */
    @Volatile private var micStarted = false

    private fun startMic() {
        if (micStarted) return
        micStarted = true
        val minBuf = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32_000))
        }.getOrNull()
        if (rec == null) { Logger.w(LOG_TAG, "mic create failed"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(LOG_TAG, "mic not initialized state=" + rec.state); rec.release(); return
        }
        Logger.i(LOG_TAG, "mic recording source=VOICE_COMMUNICATION")
        runCatching { AudioRouter.builtInMic(ctx)?.let { rec.setPreferredDevice(it) } }
        record = rec
        runCatching { rec.startRecording() }
        micThread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                if (muted) continue
                if (halfDuplex && !textOnly && speaking) continue
                val bytes = ByteArray(n * 2)
                var p = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[p++] = (v and 0xFF).toByte()
                    bytes[p++] = ((v shr 8) and 0xFF).toByte()
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val sent = socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64).toString()) == true
                if (sent) micBytes += n * 2L
                val now = System.currentTimeMillis()
                if (now - micLogAt > 5000) {
                    micLogAt = now
                    Logger.i(LOG_TAG, "mic ${micBytes / 2} samples sent, audio ${audioBytes / 2} samples out, speaking=$speaking")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** 播放：24kHz PCM 流式写进 AudioTrack，优先走耳机。 */
    private fun startPlayback() {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(24_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder().setAudioFormat(fmt).setAudioAttributes(attrs)
                .setBufferSizeInBytes(maxOf(minBuf, 24_000)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrNull() ?: return
        runCatching { AudioRouter.earphones(ctx)?.let { t.setPreferredDevice(it) } }
        val played = runCatching { t.play() }.isSuccess
        Logger.i(LOG_TAG, "audiotrack playing=$played state=" + t.playState + " buf=" + maxOf(minBuf, 24_000))
        track = t
        playThread = Thread {
            while (running) {
                val data = playback.poll(200, TimeUnit.MILLISECONDS)
                if (data == null) {
                    // 队列空了：如果这一句已经说完，才把麦克风放回来。
                    if (responseDone) { responseDone = false; speaking = false }
                    continue
                }
                val wrote = runCatching { t.write(data, 0, data.size) }.getOrDefault(-1)
                if (wrote < 0 && audioWarned.compareAndSet(false, true)) {
                    Logger.w(LOG_TAG, "audiotrack write failed code=" + wrote)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.send(JSONObject().put("type", "session.close").toString()) }
        runCatching { socket?.close(1000, "bye") }
        socket = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        playback.clear()
        micThread = null
        playThread = null
    }

    private companion object {
        const val VOICE = "longanqian_v3.1"
        const val LOG_TAG = "niki914_zafiro_RealtimeTranslate"
    }
}
