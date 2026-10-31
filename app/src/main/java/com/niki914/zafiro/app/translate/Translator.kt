package com.niki914.zafiro.app.translate

import android.content.Context
import com.niki914.logging.Logger
import android.util.Base64
import com.niki914.zafiro.app.audio.CantoneseSpeaker
import com.niki914.zafiro.app.audio.QwenAsrClient
import com.niki914.zafiro.app.audio.VoiceReplySpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** 简化字幕引擎（固定 3 秒切片 + omni），单向模式用。 */
    @Volatile private var chunk: ChunkCaptionEngine? = null
    /** 双人字幕：一次请求送两路音频，模型分别标注 A / B。 */
    @Volatile private var duo: ChunkDuoEngine? = null
    @Volatile private var cantoneseSpeaker: CantoneseSpeaker? = null

    /** 每次 startRealtime 递增；旧的 socket 回调据此丢弃，避免覆盖新会话状态。 */
    @Volatile private var generation = 0

    @Volatile var state: String = "idle"
    @Volatile private var direction: String = "two-way"
    @Volatile private var routing: String = "speaker"

    @Volatile var lastHeard: String = ""
    @Volatile var lastTranslated: String = ""

    // ——— 实时字幕（像片尾字幕一样往上滚） ———
    // 每句一行：先是被听见的原话，然后是译文。UI 只读这个列表，不管翻译怎么实现。
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** 上一行字幕：用来吃掉连续重复。 */
    @Volatile private var lastLine: String = ""

    /** 每次加行都加一，UI 靠它知道要不要重绘。 */
    @Volatile var linesVersion: Int = 0
        private set

    /**
     * 远场提示：麦克风常常离声源很远（电视、演讲、别的房间），
     * 声音小、有混响。明确告诉模型「这是远处传来的微弱声音，听清楚再说」，
     * 实测同一段录音加上这句，转录更长也更合理（15:58 的碎片就是这样丢的）。
     */
    private const val FAR_FIELD_HINT =
        " The audio often comes from far away — a television or a speaker across the room — " +
            "so it may be faint and reverberant. Listen carefully, keep each sentence whole, " +
            "and never invent words you did not hear."

    /**
     * 引擎选择：默认 **chunk-duo** —— 双人模式一次请求送两路音频。
     * 实测 omni 能分别听 A / B，两个人同时说话也能各自出字幕，而且没有
     * 服务端 VAD、手动 commit、半双工那一串可以在任何一处出错的环节。
     * 单向字幕仍是 realtime，除非显式写 translate_engine=chunk。
     */
    private fun engineMode(): String = runCatching {
        val app = appCtx ?: return@runCatching "chunk-duo"
        File(app.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("translate_engine=") }
            ?.substringAfter("=")?.trim()?.lowercase() ?: "chunk-duo"
    }.getOrDefault("chunk-duo")

    fun snapshotLines(): List<String> = runCatching { lines.toList() }.getOrDefault(emptyList())

    fun clearLines() {
        runCatching {
            lines.clear()
            linesVersion++
        }
    }

    private fun pushLine(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        // 同一句连着出现就不再加：模型在听不懂的音频上会卡住反复吐同一行
        // （实测电视声里连着六行 "It's not."），那既不是字幕也是噪音。
        if (text == lastLine) return
        // 不再按长度过滤：字幕要和耳机里听到的一字不差，哪怕它只说了一个字。
        runCatching {
            lines.add(text)
            while (lines.size > 400) lines.removeAt(0)
            linesVersion++
        }
        // 字幕同时落盘：屏幕上出现过什么，事后能和录音逐句对照。
        runCatching {
            val app = appCtx ?: return@runCatching
            val dir = File(app.filesDir, "translate_subs").apply { mkdirs() }
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            File(dir, day + ".txt").appendText(stamp + "\t" + text.replace("\n", " ") + "\n")
        }
    }

    val isRunning: Boolean get() = session != null || realtime != null || chunk != null || duo != null
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
        if (realtime != null || chunk != null || duo != null) return
        val app = ctx.applicationContext
        appCtx = app
        direction = mode
        // 双向走外放（两个人都要听见），单向走耳机
        val twoWay = mode == "two-way" || mode == "two-way-yue"
        routing = if (twoWay) "speaker" else AudioRouter.routeToEarphones(app)
        // 单人字幕只有一种：听什么都译成英文。
        // 源语言不用我们指定 —— 模型自己听得出来（实测输入转写直接给粤字）；
        // 之前分「粤语」「普通话」两套 instruction，只是重复了同一件事。
        // 真正需要区分的只有双向（回话用哪种语言、用哪个音色）。
        val instructions = when (mode) {
            "to-chinese" ->
                "You are a real-time interpreter. Whatever you hear, translate it into Simplified Chinese and output the translation only, briefly and naturally."
            "two-way" ->
                "You are a two-way interpreter between two people, Mandarin and English. If you hear Mandarin, translate it into English. If you hear English, translate it into Simplified Chinese. Say only the translation, briefly and naturally, with no commentary. If the audio is noise, echo, music or unclear speech, output nothing at all."
            "two-way-yue" ->
                "You are a two-way interpreter between two people, Cantonese and English. If you hear Cantonese, translate it into natural English. If you hear English, translate it into spoken Cantonese as used in Hong Kong, not Standard Written Chinese. Say only the translation, briefly and naturally, with no commentary. If the audio is noise, echo, music or unclear speech, output nothing at all."
            else ->
                // to-english / yue-en 共用这一段：源语言交给模型判断。
                "You are a real-time interpreter. Whatever language you hear — Cantonese, Mandarin or anything else — translate it into natural English and output English text only. " +
                    "The speaker may mix in English words or switch languages mid-sentence: still translate the whole utterance into English, keeping names and technical terms as they are. " +
                    "Ignore filler and false starts; do not invent words you did not hear."
        }
        state = "connecting"
        clearLines()
        val gen = ++generation
        // 两种模式彻底分开：
        //   双人（two-way / two-way-yue）出声 —— 面对面说话时没人愿意盯着屏幕读，
        //     粤语尤其如此，听到才是那个味道；回声问题靠 half-duplex + 播放队列排空解决。
        //   单人（to-english / yue-en）出字幕 —— 讲座/看片时只需要文字。
        // 粤语：实时会话的音色表里根本没有粤语音色（传 Rocky 会被拒：
        //   Unsupported voice: 'Rocky'. Supported voices: longanqian, ...
        // ），所以粤语改成「会话只要文字 + 本地用 Rocky 合成播放」。
        val cantonese = mode == "two-way-yue"
        val textOnly = !twoWay || cantonese
        // 音色只能在实时会话自己那组里挑。Serena 是 qwen3-tts-flash 的音色，
        // 传给实时会话会被整条拒掉：「Unsupported voice: 'Serena'」，session 永远不 ready，
        // 麦克风也就不开 —— 看上去就是「普通话翻译完全没反应」。
        val voice = "longanqian"
        // 左右分声道：只有「普通话↔英文、双方都出声」这条路才有意义 ——
        // 粤语是本地合成播的，走的是另一个播放器；单人模式本来就只出字幕。
        // 没插耳机就不分：外放时左右声道本来也分不开。
        val earphones = AudioRouter.earphones(app)
        // 运行时开关（files/voice_tuning.txt）：translate_pan=off 关掉分声道，=both 两侧都满音量。
        // 没插耳机就不分：外放时左右声道本来也分不开。
        val panCfg = runCatching {
            File(ctx.filesDir, "voice_tuning.txt").readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("translate_pan=") }
                ?.substringAfter("=")?.trim()
        }.getOrNull()
        // 只有真正的立体声输出才分声道：SCO 是单声道，分了个寂寞。
        val panSplit = panCfg != "off" && twoWay && !cantonese && AudioRouter.isStereoOut(earphones)
        val bleed = if (panCfg == "both") 1.0f else 0.12f
        Logger.i(
            LOG_TAG,
            "startRealtime mode=" + mode + " route=" + routing + " textOnly=" + textOnly +
                " voice=" + voice + " panSplit=" + panSplit +
                " earType=" + (earphones?.type ?: -1) + " earName=" + (earphones?.productName ?: "none") +
                " panCfg=" + panCfg,
        )
        // 单向字幕走简化引擎：固定 3 秒切片直接问 omni。
        // 实时会话那套要做服务端 VAD、手动 commit、半双工、双麦仲裁，
        // 任何一处错都表现成「听不见」；这里没有那些层次。
        // 双人字幕：两路音频一起送，模型分别给 A / B 的译文。
        // 路由是确定的：A 的话进 B 的耳朵，B 的话进 A 的耳朵。
        if (twoWay && engineMode() == "chunk-duo") {
            val g = gen
            // 语言跟着菜单走：two-way 是普通话↔英文，two-way-yue 才是粤语↔英文。
            val duoChinese = if (mode == "two-way-yue") "Cantonese" else "Mandarin"
            duo = ChunkDuoEngine(
                app,
                "Translate what each person says into the other person's language: "
                    + "A speaks " + duoChinese + ", B speaks English. Output English for A's lines "
                    + "and " + duoChinese + " (written Chinese" +
                    (if (mode == "two-way-yue") ", as used in Hong Kong" else "") + ") for B's lines.",
                chineseVoice = if (mode == "two-way-yue") "Rocky" else "Serena",
                onLine = { line ->
                    if (g == generation) {
                        lastTranslated = line
                        pushLine(line)
                        Logger.i(LOG_TAG, "duo: " + line.take(80))
                    }
                },
                onState = { st ->
                    if (g == generation) {
                        state = st
                        Logger.i(LOG_TAG, "state=" + st)
                    }
                },
            ).also { it.start() }
            Logger.i(LOG_TAG, "engine=chunk-duo")
            return
        }

        if (!twoWay && engineMode() == "chunk") {
            val g = gen
            chunk = ChunkCaptionEngine(
                app,
                instructions,
                onTranscript = { text ->
                    if (g == generation) {
                        lastTranslated = text
                        pushLine(text)
                        Logger.i(LOG_TAG, "translated: " + text.take(80))
                    }
                },
                onState = { s ->
                    if (g == generation) {
                        state = s
                        Logger.i(LOG_TAG, "state=" + s)
                    }
                },
            ).also { it.start() }
            Logger.i(LOG_TAG, "engine=chunk")
            return
        }

        realtime = RealtimeTranslator(
            app,
            instructions + FAR_FIELD_HINT,
            textOnly = textOnly,
            voice = voice,
            panSplit = panSplit,
            bleed = bleed,
            halfDuplex = twoWay,
            onHeard = { heard ->
                // 只记录下来（日志），不放进字幕：字幕必须是「模型正在说的话」，
                // 掺进「它听到的原话」会让屏幕和声音对不上。
                lastHeard = heard
            },
            onTranscript = { text ->
                lastTranslated = text
                pushLine(text)
                Logger.i(LOG_TAG, "translated: " + text.take(80))
                if (cantonese) {
                    val g = gen
                    scope.launch {
                        setMuted(true)
                        // 看门狗：万一播报卡住，25 秒后强制把麦克风还回去。
                        // 少了这一道，一次卡住就等于「之后再也听不到我」。
                        scope.launch {
                            delay(25_000)
                            if (g == generation) {
                                Logger.w(LOG_TAG, "unmute watchdog fired")
                                setMuted(false)
                            }
                        }
                        try {
                            speakCantoneseMode(app, text)
                        } finally {
                            if (g == generation) setMuted(false)
                        }
                    }
                }
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

    /** 粤语话音（汉字）走 Rocky，英文照常用 Qwen 的英语音色。 */
    private suspend fun speakCantoneseMode(app: Context, text: String) {
        val hasHan = text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
        if (hasHan) {
            val speaker = cantoneseSpeaker ?: CantoneseSpeaker(app).also { cantoneseSpeaker = it }
            withContext(Dispatchers.IO) { speaker.speak(text) }
        } else {
            val sp = speaker ?: VoiceReplySpeaker(app).also { speaker = it }
            sp.submit(text)
            sp.awaitIdle()
        }
    }

    fun setMuted(value: Boolean) {
        realtime?.muted = value
    }

    fun stopRealtime() {
        generation++          // 让旧 socket 迟到的回调写不进来
        realtime?.stop()
        realtime = null
        chunk?.stop()
        chunk = null
        duo?.stop()
        duo = null
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
