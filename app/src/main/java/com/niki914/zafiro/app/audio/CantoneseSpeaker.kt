package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.MediaPlayer
import com.niki914.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 粤语播报：Qwen TTS 的粤语音色（Rocky 粤语-阿强 / Kiki 粤语-阿清）。
 *
 * 为什么单独一个类：实时会话的音色表里没有粤语（只有 longan* 和几个英文音色），
 * 传 Rocky 会被服务端直接拒绝 —— 「Unsupported voice: 'Rocky'」。
 * 所以粤语走「实时会话只要文字 + 这里单独合成语音」，音色选用户试听后认可的 Rocky。
 *
 * Rocky/Kiki 只在非实时接口 qwen3-tts-flash 上提供，language_type 仍然是 chinese：
 * 粤语是靠音色区分的，不是靠 language_type。
 */
class CantoneseSpeaker(private val appContext: Context) {

    private companion object {
        private const val TAG = "niki914_zafiro_CantoneseTts"
        private const val ENDPOINT =
            "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val MODEL = "qwen3-tts-flash"
        /** 用户试听后选定：Rocky（粤语-阿强，香港男声）。 */
        private const val VOICE = "Rocky"
        private const val KEY_FILE = "qwen_asr_key.txt"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /** 同步念一句：调用方必须在后台线程。返回是否真的发出声音。 */
    fun speak(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val key = runCatching {
            File(appContext.filesDir, KEY_FILE).readText().trim()
        }.getOrNull().orEmpty()
        if (key.isEmpty()) {
            Logger.w(TAG, "no qwen key, cannot speak Cantonese")
            return false
        }
        val payload = JSONObject()
            .put("model", MODEL)
            .put(
                "input",
                JSONObject()
                    .put("text", body)
                    .put("voice", VOICE)
                    .put("language_type", "chinese"),
            )
            .toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer " + key)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            val audioUrl = http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Logger.w(TAG, "tts http " + response.code + ": " + raw.take(160))
                    return false
                }
                JSONObject(raw).getJSONObject("output").getJSONObject("audio").optString("url")
            }
            if (audioUrl.isEmpty()) {
                Logger.w(TAG, "tts returned no url")
                return false
            }
            val file = File(appContext.cacheDir, "yue_" + System.currentTimeMillis() + ".wav")
            http.newCall(Request.Builder().url(audioUrl).build()).execute().use { resp ->
                val stream = resp.body?.byteStream() ?: return false
                file.outputStream().use { out -> stream.copyTo(out) }
            }
            play(file)
        } catch (e: Throwable) {
            Logger.w(TAG, "speak failed: " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun play(file: File): Boolean {
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            val duration = player.duration.coerceAtLeast(300)
            Logger.i(TAG, "speaking " + file.length() + " bytes, " + duration + " ms")
            Thread.sleep(duration.toLong() + 120L)
            player.release()
            true
        } catch (e: Throwable) {
            Logger.w(TAG, "playback failed: " + (e.message ?: e.javaClass.simpleName))
            runCatching { player.release() }
            false
        } finally {
            runCatching { file.delete() }
        }
    }
}
