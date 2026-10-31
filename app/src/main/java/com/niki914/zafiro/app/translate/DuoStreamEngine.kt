package com.niki914.zafiro.app.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.niki914.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 双人实时口译：**一个人一条会话**。
 *
 * 关键点不是模型多聪明，而是「谁在说话」根本不进模型 —— 两个发射器各自占一个
 * 声道，所以左声道就是 A，右声道就是 B。两路音频各自推给一条独立的 realtime
 * 会话，于是每条会话听到的永远是同一个人。
 *
 * 每条会话只负责一件事：听它该听的那种语言，译成对方的语言；听见别的东西
 * （另一种语言、第二个人、音乐、噪音）一律回答 (skip)。
 *
 * 串音靠两道闸拦：
 *   1. 输入语言：会话 A 必须听出中文。听出拉丁字母 = 隔壁那个人漏进来了，丢弃。
 *   2. 输出语言：会话 A 必须给出英文。给了中文 = 在复读原文，丢弃。
 *
 * 播报：译文用 qwen3-tts-flash 合成（普通话 Serena / 粤语 Rocky），
 * A 的话进右耳（B 听），B 的话进左耳（A 听）。两边各一个播放器，
 * 所以两个人同时说话时互不打断。
 */
class DuoStreamEngine(
    private val ctx: Context,
    /** A 说的语言：Mandarin / Cantonese。 */
    private val chinese: String,
    /** 说中文时的音色：普通话 Serena，粤语 Rocky。 */
    private val chineseVoice: String,
    private val onLine: (String) -> Unit,
    private val onState: (String) -> Unit,
) {

    private companion object {
        const val TAG = "niki914_zafiro_DuoStream"
        const val RATE_OUT = 16_000
        const val CAP_RATE = 48_000
        const val BLEED = 0.12f
        const val TTS_ENDPOINT =
            "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        /** 0 = A（中文那边），1 = B（英文那边）。 */
        const val A = 0
        const val B = 1
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val sessions = arrayOfNulls<RealtimeTranslator>(2)
    /** 每条会话最近一次「听见的语言」判定：true = 听见的正是它该听的那种语言。 */
    private val verdicts = arrayOf(ArrayDeque<Boolean>(), ArrayDeque<Boolean>())
    /** 每个说话人上一次上屏的译文：切片边界常把同一句送来两次。 */
    private val lastLine = arrayOfNulls<String>(2)
    private val speakers = arrayOfNulls<MediaPlayer>(2)
    private val tts = arrayOfNulls<Thread>(2)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "duo-stream-io") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 录音留档：voice_tuning.txt 写 translate_dump=1 才开（48k 立体声太大，落盘是 16k）。 */
    private var dumpOut: java.io.FileOutputStream? = null
    private var dumpFile: File? = null
    private var dumpBytes = 0L
    private var dumpStart = 0L
    private var dumpLog: java.io.FileWriter? = null
    /** 单个文件上限 200MB（≈50 分钟），满了自动换下一个，不悄悄丢。 */
    private val dumpMax = 200L * 1024 * 1024

    private fun tuning(key: String, def: String = ""): String = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith(key + "=") }
            ?.substringAfter("=")?.trim() ?: def
    }.getOrDefault(def)

    /** 默认开：留档的意义就是「事后还能查」，translate_dump=0 才关。 */
    private val dumpOn: Boolean by lazy { tuning("translate_dump", "1") != "0" }

    private val speechOn = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("translate_voice=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    fun start() {
        if (running) return
        running = true
        onState("connecting")
        Logger.i(TAG, "engine=duo-stream chinese=" + chinese + " speech=" + speechOn)
        // 两条会话：一个声道一条。指令里写死「只听这一种语言、别的一律 skip」。
        sessions[A] = RealtimeTranslator(
            ctx,
            instructionsFor(A),
            textOnly = true,
            externalAudio = true,
            halfDuplex = false,
            onTranscript = { text -> accept(A, text) },
            onHeard = { heard -> verdict(A, heard) },
            onState = { s -> Logger.i(TAG, "A state=" + s); if (s == "ready") onState("ready") },
        ).also { it.start() }
        sessions[B] = RealtimeTranslator(
            ctx,
            instructionsFor(B),
            textOnly = true,
            externalAudio = true,
            halfDuplex = false,
            onTranscript = { text -> accept(B, text) },
            onHeard = { heard -> verdict(B, heard) },
            onState = { s -> Logger.i(TAG, "B state=" + s) },
        ).also { it.start() }
        thread = Thread { loop() }.apply { isDaemon = true; name = "duo-stream-mic"; start() }
    }

    fun stop() {
        running = false
        closeDump()
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        sessions.forEach { runCatching { it?.stop() } }
        sessions.fill(null)
        speakers.forEach { runCatching { it?.release() } }
        speakers.fill(null)
        runCatching { io.shutdownNow() }
        onState("stopped")
    }

    /** 每条会话只做一件事：听这一种语言，译成另一种。别的都 skip。 */
    private fun instructionsFor(side: Int): String {
        val tag = if (side == A) "A" else "B"
        val speaks = if (side == A) chinese else "English"
        val target = if (side == A) "English" else chinese
        val targetNote = if (side == A) "" else
            (if (chinese == "Cantonese") ", written as Cantonese is written in Hong Kong"
             else ", in Simplified Chinese characters")
        return "You are the private interpreter for one single person: the speaker wearing " +
            "transmitter $tag. You hear that person ONLY, and that person speaks $speaks ONLY. " +
            "Translate everything they say into natural $target$targetNote. " +
            "Output only the $target translation: never repeat the original words, never " +
            "translate them back, never add commentary or notes. " +
            "If the audio contains any other language, a second voice, background speech from " +
            "someone else, a television, music, noise, echo or speech you cannot make out, " +
            "reply with exactly (skip) and nothing else. " +
            "Never guess words you did not hear."
    }

    /** 输入语言判定：这条会话听到的到底是不是它该听的那种语言。 */
    private fun verdict(side: Int, heard: String) {
        val hasHan = heard.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
        val expected = if (side == A) hasHan else !hasHan
        val q = verdicts[side]
        synchronized(q) {
            q.addLast(expected)
            while (q.size > 4) q.removeFirst()
        }
        Logger.i(TAG, (if (side == A) "A" else "B") + " heard=" + (if (hasHan) "zh" else "en") +
            " ok=" + expected + " " + heard.take(50))
    }

    /** 取走最早的一条判定；没有转写就不拦（宁可多一句，别整段哑掉）。 */
    private fun takeVerdict(side: Int): Boolean? {
        val q = verdicts[side]
        return synchronized(q) { q.removeFirstOrNull() }
    }

    private fun accept(side: Int, raw: String) {
        val text = raw.trim()
        if (!running || text.isEmpty()) return
        val who = if (side == A) "A" else "B"
        if (text.startsWith("(skip", true) || text.equals("skip", true)) return
        val hasHan = text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
        // 输出语言：A 那边只该出英文，B 那边只该出中文。别的都说明它在复读原文。
        if (side == A && hasHan) {
            Logger.i(TAG, "$who skip wrong-output " + text.take(40)); note(side, "skip-wrong-output", text); return
        }
        if (side == B && !hasHan) {
            Logger.i(TAG, "$who skip wrong-output " + text.take(40)); note(side, "skip-wrong-output", text); return
        }
        // 输入语言：听见的不是该听的语言 = 隔壁那个人的声音漏进来了。
        if (takeVerdict(side) == false) {
            Logger.i(TAG, "$who skip crosstalk " + text.take(40)); note(side, "skip-crosstalk", text); return
        }
        if (text == lastLine[side]) {
            Logger.i(TAG, "$who skip repeat " + text.take(40)); note(side, "skip-repeat", text); return
        }
        lastLine[side] = text
        note(side, "ok", text)
        onLine("$who：$text")
        if (speechOn) io.execute { runCatching { say(side, text) } }
    }

    private fun loop() {
        val usb = AudioRouter.usbMicWait(ctx)
        if (usb == null) {
            Logger.w(TAG, "no usb mic: two-person needs the DJI receiver")
            onState("no usb mic")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            CAP_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, CAP_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CAP_RATE * 2),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Logger.w(TAG, "mic create failed")
            onState("mic failed")
            return
        }
        runCatching { rec.setPreferredDevice(usb) }
        record = rec
        runCatching { rec.startRecording() }
        openDump()
        Logger.i(TAG, "capture " + CAP_RATE + "Hz stereo on " + usb.productName)

        val buf = ShortArray(9600)          // 4800 帧 ≈ 100ms @48k
        val left = ShortArray(1600)         // 降采样 3:1 → 16k
        val right = ShortArray(1600)
        var n = 0
        while (running) {
            val read = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
            if (read <= 0) continue
            val frames = read / 2
            val out = frames / 3
            if (out <= 0) continue
            for (i in 0 until out) {
                left[i] = buf[2 * (i * 3)]
                right[i] = buf[2 * (i * 3) + 1]
            }
            // 每条会话只听自己那一路：左 = A，右 = B。
            sessions[A]?.pushPcm(left.copyOf(out))
            sessions[B]?.pushPcm(right.copyOf(out))
            writeDump(left, right, out)
            if (++n % 300 == 0) {
                var pl = 0
                var pr = 0
                for (i in 0 until out) {
                    val l = kotlin.math.abs(left[i].toInt())
                    val r = kotlin.math.abs(right[i].toInt())
                    if (l > pl) pl = l
                    if (r > pr) pr = r
                }
                Logger.i(TAG, "levels L=" + pl + " R=" + pr)
            }
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    // ── 录音留档 ───────────────────────────────────────────────────────────
    // 两个声道原样落盘（16k 立体声，L = A，R = B），旁边配一个同名 txt：
    // 每一行都带相对开录的毫秒数，所以事后可以把「听到什么」和「字幕打了什么」
    // 逐句对上 —— 包括被拦下来的那几句，以及为什么被拦。
    private fun openDump() {
        if (!dumpOn) return
        runCatching {
            val dir = File(ctx.filesDir, "translate_audio").apply { mkdirs() }
            // 只留最近三对文件（≈600MB），否则一次长会就把手机存满。
            runCatching {
                dir.listFiles()?.sortedBy { it.lastModified() }?.let { all ->
                    val keepFrom = maxOf(0, all.size - 5)
                    all.take(keepFrom).forEach { it.delete() }
                }
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val f = File(dir, "duo_" + stamp + ".wav")
            val out = java.io.FileOutputStream(f)
            out.write(ByteArray(44))
            dumpFile = f
            dumpOut = out
            dumpBytes = 0
            dumpStart = System.currentTimeMillis()
            dumpLog = java.io.FileWriter(File(dir, "duo_" + stamp + ".txt"), true)
            Logger.i(TAG, "dump -> " + f.absolutePath)
        }
    }

    private fun writeDump(l: ShortArray, r: ShortArray, n: Int) {
        val out = dumpOut ?: return
        val bytes = ByteArray(n * 4)
        var p = 0
        for (i in 0 until n) {
            val lv = l[i].toInt()
            val rv = r[i].toInt()
            bytes[p++] = (lv and 0xFF).toByte()
            bytes[p++] = ((lv shr 8) and 0xFF).toByte()
            bytes[p++] = (rv and 0xFF).toByte()
            bytes[p++] = ((rv shr 8) and 0xFF).toByte()
        }
        runCatching {
            out.write(bytes)
            dumpBytes += bytes.size
        }
        if (dumpBytes > dumpMax) {
            Logger.w(TAG, "dump reached 200MB, continuing in a new file")
            closeDump()
            openDump()
        }
    }

    /** 字幕侧写：相对开录的毫秒数 + 谁 + 结果（ok / skip 原因）。 */
    private fun note(side: Int, what: String, text: String) {
        val w = dumpLog ?: return
        val ms = if (dumpStart == 0L) 0 else System.currentTimeMillis() - dumpStart
        runCatching {
            w.write(ms.toString() + "\t" + (if (side == A) "A" else "B") + "\t" + what + "\t" +
                text.replace("\n", " ") + "\n")
            w.flush()
        }
    }

    private fun closeDump() {
        val out = dumpOut ?: return
        dumpOut = null
        runCatching { out.close() }
        val f = dumpFile
        if (f != null) runCatching {
            java.io.RandomAccessFile(f, "rw").use { raf ->
                val dataLen = raf.length().toInt() - 44
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
                put32(16, 16); h[20] = 1; h[22] = 2
                put32(24, RATE_OUT); put32(28, RATE_OUT * 4)
                h[32] = 4; h[34] = 16
                "data".toByteArray().copyInto(h, 36)
                put32(40, dataLen)
                raf.seek(0)
                raf.write(h)
            }
            Logger.i(TAG, "dump closed " + f.name + " bytes=" + f.length())
        }
        runCatching { dumpLog?.close() }
        dumpLog = null
        dumpFile = null
    }

    /** 合成一句，播到对面那只耳朵：A 的话进右耳，B 的话进左耳。 */
    private fun say(side: Int, text: String) {
        val key = runCatching { File(ctx.filesDir, "qwen_asr_key.txt").readText().trim() }.getOrNull().orEmpty()
        if (key.isEmpty()) return
        val hasHan = text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF }
        val voice = if (hasHan) chineseVoice else "Serena"
        val lang = if (hasHan) "chinese" else "English"
        val payload = JSONObject()
            .put("model", "qwen3-tts-flash")
            .put("input", JSONObject().put("text", text).put("voice", voice).put("language_type", lang))
        val url = runCatching {
            http.newCall(
                Request.Builder().url(TTS_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "tts http " + resp.code)
                    null
                } else JSONObject(resp.body?.string().orEmpty())
                    .getJSONObject("output").getJSONObject("audio").optString("url").ifEmpty { null }
            }
        }.getOrNull() ?: return
        val audio = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        }.getOrNull() ?: return
        val f = File(ctx.cacheDir, "duo_stream_" + side + "_" + System.currentTimeMillis() + ".mp3")
        runCatching { f.writeBytes(audio) }.getOrElse { return }
        val toRight = side == A
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                AudioRouter.earphones(ctx)?.let { dev -> runCatching { setPreferredDevice(dev) } }
                setDataSource(f.absolutePath)
                prepare()
                // 一侧满音量、另一侧只留底噪：A 的话只有 B 需要听。
                if (toRight) setVolume(BLEED, 1f) else setVolume(1f, BLEED)
            }
        }.getOrNull() ?: return
        // 每一侧一个播放器：两个人同时说话时互不打断。
        runCatching { speakers[side]?.release() }
        speakers[side] = mp
        runCatching { mp.start() }
        Logger.i(TAG, "say " + (if (toRight) "right" else "left") + " voice=" + voice + " " + text.take(30))
        runCatching { f.delete() }
    }
}
