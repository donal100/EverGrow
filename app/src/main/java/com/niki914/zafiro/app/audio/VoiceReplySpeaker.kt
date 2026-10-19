package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把回答念出来。主路是 Qwen 云端音色（qwen3-tts-flash），本机 TTS 只作为兜底。
 *
 * 选主路为 Qwen 的理由：实测音色明显更自然，且亚洲口音/多语种比系统英语引擎靠谱
 * （系统引擎在这台机器上只有英文音色，中文会被念成英文腔）。
 * 兜底留着的理由：没网、配额用完、key 失效时，宁可机器人声也不能一声不吭。
 *
 * 合成返回的是一个短时效的 OSS 链接（约 1 小时），所以每次都要重新合成，不能缓存 URL。
 */
class VoiceReplySpeaker(private val appContext: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 流式合成专用的 client。读超时必须短：
     * 实测有一次 "Nine." 的请求只回了一个 chunk 就卡住 19 秒，
     * 期间麦克风一直被 pause 住 —— 用户以为「不说话了也不听了」。
     * 8 秒没有新数据就放弃，走兜底合成（约 1.9 秒出音）。
     */
    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 播报前申请音频焦点：本地识别器一直占着音频，不申请会被压制。 */
    private fun requestFocus(): Boolean = runCatching {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }.getOrDefault(false)

    private val fallback = SpeechOutput(appContext)
    private var player: MediaPlayer? = null

    /**
     * 用户打断的开关。合成要 1-2 秒，光 release 播放器不够 ——
     * 必须让已经在路上的那一次合成知道「别念了」，否则它下载完照样开口。
     */
    @Volatile
    private var cancelled = false

    /**
     * 流水线播放用的队列。为什么要流水线：流式播报是按句合成的，如果「念完一句再去合成下一句」，
     * 每两句之间就空出一次合成的 1-2 秒 —— 听感就是「他停顿一下再接下一句」。
     * 这里让下一句在当前句还响着的时候就开始合成，接缝就没了。
     */
    private val pending = LinkedBlockingQueue<String>()
    /** 合成好的音频排队等播放：合成线程和播放线程各跑各的，句与句之间自然接上。 */
    private val readyAudio = LinkedBlockingQueue<File>()
    private val synthExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tts-synth").apply { isDaemon = true } }
    private val playExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tts-play").apply { isDaemon = true } }

    @Volatile
    private var shutdown = false

    @Volatile
    private var synthBusy = false

    private val workerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, err ->
            // 音频线程出什么事都不该带走整个应用：记一行，继续服务下一条
            log("audio worker error: " + (err.message ?: err.javaClass.simpleName))
        },
    )
    private val workerStarted = AtomicBoolean(false)

    @Volatile
    private var playing = false

    /**
     * 念一段文本；Result 里是「实际走了哪条路」，方便调用方提示或诊断。
     *
     * [preferDevice] 用于通话模式：本机引擎几乎立刻出声，Qwen 要先合成再下载，
     * 一来一回 1.5-2 秒。走路跑步时的对话，快比好听重要。
     */
    suspend fun speak(text: String, preferDevice: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
        cancelled = false
        log("speak() called, chars=" + text.length + " preferDevice=" + preferDevice)
        val clean = SpeechOutput.toSpeakable(text)
        if (clean.isBlank()) {
            log("speak() aborted: empty after markdown strip")
            return@withContext Result.success("nothing to speak")
        }
        log("cleaned chars=" + clean.length + " head=" + clean.take(60))

        if (preferDevice) {
            if (!cancelled) fallback.speak(clean)
            return@withContext Result.success("device")
        }

        if (cancelled) {
            log("speak() cancelled before synthesis")
            return@withContext Result.success("cancelled")
        }
        runCatching { speakWithQwen(clean) }.fold(
            onSuccess = {
                log("qwen path OK")
                Result.success("qwen")
            },
            onFailure = { err ->
                log("qwen FAILED: " + (err.message ?: err.javaClass.simpleName) + " -> falling back to device TTS")
                if (!cancelled) fallback.speak(clean)
                Result.failure(err)
            },
        )
    }

    /** 供 UI 层记录「钩子是否触发」，与 speak() 写同一个文件。 */
    fun note(msg: String) = log(msg)

    /** 排队念一句（流式播报用）；合成与播放会在后台流水线里接续。 */
    /** 清掉以前会话残留的合成文件（异常退出会留下几十个 wav）。 */
    private fun pruneCache() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
            appContext.cacheDir.listFiles { f -> f.name.startsWith("tts_") && f.name.endsWith(".wav") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    fun submit(text: String) {
        val clean = SpeechOutput.toSpeakable(text)
        if (clean.isBlank()) return
        cancelled = false
        log("submit chars=" + clean.length + " head=" + clean.take(40) + " queued=" + (pending.size + 1))
        pending.offer(clean)
        if (workerStarted.compareAndSet(false, true)) {
            pruneCache()
            // 用真正的阻塞线程而不是协程调度器：嵌套 withContext 在这个 Chaquopy 环境里
            // 会卡住（合成完第一句之后再也不取第二句），线程池没有这个坑。
            synthExec.execute { synthLoopBlocking() }
            playExec.execute { playLoopBlocking() }
        }
    }

    /**
     * 队列念完时回调一次（在播放线程上）。
     * 通话模式靠它把麦克风交还回去 —— 少了这一句，说完一句就再也不听了。
     */
    @Volatile
    var onIdle: (() -> Unit)? = null

    /** 现在真的有声音在放/要放吗？打断逻辑靠它判断，避免用陈旧状态误触发。 */
    fun isBusy(): Boolean = playing || pending.isNotEmpty() || readyAudio.isNotEmpty()

    /** 等队列念完（调用方据此决定何时把麦克风交还通话模式）。 */
    suspend fun awaitIdle() {
        while (playing || synthBusy || pending.isNotEmpty() || readyAudio.isNotEmpty()) delay(60)
    }

    /** 文本 -> wav。阻塞线程，取到就合成，合成好就交给播放线程。 */

    /**
     * 流式朗读：qwen3.5-omni-plus 的 PCM 边到边放。
     *
     * 之前的路径是「整段合成 → 下载整段 wav → 再播放」，首字延迟 ≈1.9s；
     * 现在是「边合成边放」，首段音频 ≈1.2s 就到了，长回答的差别更明显。
     * 返回 false 表示这条路走不通（没网/模型拒绝），调用方退回原来的合成到文件。
     */
    private fun streamAndPlay(rawText: String): Boolean {
        if (cancelled) return true
        // 先把数字/时间/单位展开成英文单词，否则多语种音色会把 "0.96 km" 念成别的东西
        val text = SpokenForm.of(rawText)
        if (text.length <= 12) return false
        val key = runCatching { File(appContext.filesDir, KEY_FILE).readText().trim() }.getOrNull()
        if (key.isNullOrEmpty()) return false
        val payload = JSONObject()
            .put("model", STREAM_MODEL)
            .put("modalities", org.json.JSONArray().put("text").put("audio"))
            .put("audio", JSONObject().put("voice", VOICE).put("format", "pcm16"))
            .put("stream", true)
            .put("max_tokens", 1200)
            .put("messages", org.json.JSONArray().put(
                JSONObject().put("role", "user")
                    .put("content", "Read this aloud exactly as written, nothing else: " + text)))
            .toString()
        val request = Request.Builder()
            .url(STREAM_ENDPOINT)
            .addHeader("Authorization", "Bearer " + key)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            streamHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { log("stream: HTTP " + response.code); return false }
                val source = response.body?.source() ?: return false
                val pcm = ByteArrayOutputStream()
                var lastChunkAt = 0L
                var worstGap = 0L
                var jitter = 0
                var chunks = 0
                while (!source.exhausted() && !cancelled) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val body = line.substring(5).trim()
                    if (body == "[DONE]") break
                    val ev = runCatching { JSONObject(body) }.getOrNull() ?: continue
                    val audio = ev.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optJSONObject("audio")?.optString("data").orEmpty()
                    if (audio.isEmpty()) continue
                    val bytes = Base64.decode(audio, Base64.DEFAULT)
                    if (bytes.isEmpty()) continue
                    pcm.write(squeezeSilence(bytes))
                    chunks++
                    val now = System.currentTimeMillis()
                    if (lastChunkAt > 0) {
                        val gap = now - lastChunkAt
                        if (gap > 120) { jitter++; if (gap > worstGap) worstGap = gap }
                    }
                    lastChunkAt = now
                }
                val raw = pcm.toByteArray()
                log("stream: done chunks=" + chunks + " chars=" + text.length +
                    " worstGapMs=" + worstGap + " jitter=" + jitter)
                if (raw.isEmpty() || cancelled) return raw.isNotEmpty()
                saveSample(raw, text, worstGap, jitter, chunks)
                // 用文件播放而不是 AudioTrack 流式播放：实测识别器占着音频时
                // AudioTrack 的播放头根本不前进（playback head=0，writtenFrames=71040），
                // 而 MediaPlayer 走同一条路是正常的。
                val file = File(appContext.cacheDir, "tts_stream_" + System.currentTimeMillis() + ".wav")
                file.writeBytes(wavBytes(raw))
                playing = true
                try {
                    requestFocus()
                    runBlocking { playBlocking(file) }
                } finally {
                    playing = false
                    file.delete()
                }
                val hook = onIdle
                if (hook != null && pending.isEmpty()) runCatching { hook() }
                true
            }
        } catch (e: Throwable) {
            log("stream failed: " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /** 24kHz PCM16 单声道 + wav 头。 */
    private fun wavBytes(pcm: ByteArray): ByteArray {
        val header = ByteArray(44)
        fun put32(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put16(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        put32(4, pcm.size + 36)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, 24_000); put32(28, 48_000)
        put16(32, 2); put16(34, 16)
        "data".toByteArray().copyInto(header, 36)
        put32(40, pcm.size)
        return header + pcm
    }

    /**
     * 把过长的静音压短。
     *
     * 实测 qwen3.5-omni-plus 会在逗号处停顿约 0.43 秒，12.8 秒的句子里出现四次，
     * 听起来就是「断断续续」。这里把超过 200ms 的静音压到 150ms，
     * 每段开头的静音也一并处理 —— 只是去掉多余的空白，不动语音本身。
     */
    private fun squeezeSilence(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n < 200) return pcm
        val samples = ShortArray(n)
        var i = 0
        var p = 0
        while (i < n) {
            val v = ((pcm[p + 1].toInt() shl 8) or (pcm[p].toInt() and 0xFF)).toShort()
            samples[i] = v
            i++; p += 2
        }
        val threshold = 400
        val maxRun = 24 * 150 / 10      // 150ms 允许的最长静音帧数（24kHz）
        val out = ShortArray(n)
        var w = 0
        var run = 0
        for (s in samples) {
            if (kotlin.math.abs(s.toInt()) < threshold) {
                run++
                if (run <= maxRun) { out[w++] = s }
            } else {
                run = 0
                out[w++] = s
            }
        }
        if (w == n) return pcm
        val res = ByteArray(w * 2)
        var q = 0
        for (k in 0 until w) {
            val v = out[k].toInt()
            res[q++] = (v and 0xFF).toByte()
            res[q++] = ((v shr 8) and 0xFF).toByte()
        }
        return res
    }

    /**
     * 等 AudioTrack 把写入的帧真的播完再停。
     * 之前直接 stop()，缓冲区里最后那几百毫秒被丢掉 —— 听感上就是句尾被切。
     */
    /**
     * 把这次合成的原始 PCM 存成 wav，方便事后分析：
     * 句间空隙、爆音、被切掉的尾巴都能从这里看出来。只留最近 SAMPLE_KEEP 条。
     */
    private fun saveSample(pcm: ByteArray, text: String, worstGapMs: Long, jitter: Int, chunks: Int) {
        runCatching {
            if (pcm.isEmpty()) return
            val dir = File(appContext.filesDir, "voice_samples")
            if (!dir.exists()) dir.mkdirs()
            val stamp = System.currentTimeMillis()
            val wav = File(dir, "tts_" + stamp + ".wav")
            val header = ByteArray(44)
            fun put32(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
                header[off + 2] = ((v shr 16) and 0xFF).toByte()
                header[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun put16(off: Int, v: Int) {
                header[off] = (v and 0xFF).toByte()
                header[off + 1] = ((v shr 8) and 0xFF).toByte()
            }
            "RIFF".toByteArray().copyInto(header, 0)
            put32(4, pcm.size + 36)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            put32(16, 16); put16(20, 1); put16(22, 1)
            put32(24, 24_000); put32(28, 48_000)
            put16(32, 2); put16(34, 16)
            "data".toByteArray().copyInto(header, 36)
            put32(40, pcm.size)
            wav.writeBytes(header)
            wav.appendBytes(pcm)
            File(dir, "tts_" + stamp + ".txt").writeText(
                "chunks=" + chunks + " worstGapMs=" + worstGapMs + " jitter=" + jitter + "\n" + text
            )
            val all = dir.listFiles { f -> f.name.startsWith("tts_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            all.drop(SAMPLE_KEEP).forEach { runCatching { it.delete() } }
        }
    }

    private fun drain(t: AudioTrack, writtenFrames: Long) {
        runCatching {
            val deadline = System.currentTimeMillis() + 30_000
            while (!cancelled && System.currentTimeMillis() < deadline) {
                val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (played >= writtenFrames) break
                Thread.sleep(40)
            }
            runCatching { t.stop() }
        }
    }

    private fun synthLoopBlocking() {
        while (true) {
            if (shutdown) return
            val text = try {
                pending.take()
            } catch (e: InterruptedException) {
                return
            }
            if (cancelled) continue
            synthBusy = true
            try {
                // 优先流式：模型边说我们边放，首字延迟 ≈1.2s，而不是等整段合成完
                if (streamAndPlay(text)) continue
                val audio = runBlocking { synthesizeToFile(text) } ?: continue
                log("synth: ready chars=" + text.length + " head=" + text.take(32) + " pending=" + pending.size)
                if (cancelled) {
                    audio.delete()
                    continue
                }
                readyAudio.put(audio)
            } finally {
                synthBusy = false
            }
        }
    }

    /** wav -> 喇叭。阻塞线程，一句念完再取下一句。 */
    private fun playLoopBlocking() {
        while (true) {
            if (shutdown) return
            val audio = try {
                readyAudio.take()
            } catch (e: InterruptedException) {
                return
            }
            if (cancelled) {
                audio.delete()
                continue
            }
            playing = true
            try {
                runBlocking { playBlocking(audio) }
            } finally {
                playing = false
                audio.delete()
                log("play: done remaining=" + readyAudio.size)
                // 队列真的空了才通知：还有下一句在排着就别抢麦克风。
                if (pending.isEmpty() && readyAudio.isEmpty() && !cancelled) {
                    val hook = onIdle
                    if (hook != null) runCatching { hook() }
                }
            }
        }
    }

    /** 合成到文件；失败返回 null（这时退回本机 TTS 直接念，宁可音色差也别哑巴）。 */
    private suspend fun synthesizeToFile(text: String): File? = withContext(Dispatchers.IO) {
        val result = runCatching { speakWithQwen(text, returnFile = true) }
        result.exceptionOrNull()?.let { log("synth: FAILED " + (it.message ?: it.javaClass.simpleName)) }
        val file = result.getOrNull()?.let { path -> File(path).takeIf { it.isFile } }
        if (file == null) {
            log("synth: falling back to device TTS")
            if (!cancelled) fallback.speak(text)
        }
        file
    }

    /** 截掉首尾的静音：云合成给的 wav 两端各带几十毫秒空白，句句加起来就是可听见的顿挫。 */
    private fun trimSilence(file: File, threshold: Int = 350) {
        val bytes = file.readBytes()
        if (bytes.size < 64) return
        var p = 12
        var dataStart = -1
        var dataSize = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataStart = p + 8
                dataSize = minOf(size, bytes.size - dataStart)
                break
            }
            p += 8 + size + (size and 1)
        }
        if (dataStart < 0 || dataSize < 320) return
        val samples = dataSize / 2
        val shorts = ShortArray(samples)
        ByteBuffer.wrap(bytes, dataStart, dataSize).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(shorts)

        var first = 0
        while (first < samples && kotlin.math.abs(shorts[first].toInt()) < threshold) first++
        var last = samples - 1
        while (last > first && kotlin.math.abs(shorts[last].toInt()) < threshold) last--
        if (last - first < samples / 4) return  // 几乎全是静音就别动
        val pad = 640  // 40ms 余量，剪太狠会有咔哒声
        val start = (first - pad).coerceAtLeast(0)
        val end = (last + pad).coerceAtMost(samples - 1)
        val trimmedSize = (end - start + 1) * 2

        val header = bytes.copyOfRange(0, dataStart)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, 36 + trimmedSize)
            putInt(dataStart - 4, trimmedSize)
        }
        val out = ByteArray(header.size + trimmedSize)
        header.copyInto(out, 0)
        for (i in 0 until (end - start + 1)) {
            val s = shorts[start + i].toInt()
            out[header.size + i * 2] = (s and 0xff).toByte()
            out[header.size + i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        file.writeBytes(out)
    }

    /** 诊断用：这台设备拿不到 logcat，落盘一个增量日志。 */
    private fun log(msg: String) {
        runCatching {
            File(appContext.filesDir, "voice_debug.log")
                .appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    private suspend fun speakWithQwen(text: String, returnFile: Boolean = false): String? {
        val keyFile = File(appContext.filesDir, KEY_FILE)
        require(keyFile.isFile) { "Qwen key missing at filesDir/$KEY_FILE" }
        val key = keyFile.readText().trim()
        require(key.isNotEmpty()) { "Qwen key is empty" }

        val payload = JSONObject()
            .put("model", MODEL)
            .put(
                "input",
                JSONObject()
                    .put("text", text)
                    .put("voice", VOICE)
                    .put("language_type", languageFor(text)),
            )
            .toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer " + key)
            .post(payload.toRequestBody(JSON))
            .build()

        val audioUrl = http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("TTS HTTP " + response.code + ": " + raw.take(160))
            JSONObject(raw).getJSONObject("output").getJSONObject("audio").getString("url")
        }

        val bytes = http.newCall(Request.Builder().url(audioUrl).build())
            .execute().use { response ->
                if (!response.isSuccessful) error("TTS audio HTTP " + response.code)
                response.body?.bytes() ?: error("TTS audio body empty")
            }

        if (cancelled) {
            log("speakWithQwen cancelled before playback")
            return null
        }
        val file = File(appContext.cacheDir, "tts_" + System.currentTimeMillis() + ".wav")
        file.writeBytes(bytes)
        runCatching { trimSilence(file) }
        if (returnFile) return file.absolutePath
        try {
            playBlocking(file)
        } finally {
            file.delete()
        }
        return null
    }

    /** 显式指定正文脚本：Auto 会让英文套上中文音色的韵律，听起来发飘。 */
    private fun languageFor(text: String): String =
        if (text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }) "Chinese" else "English"

    private suspend fun playBlocking(file: File) {
        // 注意：这里只能停播放器。之前调的是 stop()，而 stop() 会把 cancelled 置真 ——
        // 于是「第一句开始播」就等于「取消后面所有句子的合成」，永远只念第一句。
        stopPlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            // 轮询而不是回调：MediaPlayer 的完成回调在非主线程上不好等，这里已经在 IO 线程
            // 播放器可能被 stop() 释放掉（关掉通话模式就是这条路径）。
            // 对已释放的 MediaPlayer 调 isPlaying 会抛 IllegalStateException，
            // 而这是在工作线程上，没人接就整个进程崩 —— 所以每一次轮询都要自保。
            while (runCatching { mp.isPlaying }.getOrDefault(false)) {
                delay(80)
            }
        } finally {
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    /** 只停当前播放（不动队列、不动取消标记）。 */
    private fun stopPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        fallback.stop()
    }

    fun stop() {
        // 诊断：谁在取消播放？之前只能靠猜，直接记下调用栈的前几帧。
        runCatching {
            val frames = Throwable().stackTrace
                .drop(1)
                .take(6)
                .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
            log("stop() from: " + frames)
        }
        cancelled = true
        playing = false
        pending.clear()
        while (true) {
            val f = readyAudio.poll() ?: break
            runCatching { f.delete() }
        }
        stopPlayer()
    }

    fun release() {
        stop()
        shutdown = true
        runCatching { synthExec.shutdownNow() }
        runCatching { playExec.shutdownNow() }
        runCatching { workerScope.cancel() }
        fallback.release()
    }

    companion object {
        private const val STREAM_ENDPOINT =
            "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
        private const val STREAM_MODEL = "qwen3.5-omni-plus"
        /** 24kHz 16bit 单声道：0.6 秒 ≈ 28800 字节 */
        private const val PREBUFFER_BYTES = 48_000
        /** 保留最近几段合成音频，供事后分析 */
        private const val SAMPLE_KEEP = 12
        private const val ENDPOINT =
            "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val MODEL = "qwen3-tts-flash"
        private const val VOICE = "Serena"
        private const val KEY_FILE = "qwen_asr_key.txt"
        private val JSON = "application/json".toMediaType()
    }
}
