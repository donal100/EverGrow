package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    fun submit(text: String) {
        val clean = SpeechOutput.toSpeakable(text)
        if (clean.isBlank()) return
        cancelled = false
        log("submit chars=" + clean.length + " head=" + clean.take(40) + " queued=" + (pending.size + 1))
        pending.offer(clean)
        if (workerStarted.compareAndSet(false, true)) {
            workerScope.launch { workerLoop() }
        }
    }

    /** 等队列念完（调用方据此决定何时把麦克风交还通话模式）。 */
    suspend fun awaitIdle() {
        while (playing || pending.isNotEmpty()) delay(60)
    }

    private suspend fun workerLoop() {
        while (workerScope.isActive) {
            val text = withContext(Dispatchers.IO) { pending.poll(1, TimeUnit.SECONDS) }
            if (text == null) continue
            if (cancelled) {
                pending.clear()
                continue
            }
            val audio = synthesizeToFile(text)
            // 关键：播放之前先把下一句的合成推起来，接缝才不会露出来
            val prefetch = workerScope.async {
                val nextText = withContext(Dispatchers.IO) { pending.poll(15, TimeUnit.SECONDS) }
                if (nextText == null || cancelled) null else synthesizeToFile(nextText)
            }
            log("worker: synthesised chars=" + text.length + " head=" + text.take(40) + " file=" + (audio != null))
            if (audio != null) {
                playing = true
                try {
                    runCatching { playBlocking(audio) }
                } finally {
                    playing = false
                    audio.delete()
                    log("worker: played chars=" + text.length)
                }
            }
            prefetch.await()
            if (cancelled) pending.clear()
        }
    }

    /** 合成到文件；失败返回 null（这时退回本机 TTS 直接念，宁可音色差也别哑巴）。 */
    private suspend fun synthesizeToFile(text: String): File? = withContext(Dispatchers.IO) {
        runCatching { speakWithQwen(text, returnFile = true) }
            .getOrNull()
            ?.let { path -> File(path).takeIf { it.isFile } }
            ?: run {
                if (!cancelled) fallback.speak(text)
                null
            }
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
        stop()
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

    fun stop() {
        cancelled = true
        playing = false
        pending.clear()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        fallback.stop()
    }

    fun release() {
        stop()
        runCatching { workerScope.cancel() }
        fallback.release()
    }

    companion object {
        private const val ENDPOINT =
            "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val MODEL = "qwen3-tts-flash"
        private const val VOICE = "Dylan"
        private const val KEY_FILE = "qwen_asr_key.txt"
        private val JSON = "application/json".toMediaType()
    }
}
