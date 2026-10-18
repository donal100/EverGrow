package com.niki914.zafiro.app.audio

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Qwen ASR（qwen3-asr-flash）——把录音文件转成文本。
 *
 * 走 OpenAI 兼容的 chat/completions：音频以 base64 data URI 塞进 input_audio。
 * 只认 qwen3-asr-flash 这一个模型名；同系列的 qwen-audio-3.0-asr-flash 在这个
 * 路由上会 400（它只服务于 /api/v1/services/* 那条链路）。
 *
 * 实测（5s m4a）：约 0.8s 往返，11s 音频也只要约 1s——延迟基本与时长无关。
 *
 * API key 从 filesDir/qwen_asr_key.txt 读取，不写进源码：key 属于用户，不属于仓库。
 * 用的是 Pay-As-You-Go key（sk-ws-）；Token Plan key（sk-sp-）在该网关没有 ASR 模型。
 */
class QwenAsrClient(private val keyFile: File) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun hasKey(): Boolean = keyFile.isFile && keyFile.length() > 0L

    /** 转写失败时返回 failure，调用方负责提示；录音文件不会在这里被删除，方便重试。 */
    suspend fun transcribe(audio: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasKey()) error("ASR key not configured")
            val key = keyFile.readText().trim()
            require(key.isNotEmpty()) { "ASR key is empty" }

            val encoded = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject()
                .put("model", MODEL)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put("input_audio", JSONObject().put("data", "data:$MIME;base64,$encoded"))
                                )
                            )
                    )
                )
                .toString()

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer $key")
                .post(payload.toRequestBody(JSON))
                .build()

            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("ASR HTTP " + response.code + ": " + raw.take(160))
                }
                JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim()
            }
        }
    }

    companion object {
        private const val ENDPOINT =
            "https://maas.qwencloudapi.com/compatible-mode/v1/chat/completions"
        private const val MODEL = "qwen3-asr-flash"
        private const val MIME = "audio/mp4"
        private val JSON = "application/json".toMediaType()

        /** 录音文件与 key 的约定位置（都在应用私有目录内）。 */
        fun keyFileFor(appContext: android.content.Context): File =
            File(appContext.filesDir, "qwen_asr_key.txt")
    }
}
