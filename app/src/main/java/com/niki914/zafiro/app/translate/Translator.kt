package com.niki914.zafiro.app.translate

import android.content.Context
import com.niki914.zafiro.app.audio.QwenAsrClient
import com.niki914.zafiro.app.audio.VoiceReplySpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 双向口译：说中文 -> 出英文，说英文 -> 出中文。
 *
 * 一路实测耗时（2026-10）：ASR ≈1.1s、翻译 ≈0.9s、Qwen 合成 ≈1.9s，
 * 也就是说完到听见译文约 4 秒。这个数字决定了它「能用但不算快」。
 *
 * 为什么不问 agent：整条链路里不需要模型做任何判断——脚本检测语言、
 * 固定方向翻译。让 agent 参与只会多一层延迟。
 */
object Translator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    @Volatile private var appCtx: Context? = null
    @Volatile private var session: TranslateSession? = null
    @Volatile private var speaker: VoiceReplySpeaker? = null
    @Volatile private var busy = false

    /** 最近一条原文/译文，供 UI 或状态查询显示。 */
    @Volatile var lastHeard: String = ""
    @Volatile var lastTranslated: String = ""

    val isRunning: Boolean get() = session != null

    fun start(ctx: Context) {
        if (session != null) return
        val app = ctx.applicationContext
        appCtx = app
        if (speaker == null) speaker = VoiceReplySpeaker(app)
        val recorder = QwenAsrClient(File(app.filesDir, "qwen_asr_key.txt"))
        session = TranslateSession(app) { file ->
            if (busy) return@TranslateSession
            busy = true
            scope.launch {
                try {
                    translateTurn(app, recorder, file)
                } finally {
                    busy = false
                    runCatching { file.delete() }
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        session?.stop()
        session = null
        speaker?.stop()
        busy = false
    }

    private suspend fun translateTurn(app: Context, recorder: QwenAsrClient, wav: File) {
        val heard = recorder.transcribe(wav).getOrNull()?.trim().orEmpty()
        if (heard.length < 2) return
        lastHeard = heard
        val toEnglish = Regex("[\\u4e00-\\u9fff]").containsMatchIn(heard)
        val target = if (toEnglish) "English" else "Simplified Chinese"
        val translated = translate(heard, target) ?: return
        lastTranslated = translated
        session?.pause()
        speaker?.submit(translated)
        // 等念完再恢复收音：半双工，外放的译文绝不会被自己听成「有人说话」
        speaker?.awaitIdle()
        session?.resume()
    }

    private fun translate(text: String, target: String): String? {
        val app = appCtx ?: return null
        val keyFile = File(app.filesDir, "deepseek_key.txt")
        val key = runCatching { keyFile.readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) return null
        val body = JSONObject()
            .put("model", "deepseek-flash")
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", "Translate into $target. Output only the translation, no notes: " + text)))
            .put("max_tokens", 400)
            .put("reasoning_effort", "none")
            .toString()
        val req = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer " + key)
            .post(body.toRequestBody(JSON))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use null
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
