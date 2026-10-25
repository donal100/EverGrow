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
    /** 模型听见的原话。字幕流里要有这一行，屏幕才看得出「谁说了什么」。 */
    private val onHeard: (String) -> Unit = {},
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
                // 听写模式验证过的路子：播报走媒体通道，耳机才会回到 A2DP 出声；
                // 通话链路（SCO）留着的话 A2DP 是哑的。所以这里先放掉 SCO。
                if (earbudMicActive) releaseCallRoute()
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
                val said = ev.optString("transcript").trim()
                Logger.i(LOG_TAG, "heard: " + said.take(80))
                if (said.isNotEmpty()) runCatching { onHeard(said) }
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
    /** 是不是在用耳机麦克风 —— 是的话播放前后要像听写模式那样在 SCO 和 A2DP 之间来回切。 */
    @Volatile private var earbudMicActive = false
    /** 当前想用哪个麦克风：true = 耳机，false = 手机（播报窗口里用手机）。 */
    @Volatile private var micWantEarbud = true
    /** 诊断用：把送上去的麦克风音频原样存一份，事后可以回放/转写。 */
    private var dump: java.io.FileOutputStream? = null
    private var dumpBytes = 0L
    private var dumpFile: File? = null

    /** 上一次切换路由的时刻，用来给 A2DP 留一点醒过来的时间。 */
    @Volatile private var routeSwitchAt = 0L
    private var micChunks = 0
    private var micSumSq = 0L
    private var micFrames = 0

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
        // 两个麦克风分时段用，不能同时用（实测两个 AudioRecord 拿到的是同一路音频）：
        //   听的时候用耳机麦克风（离说话的人最近）；
        //   播报的时候耳机链路必须放下（否则耳机没声音），这段时间改用手机麦克风，
        //   这样对面的人在这几秒里说的话也不会丢。
        val earbudMic = if (micWantEarbud) AudioRouter.earbudsMic(ctx) else null
        earbudMicActive = AudioRouter.earbudsMic(ctx) != null
        if (earbudMic != null) {
            val sco = AudioRouter.enterCallAudio(ctx)
            Logger.i(LOG_TAG, "mic pref=earbuds type=" + earbudMic.type + " name=" + earbudMic.productName + " scoOn=" + sco)
            runCatching { rec.setPreferredDevice(earbudMic) }
        } else {
            Logger.i(LOG_TAG, "mic pref=phone (playback window or no earbud mic)")
            runCatching { AudioRouter.builtInMic(ctx)?.let { rec.setPreferredDevice(it) } }
        }
        record = rec
        runCatching { rec.startRecording() }
        openDump()
        // 关键诊断：插着耳机时到底在录哪个设备。录错设备 = 模型什么都没听见。
        runCatching {
            val rd = rec.routedDevice
            Logger.i(LOG_TAG, "mic routed type=" + (rd?.type ?: -1) + " name=" + (rd?.productName ?: "?"))
        }
        micThread = Thread {
            val buf = ShortArray(1600)
            // 只认自己那个录音器：换麦克风时旧线程必须自己退出，否则读已释放的对象。
            while (running && record === rec) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n <= 0) continue
                if (muted) continue
                // 有耳机时播报期间也在录（那几秒用的是手机麦克风），所以不再整段跳过。
                if (halfDuplex && !textOnly && speaking && !earbudMicActive) continue
                val bytes = ByteArray(n * 2)
                var p = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[p++] = (v and 0xFF).toByte()
                    bytes[p++] = ((v shr 8) and 0xFF).toByte()
                }
                appendDump(bytes)
                // 每 200 片报一次电平：能区分「麦克风没声」和「模型没听见」。
                var sum = 0L
                for (i in 0 until n) { val v = buf[i].toInt(); sum += (v * v).toLong() }
                micSumSq += sum; micFrames += n
                if (++micChunks >= 200) {
                    val rms = if (micFrames > 0) Math.sqrt(micSumSq.toDouble() / micFrames).toInt() else 0
                    Logger.i(LOG_TAG, "mic level rms=" + rms + " frames=" + micFrames)
                    micChunks = 0; micSumSq = 0L; micFrames = 0
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

    /** 开一个 WAV 把麦克风原音存下来（16k 单声道，和送给模型的完全一致）。 */
    private fun openDump() {
        closeDump()
        if (!dumpEnabled()) return
        runCatching {
            val dir = File(ctx.filesDir, "translate_mic").apply { mkdirs() }
            val f = File(dir, "mic_" + System.currentTimeMillis() + ".wav")
            val out = java.io.FileOutputStream(f)
            out.write(ByteArray(44))   // 先占位，收尾时回填长度
            dump = out; dumpFile = f; dumpBytes = 0L
            Logger.i(LOG_TAG, "mic dump -> " + f.name)
        }
    }

    private fun appendDump(bytes: ByteArray) {
        val out = dump ?: return
        runCatching {
            out.write(bytes)
            dumpBytes += bytes.size
            if (dumpBytes > 60L * 1024 * 1024) closeDump()   // 60MB 上限，约 30 分钟
        }
    }

    /** 收尾：回填 WAV 头。写坏也不能影响翻译，所以全都包在 runCatching 里。 */
    private fun closeDump() {
        val out = dump ?: return
        val f = dumpFile
        dump = null; dumpFile = null
        if (f == null) { return }
        runCatching {
            val raf = java.io.RandomAccessFile(f, "rw")
            raf.seek(0)
            val dataLen = dumpBytes.toInt()
            val h = ByteArray(44)
            fun put32(off: Int, v: Int) {
                h[off] = (v and 0xFF).toByte()
                h[off + 1] = ((v shr 8) and 0xFF).toByte()
                h[off + 2] = ((v shr 16) and 0xFF).toByte()
                h[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            "RIFF".toByteArray().copyInto(h, 0)
            put32(4, dataLen + 36)
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            put32(16, 16); h[20] = 1; h[22] = 1
            put32(24, 16000); put32(28, 32000)
            h[32] = 2; h[34] = 16
            "data".toByteArray().copyInto(h, 36)
            put32(40, dataLen)
            raf.write(h)
            raf.close()
            out.close()
            Logger.i(LOG_TAG, "mic dump closed bytes=" + dataLen + " file=" + f.name)
        }
    }

    /** files/voice_tuning.txt: translate_dump=0 关掉录音存档。默认开着，方便诊断。 */
    private fun dumpEnabled(): Boolean = runCatching {
        val f = File(ctx.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching true
        f.readLines().firstOrNull { it.trim().startsWith("translate_dump=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    /** 播报开始：放下通话链路，让媒体通道回到 A2DP。 */
    private fun releaseCallRoute() {
        runCatching { AudioRouter.reset(ctx) }
        routeSwitchAt = System.currentTimeMillis()
        // 播报期间的几秒钟改用手机麦克风：它就在两人之间，对面的人说话听得见。
        micWantEarbud = false
        restartMic()
        Logger.i(LOG_TAG, "route: playback start, mic -> phone")
    }

    /** 播报结束：重新建 SCO 并把耳机麦克风接回来。 */
    private fun restoreCallRoute() {
        Thread {
            // 按语言决定下一个收音的麦克风，而不是死板地轮流：
            //   这一次出的是英文译文  -> 说明刚才是中文（对面那位）在说，接下来轮到戴耳机的你 -> 耳机麦克风
            //   这一次出的是中文译文  -> 说明刚才是你在说，接下来轮到对面那位 -> 手机麦克风
            // 好处：同一个人连说两句也不会错位。
            val side = if (panSide != 0) panSide else lastPanSide
            val replyWasEnglish = side != SIDE_RIGHT
            micWantEarbud = replyWasEnglish
            routeSwitchAt = 0L
            val sco = if (micWantEarbud) {
                runCatching { AudioRouter.enterCallAudio(ctx) }.getOrDefault(false)
            } else {
                runCatching { AudioRouter.reset(ctx) }
                false
            }
            restartMic()
            Logger.i(LOG_TAG, "route: turn end, mic -> " + (if (micWantEarbud) "earbuds" else "phone") + " scoOn=" + sco)
        }.apply { isDaemon = true }.start()
    }

    /** 丢掉旧录音器，按同一套规则再建一个（耳机麦克风优先）。 */
    @Synchronized
    private fun restartMic() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        micStarted = false
        startMic()
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
        // CONTENT_TYPE_SPEECH 在这台机器上（HyperOS + FreeClip）走的是「话音」那条支路：
        // 轨道在播、write 全成功、routed 设备就是 A2DP，但耳机里一点声音都没有。
        // 实测同一段音：SPEECH = 完全没声，MUSIC = 正常。别改回去。
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // 缓冲区也别太小：48000 字节（0.5 秒）同样会静音，96000 就正常。
        val bufBytes = 96_000
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
            // 已经写进轨道多少帧：队列空 != 放完，轨道里还压着最多一秒的音频。
            var writtenFrames = 0L
            while (running) {
                val chunk = playback.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    // 队列空了：如果这一句已经说完，才把麦克风放回来。
                    if (responseDone) {
                        // 等轨道真的放到尾巴（留 100ms 余量）再换路由，否则最后半句被切掉。
                        val until = System.currentTimeMillis() + 3000
                        while (running && System.currentTimeMillis() < until) {
                            val left = writtenFrames - runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(writtenFrames)
                            if (left <= 2400) break
                            runCatching { Thread.sleep(20) }
                        }
                        responseDone = false; speaking = false
                        if (earbudMicActive) restoreCallRoute()
                    }
                    continue
                }
                // 刚切过路由的话，等 A2DP 醒过来再写，否则开头半秒会被吞掉。
                val since = System.currentTimeMillis() - routeSwitchAt
                if (since in 1..300) runCatching { Thread.sleep(300 - since) }
                val data = if (panSplit) toStereo(chunk.data, chunk.side) else chunk.data
                writtenFrames += data.size / (if (panSplit) 4 else 2)
                val wrote = runCatching { t.write(data, 0, data.size) }.getOrDefault(-1)
                if (wrote < 0 && audioWarned.compareAndSet(false, true)) {
                    Logger.w(LOG_TAG, "audiotrack write failed code=" + wrote)
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        closeDump()
        // 如果为了耳机麦克风把音频切到了通话模式，退出时一定要还回去。
        runCatching { AudioRouter.reset(ctx) }
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
