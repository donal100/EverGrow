package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 听写引擎。规则只有一条：麦克风开着，就一直转写。
 *
 * 之前这里堆了 pause/resume、看门狗、重启、锁 —— 每一个都在引入新的故障形态
 * （聋掉、卡死、error=2）。现在全部删掉：
 *
 *   AudioRecord ──► 队列 ──► 写线程 ──► 管道 ──► SpeechRecognizer
 *
 * 不再暂停：播报期间照样转写，靠文本比对把「自己说的话」丢掉（回声由上层过滤）。
 * 会话死了（onError）就重新开一条，没有别的状态机。
 */
class ListenEngine(
    private val context: Context,
    private val onSentence: (String) -> Unit,
) {

    private companion object {
        /** 会话重开空档里最多攒多少块（约 1 秒），超了丢最旧的。 */
        const val MAX_BACKLOG_BLOCKS = 16
    }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = UtteranceRecorder(context.filesDir)
    private val queue = ArrayBlockingQueue<ByteArray>(64)

    /**
     * 用户真的说了话的回调（带已识别的文字）。
     * 用文字而不是「检测到声音」：后者会被咳嗽、呼吸、自己上一句的尾音触发，
     * 实测会把刚开始播报的回答立刻掐掉。
     */
    @Volatile var onSpeechStart: ((String) -> Unit)? = null

    /** 播报期间静音：不把样本送进识别器（外放时这是最干净的做法）。 */
    @Volatile var muted = false

    @Volatile private var active = false
    @Volatile private var out: FileOutputStream? = null
    private var recognizer: SpeechRecognizer? = null
    private var pfdRead: ParcelFileDescriptor? = null
    private var pfdWrite: ParcelFileDescriptor? = null
    private var writer: Thread? = null
    // 噪声闸门的计时：只用于观察，默认不改变任何交付行为
    private var speechBeginAt = 0L
    private var speechEndAt = 0L
    private var currentPartial = ""
    private var lastFinal = ""

    fun start() {
        if (active) return
        active = true
        recorder.onSamples = { buf, n -> enqueue(buf, n) }
        recorder.onVoiceActivity = null
        // 实测这副蓝牙耳机的麦克风拿不到（系统录音机同样如此），
        // 所以不折腾 SCO：直接用手机麦克风，输出保持 A2DP 的高音质。
        recorder.preferredInput = null
        recorder.start()
        startWriter()
        main.post { openSession() }
    }

    fun stop() {
        active = false
        recorder.onSamples = null
        recorder.stop()
        writer?.interrupt()
        writer = null
        queue.clear()
        main.post { closeSession() }
    }

    /**
     * 蓝牙耳机的麦克风只走 SCO（通话通道），A2DP 只负责放音。
     * 所以要用耳机麦，必须把设备切进通信模式并启动 SCO —— 代价是输出也变成通话音质。
     * 有线耳机不需要这一步，系统会直接用它的麦克风。
     */
    private fun enqueue(buf: ShortArray, n: Int) {
        if (!active || muted) return
        val bytes = ByteArray(n * 2)
        var p = 0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            bytes[p++] = (v and 0xFF).toByte()
            bytes[p++] = ((v shr 8) and 0xFF).toByte()
        }
        // 会话重开的空档里照样攒着：这些样本正是下一句话的开头。
        // 之前 closeSession 里的 queue.clear() 把它们统一删掉，于是每次重开都吃掉
        // 头几百毫秒 —— 实测「Do you think I should try」整段没进识别器。
        // 攒太多就丢最旧的：断线久了不能把几秒前的旧声音当成新发言。
        if (out == null) {
            while (queue.size >= MAX_BACKLOG_BLOCKS) queue.poll()
        }
        queue.offer(bytes)   // 满了就丢，绝不阻塞
    }

    private fun startWriter() {
        if (writer != null) return
        writer = Thread {
            while (active) {
                // 先看有没有打开的管道，再取样本。顺序反了就等于把攒下的 pre-roll
                // 直接吃掉：poll 已经从队列里拿走那块音频，随后 out 为 null 就丢掉 ——
                // 实测 backlog 永远是 0，pre-roll 一条都没冲到。
                val stream = out
                if (stream == null) {
                    runCatching { Thread.sleep(20) }
                    continue
                }
                val bytes = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { stream.write(bytes); stream.flush() }
                    .onFailure {
                        log("writer: " + it.message)
                        // 写失败 = 这条管道已经死了（日志里就是 "Stream Closed"）。
                        // 会话重开的 350 毫秒里 out 还指着旧管道，音频全被写进死流里，
                        // 于是 backlog 永远为 0。把这块放回队列，等新管道开起来再冲。
                        out = null
                        queue.offer(bytes)
                    }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Volatile private var opening = false

    private fun openSession() {
        if (!active) return
        // 防重叠：上一次会话可能还在收尾，立刻再开会让识别器报 error=11（server disconnected）
        if (opening) return
        opening = true
        try { doOpen() } finally { opening = false }
    }

    private fun doOpen() {
        if (!active) return
        runCatching {
            closeSession()
            val pipe = ParcelFileDescriptor.createPipe()
            pfdRead = pipe[0]
            pfdWrite = pipe[1]
            out = FileOutputStream(pipe[1].fileDescriptor)
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfdRead)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                }
            )
            recognizer = r
            // backlog = 重开空档里攒下的块数；>0 就证明 pre-roll 生效了
            log("session open backlog=" + queue.size)
        }.onFailure { log("open failed: " + it.message) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            speechBeginAt = System.currentTimeMillis()
            log("speech begins")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            speechEndAt = System.currentTimeMillis()
            log("speech ends")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            log("error=" + error)
            // 任何错误都只做一件事：重开一条会话。没有别的分支。
            if (active) main.postDelayed({ if (active) openSession() }, 600)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) currentPartial = t
        }

        override fun onResults(results: Bundle?) {
            val t = first(results).ifEmpty { currentPartial }
            currentPartial = ""
            // 打断用整句判断：partial 只有半个词，麦克风听见自己也会凑出一两个词，
            // 拿它当「用户在说话」会把回答掐掉（实测一个 "here" 就停了）。
            if (t.length >= 3) runCatching { onSpeechStart?.invoke(t) }
            if (t.length >= 2 && t != lastFinal) {
                lastFinal = t
                log("sentence: " + t.take(70))
                // 噪声闸门：先只观察。默认 gate_enforce=0 —— 交付路径与以前完全一致，
                // 只有把 gate_enforce=1 写进 voice_tuning.txt 才会真的丢弃。
                if (noteGate(t)) {
                    log("gate: dropped")
                } else {
                    onSentence(t)
                }
            }
            // 关键：一条会话只交付一句话，随后就自己关掉了。
            // 之前没有重开，于是「第二句永远听不到」——日志里只有第一次的 session open。
            if (active) main.postDelayed({ if (active) openSession() }, 350)
        }

        private fun first(b: Bundle?): String = runCatching {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun closeSession() {
        runCatching { out?.close() }
        out = null
        // 不清队列：重开空档里攒下的就是下一句的开头，交给 writer 冲进新管道。
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { pfdRead?.close() }
        runCatching { pfdWrite?.close() }
        pfdRead = null
        pfdWrite = null
    }

    /**
     * 判断一条识别结果是否像噪声（默认只看不丢）。
     * 判据只基于识别器自己给出的时间戳和文字，不碰音频链路、不碰会话生命周期：
     *   - 语音段太短（默认 < 400 ms）：白噪声、咳嗽、椅子响
     *   - 词太少（< 2 词且 < 5 字）：一段杂音被凑成一个词
     * 单字回复（yes/no/ok/stop/hey）在白名单，不会被短判据误杀。
     * 只有 voice_tuning.txt 里 gate_enforce=1 时才真的丢弃。
     */
    private fun noteGate(text: String): Boolean {
        val begin = speechBeginAt
        val end = speechEndAt
        val dur = if (begin > 0L && end > begin) end - begin else -1L
        val trim = text.trim()
        val words = if (trim.isEmpty()) 0 else trim.split(Regex("\\s+")).size
        val shortReply = trim.lowercase() in setOf("yes", "no", "ok", "okay", "stop", "hey")
        val tooShort = dur in 0..minGateMs()
        val tooFew = words < minGateWords() && trim.length < 5 && !shortReply
        val suspect = tooShort || tooFew
        log("gate: dur=" + dur + "ms words=" + words + " suspect=" + suspect + " text=" + trim.take(30))
        return suspect && gateEnforce()
    }

    private fun tuningValue(key: String): String? = runCatching {
        val f = File(context.filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching null
        f.readLines().firstOrNull { it.trim().startsWith("$key=") }?.substringAfter("=")?.trim()
    }.getOrNull()

    private fun gateEnforce(): Boolean = tuningValue("gate_enforce") == "1"

    private fun minGateMs(): Long = tuningValue("gate_min_ms")?.toLongOrNull() ?: 400L

    private fun minGateWords(): Int = tuningValue("gate_min_words")?.toIntOrNull() ?: 2

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }
}
