package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** 念一段文本；Result 里是「实际走了哪条路」，方便调用方提示或诊断。 */
    suspend fun speak(text: String): Result<String> = withContext(Dispatchers.IO) {
        log("speak() called, chars=" + text.length)
        val clean = SpeechOutput.toSpeakable(text)
        if (clean.isBlank()) {
            log("speak() aborted: empty after markdown strip")
            return@withContext Result.success("nothing to speak")
        }
        log("cleaned chars=" + clean.length + " head=" + clean.take(60))

        runCatching { speakWithQwen(clean) }.fold(
            onSuccess = {
                log("qwen path OK")
                Result.success("qwen")
            },
            onFailure = { err ->
                log("qwen FAILED: " + (err.message ?: err.javaClass.simpleName) + " -> falling back to device TTS")
                fallback.speak(clean)
                Result.failure(err)
            },
        )
    }

    /** 供 UI 层记录「钩子是否触发」，与 speak() 写同一个文件。 */
    fun note(msg: String) = log(msg)

    /** 诊断用：这台设备拿不到 logcat，落盘一个增量日志。 */
    private fun log(msg: String) {
        runCatching {
            File(appContext.filesDir, "voice_debug.log")
                .appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    private suspend fun speakWithQwen(text: String) {
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

        val file = File(appContext.cacheDir, "tts_" + System.currentTimeMillis() + ".wav")
        file.writeBytes(bytes)
        try {
            playBlocking(file)
        } finally {
            file.delete()
        }
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
            while (mp.isPlaying) delay(80)
        } finally {
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        fallback.stop()
    }

    fun release() {
        stop()
        fallback.release()
    }

    companion object {
        private const val ENDPOINT =
            "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val MODEL = "qwen3-tts-flash"
        private const val VOICE = "Ethan"
        private const val KEY_FILE = "qwen_asr_key.txt"
        private val JSON = "application/json".toMediaType()
    }
}
