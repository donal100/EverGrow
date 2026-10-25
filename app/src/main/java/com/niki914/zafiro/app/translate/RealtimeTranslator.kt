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
    /**
     * 立体声分声道：插着耳机时，英文译文只走左耳、中文译文只走右耳，
     * 另一边留一成二的音量做「伴奏」。两个人各戴一只耳塞就能各听各的语言。
     * 关掉就是原来的单声道，两个耳朵一样响。
     */
    private val panSplit: Boolean = false,
    /** 另一侧的音量。1.0 = 两边一样响（分声道等于没分），0.12 = 只留一点底噪。 */
    private val bleed: Float = 0.12f,
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
    private val playback = LinkedBlockingQueue<Chunk>()
    private var playThread: Thread? = null

    private val transcript = StringBuilder()

    /** 一片播放音频 + 它该走哪边（0 = 两边一样，1 = 左，2 = 右）。 */
    private class Chunk(val data: ByteArray, val side: Int)

    /** 这一句收到了几片音频：0 = 模型只给了文字没给声音。 */
    private var respDeltas = 0

    /** 这一句该走哪边：0 = 还没定，1 = 左（英文），2 = 右（中文）。 */
    private var panSide = 0
    private var lastPanSide = SIDE_LEFT
    /** 定边之前先攒着：第一片音频往往比第一个文字 delta 到得还早。 */
    private val pending = ArrayList<ByteArray>()

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
            "response.created" -> {
                speaking = true; responseDone = false
                panSide = 0
                pending.clear()
                respDeltas = 0
            }
            "response.audio.delta" -> {
                val b64 = ev.optString("delta")
                if (b64.isNotEmpty()) routeAudio(Base64.decode(b64, Base64.DEFAULT))
            }
            "response.audio_transcript.delta", "response.text.delta", "response.output_text.delta" -> {
                transcript.append(ev.optString("delta"))
                if (panSplit && panSide == 0) {
                    val guess = guessSide()
                    if (guess != 0) decide(guess)
                }
            }
            "response.audio_transcript.done", "response.text.done", "response.done" -> {
                // 这里不再直接把 speaking 置回 false：服务端的 done 早于本地播放结束，
                // 立刻开麦就会把自己的译文再听一遍（实测同一句话被译了三遍）。
                responseDone = true
                Logger.i(LOG_TAG, "resp done audioDeltas=" + respDeltas + " bytes=" + audioBytes)
                // 文字一直没到（纯音频回复）也要定边，否则这一句会永远攒在 pending 里。
                if (panSplit && panSide == 0) decide(lastPanSide)
                if (transcript.isNotEmpty()) {
                    onTranscript(transcript.toString())
                    transcript.setLength(0)
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                // 模型到底听见了什么：没有这行，「没反应」只能靠猜是麦克风还是播放。
                Logger.i(LOG_TAG, "heard: " + ev.optString("transcript").take(80))
            }
            "conversation.item.input_audio_transcription.failed" -> {
                Logger.w(LOG_TAG, "heard: <failed> " + ev.optJSONObject("error")?.optString("message"))
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
        // 关键诊断：插着耳机时到底在录哪个设备。录错设备 = 模型什么都没听见。
        runCatching {
            val rd = rec.routedDevice
            Logger.i(LOG_TAG, "mic routed type=" + (rd?.type ?: -1) + " name=" + (rd?.productName ?: "?"))
        }
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

    /** 收到一片译文音频：知道走哪边就直接排队，不知道就先攒着。 */
    private fun routeAudio(pcm: ByteArray) {
        audioBytes += pcm.size
        respDeltas++
        if (!panSplit) { playback.put(Chunk(pcm, 0)); return }
        val side = panSide
        if (side == 0) {
            pending.add(pcm)
            // 文字迟迟不来也不能一直攒：攒到 8 片（约 200ms）就按上一句的边放。
            if (pending.size >= 8) decide(lastPanSide)
            return
        }
        playback.put(Chunk(pcm, side))
    }

    /** 定边，并把攒下的音频一口气放出去。 */
    private fun decide(side: Int) {
        panSide = side
        lastPanSide = side
        val buffered = ArrayList(pending)
        pending.clear()
        Logger.i(
            LOG_TAG,
            "pan side=" + sideName(side) + " buffered=" + buffered.size + " text=" + transcript.toString().take(24),
        )
        for (p in buffered) playback.put(Chunk(p, side))
    }

    /**
     * 从译文文字猜语言：出现汉字就是中文译文（右耳），
     * 纯拉丁字母攒够 6 个就是英文译文（左耳）。不够 6 个先不定边 ——
     * 「O」这种一个字母的 delta 判不出什么。
     */
    private fun guessSide(): Int {
        var latin = 0
        for (ch in transcript) {
            if (ch.code in 0x3400..0x4DBF || ch.code in 0x4E00..0x9FFF) return SIDE_RIGHT
            if (ch.isLetter()) latin++
        }
        return if (latin >= 6) SIDE_LEFT else 0
    }

    private fun sideName(side: Int) = when (side) {
        SIDE_LEFT -> "left(en)"
        SIDE_RIGHT -> "right(zh)"
        else -> "both"
    }

    /** 单声道 PCM 摊成双声道：本侧满音量，另一侧留 BLEED，免得听感像断连。 */
    private fun toStereo(pcm: ByteArray, side: Int): ByteArray {
        val n = pcm.size / 2
        val out = ByteArray(n * 4)
        var i = 0
        while (i < n) {
            val s = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            val l = if (side == SIDE_RIGHT) (s * bleed).toInt() else s
            val r = if (side == SIDE_LEFT) (s * bleed).toInt() else s
            val o = i * 4
            out[o] = (l and 0xFF).toByte()
            out[o + 1] = ((l shr 8) and 0xFF).toByte()
            out[o + 2] = (r and 0xFF).toByte()
            out[o + 3] = ((r shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    /** 播放：24kHz PCM 流式写进 AudioTrack，优先走耳机。 */
    private fun startPlayback() {
        val channels = if (panSplit) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(24_000)
            .setChannelMask(channels)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val bufBytes = if (panSplit) 48_000 else 24_000
        val minBuf = AudioTrack.getMinBufferSize(24_000, channels, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder().setAudioFormat(fmt).setAudioAttributes(attrs)
                .setBufferSizeInBytes(maxOf(minBuf, bufBytes)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrNull() ?: return
        runCatching { AudioRouter.earphones(ctx)?.let { t.setPreferredDevice(it) } }
        val played = runCatching { t.play() }.isSuccess
        // 真的写到哪个设备上：绑错设备（比如没激活的 SCO）就是完全没声音。
        runCatching {
            val rd = t.routedDevice
            Logger.i(LOG_TAG, "audiotrack routed type=" + (rd?.type ?: -1) + " name=" + (rd?.productName ?: "?"))
        }
        Logger.i(
            LOG_TAG,
            "audiotrack playing=" + played + " panSplit=" + panSplit + " ch=" +
                (if (panSplit) "stereo" else "mono") + " state=" + t.playState +
                " buf=" + maxOf(minBuf, bufBytes),
        )
        track = t
        playThread = Thread {
            while (running) {
                val chunk = playback.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // 队列空了：如果这一句已经说完，才把麦克风放回来。
                    if (responseDone) { responseDone = false; speaking = false }
                    continue
                }
                val data = if (panSplit) toStereo(chunk.data, chunk.side) else chunk.data
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
        /** 分声道：1 = 左耳（英文），2 = 右耳（中文）。 */
        const val SIDE_LEFT = 1
        const val SIDE_RIGHT = 2
        /** 另一侧的音量：留一点，才不像那只耳朵坏了。 */
        const val BLEED = 0.12f
    }
}
