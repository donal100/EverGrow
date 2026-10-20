package com.niki914.zafiro.app.translate

import android.content.Context
import com.niki914.logging.Logger
import android.util.Base64
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
 * 口译。两种模式：
 *
 *  - "two-way"：说中文出英文、说英文出中文。方向靠转写文本的字符集判断，
 *    走「ASR + 翻译」两步（实测 1.1 + 0.6 ≈ 1.7s）。
 *  - "to-english" / "to-chinese"：方向锁死，走 qwen3-livetranslate-flash
 *    单次调用（音频直接出译文，实测 ≈1.2s）。锁定模式下把声音路由到耳机、
 *    麦克风坚持用手机自带的 —— 免提时喇叭不进麦克风，回声问题从根上消失。
 *
 * 为什么锁定模式不用两步：目标语言已知，一次调用就够，少一跳网络。
 * 为什么双向不能单次：target_lang 不支持 auto，不知道对方说什么就无法预先指定。
 */
object Translator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    private const val LOG_TAG = "niki914_zafiro_Translator"
    private const val BASE = "https://maas.qwencloudapi.com"
    private const val TRANSLATE_MODEL = "qwen3-livetranslate-flash"

    @Volatile private var appCtx: Context? = null
    @Volatile private var session: TranslateSession? = null
    @Volatile private var speaker: VoiceReplySpeaker? = null
    @Volatile private var busy = false
    @Volatile private var realtime: RealtimeTranslator? = null

    /** 每次 startRealtime 递增；旧的 socket 回调据此丢弃，避免覆盖新会话状态。 */
    @Volatile private var generation = 0

    @Volatile var state: String = "idle"
    @Volatile private var direction: String = "two-way"
    @Volatile private var routing: String = "speaker"

    @Volatile var lastHeard: String = ""
    @Volatile var lastTranslated: String = ""

    val isRunning: Boolean get() = session != null || realtime != null
    val mode: String get() = direction
    val audioRoute: String get() = routing

    fun start(ctx: Context, dir: String = "two-way") {
        if (session != null) return
        val app = ctx.applicationContext
        appCtx = app
        direction = dir
        if (speaker == null) speaker = VoiceReplySpeaker(app)
        routing = if (dir == "two-way") "speaker" else AudioRouter.routeToEarphones(app)
        val asr = QwenAsrClient(File(app.filesDir, "qwen_asr_key.txt"))
        session = TranslateSession(app) { file ->
            if (busy) return@TranslateSession
            busy = true
            scope.launch {
                try {
                    translateTurn(asr, file)
                } finally {
                    busy = false
                    runCatching { file.delete() }
                }
            }
        }.also { it.start() }
    }

    /**
     * 实时引擎：一条 WebSocket，边听边译边说，延迟 ≈1.2s 起。
     * 目标语言锁定（en / zh），输出走耳机。
     */
    fun startRealtime(ctx: Context, mode: String) {
        if (realtime != null) return
        val app = ctx.applicationContext
        appCtx = app
        direction = mode
        // 双向走外放（两个人都要听见），单向走耳机
        val twoWay = mode == "two-way"
        routing = if (twoWay) "speaker" else AudioRouter.routeToEarphones(app)
        val instructions = when (mode) {
            "to-chinese" ->
                "You are a real-time interpreter. Whatever you hear, translate it into Simplified Chinese and speak only the translation, briefly and naturally."
            "two-way" ->
                "You are a two-way interpreter between two people. If you hear Chinese, translate it into English. If you hear English, translate it into Simplified Chinese. Speak only the translation, briefly and naturally."
            else ->
                "You are a real-time interpreter. Whatever you hear, translate it into English and speak only the translation, briefly and naturally."
        }
        state = "connecting"
        val gen = ++generation
        // 单人模式（to-english / to-chinese）只要字幕：文字比语音稳，也不会跟你说话抢麦。
        val textOnly = !twoWay
        Logger.i(LOG_TAG, "startRealtime mode=" + mode + " route=" + routing + " textOnly=" + textOnly)
        realtime = RealtimeTranslator(
            app,
            instructions,
            textOnly = textOnly,
            halfDuplex = twoWay,
            onTranscript = { text ->
                lastTranslated = text
                Logger.i(LOG_TAG, "translated: " + text.take(80))
            },
            // 旧 socket 的回调会晚到（stop() 里的 close(1000) 就是典型），把新会话的 "ready"
            // 覆盖成 "closed 1000"，看上去就像根本没开起来。只认当前这一代。
            onState = { s ->
                if (gen == generation) {
                    state = s
                    Logger.i(LOG_TAG, "state=" + s)
                }
            },
        ).also { it.start() }
    }

    fun stopRealtime() {
        generation++          // 让旧 socket 迟到的回调写不进来
        realtime?.stop()
        realtime = null
        appCtx?.let { AudioRouter.reset(it) }
        routing = "speaker"
        state = "idle"
    }

    fun stop() {
        stopRealtime()
        session?.stop()
        session = null
        speaker?.stop()
        appCtx?.let { AudioRouter.reset(it) }
        routing = "speaker"
        busy = false
    }

    private suspend fun translateTurn(asr: QwenAsrClient, wav: File) {
        val translated = when (direction) {
            "to-english" -> single(b64(wav), "en")
            "to-chinese" -> single(b64(wav), "zh")
            else -> twoStage(asr, wav)
        } ?: return
        lastTranslated = translated
        session?.pause()
        speaker?.submit(translated)
        speaker?.awaitIdle()
        session?.resume()
    }

    /** 锁定方向：音频直接进翻译模型，一次网络往返。 */
    private fun single(audioB64: String, target: String): String? {
        val key = key() ?: return null
        val body = JSONObject()
            .put("model", TRANSLATE_MODEL)
            .put("translation_options", JSONObject().put("source_lang", "auto").put("target_lang", target))
            .put("max_tokens", 600)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                JSONArray().put(JSONObject().put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", "data:audio/wav;base64," + audioB64))))))
            .toString()
        val req = Request.Builder().url(BASE + "/compatible-mode/v1/chat/completions")
            .addHeader("Authorization", "Bearer " + key)
            .post(body.toRequestBody(JSON)).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use null
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** 双向：先转写判断语言，再翻译。 */
    private suspend fun twoStage(asr: QwenAsrClient, wav: File): String? {
        val heard = asr.transcribe(wav).getOrNull()?.trim().orEmpty()
        if (heard.length < 2) return null
        lastHeard = heard
        val toEnglish = Regex("[\\u4e00-\\u9fff]").containsMatchIn(heard)
        return translateText(heard, if (toEnglish) "English" else "Simplified Chinese")
    }

    private fun translateText(text: String, target: String): String? {
        val app = appCtx ?: return null
        val keyFile = File(app.filesDir, "deepseek_key.txt")
        val key = runCatching { keyFile.readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) return null
        val body = JSONObject()
            .put("model", "deepseek-flash")
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "Translate into $target. Output only the translation, no notes: " + text)))
            .put("max_tokens", 400)
            .put("reasoning_effort", "none")
            .toString()
        val req = Request.Builder().url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer " + key)
            .post(body.toRequestBody(JSON)).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use null
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private fun b64(f: File): String =
        Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)

    private fun key(): String? {
        val app = appCtx ?: return null
        return runCatching { File(app.filesDir, "qwen_asr_key.txt").readText().trim() }.getOrNull()
    }
}
