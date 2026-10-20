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

    private val main = Handler(Looper.getMainLooper())

    /**
     * 看门狗：active 为真却没有会话，就重新开一条。
     * 实测切前后台回来后可能一条 session open 都没有（日志里 20:12:14 onResume 之后
     * 直到 20:14 全是空的），此时没有任何人会发现它已经聋了。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (recognizer == null && !opening) {
                log("watchdog: no session, reopening")
                openSession()
            }
            main.postDelayed(this, 8_000)
        }
    }
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

    /** 端上识别器是否还能用：报 12/13（语言不支持/不可用）后就永久退回网络识别器。 */
    @Volatile private var preferOnDevice = true
    /** 连续失败次数，用来做退避：0.6s 起翻倍，最长 8s。 */
    @Volatile private var consecutiveErrors = 0

    @Volatile private var active = false
    @Volatile private var out: FileOutputStream? = null
    private var recognizer: SpeechRecognizer? = null
    private var pfdRead: ParcelFileDescriptor? = null
    private var pfdWrite: ParcelFileDescriptor? = null
    private var writer: Thread? = null
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
        main.postDelayed(watchdog, 8_000)
    }

    fun stop() {
        active = false
        recorder.onSamples = null
        recorder.stop()
        writer?.interrupt()
        writer = null
        queue.clear()
        main.removeCallbacks(watchdog)
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
        queue.offer(bytes)   // 满了就丢，绝不阻塞
    }

    private fun startWriter() {
        if (writer != null) return
        writer = Thread {
            while (active) {
                val bytes = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                val stream = out ?: continue
                runCatching { stream.write(bytes); stream.flush() }
                    .onFailure { log("writer: " + it.message) }
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
            // 每句话一个全新的识别器 —— 这是唯一被验证跑通过的形态：
            // 昨天用 Python 直接把管道喂给识别器，feed+segmented+partial 一次就出文字；
            // 而复用同一个识别器 startListening 第二次会报 error=5（client）。
            // 代价是每句建一次对象（几十毫秒），换来的是不会卡死。
            closeSession()
            val pipe = ParcelFileDescriptor.createPipe()
            pfdRead = pipe[0]
            pfdWrite = pipe[1]
            out = FileOutputStream(pipe[1].fileDescriptor)
            val onDevice = preferOnDevice && onDeviceEnabled() &&
                android.os.Build.VERSION.SDK_INT >= 31 &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context)
            log("recognizer=" + (if (onDevice) "on-device" else "network"))
            r.setRecognitionListener(listener)
            recognizer = r
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // 语言可配：filesDir/voice_tuning.txt 里写 listen_lang=zh-HK 即可改。
                    // 写 listen_lang= 空（或 auto）就不传语言，交回系统默认 —— 实测
                    // 这台设备上 en-US 会报 error=13（language_unavailable）。
                    val lang = listenLang()
                    if (lang.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfdRead)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                }
            )
            recognizer = r
            log("session open")
        }.onFailure { log("open failed: " + it.message) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { log("speech begins") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { log("speech ends") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            // 错误码写成人话：不写的话日志里只有数字，得靠猜
            val name = when (error) {
                1 -> "network_timeout"; 2 -> "network"; 3 -> "audio"; 4 -> "server"
                5 -> "client"; 6 -> "speech_timeout"; 7 -> "no_match"; 8 -> "busy"
                9 -> "no_permission"; 10 -> "too_many_requests"; 11 -> "server_disconnected"
                12 -> "language_not_supported"; 13 -> "language_unavailable"
                else -> "unknown"
            }
            consecutiveErrors++
            log("error=" + error + " (" + name + ") retry=" + consecutiveErrors +
                " onDevice=" + preferOnDevice)
            // 端上模型不支持这个语言时，硬撑只会一直重开：永久退回网络识别器
            if (preferOnDevice && (error == 12 || error == 13)) {
                preferOnDevice = false
                log("on-device cannot do this language, falling back to network recogniser")
            }
            // 下限 1.2 秒：error=5/11 都是「上一个会话还没收尾」，催得越急越糟
            val delay = (1_200L shl (consecutiveErrors - 1).coerceIn(0, 3)).coerceAtMost(9_000L)
            if (active) main.postDelayed({ if (active) openSession() }, delay)
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
                consecutiveErrors = 0
                lastFinal = t
                log("sentence: " + t.take(70))
                onSentence(t)
            }
            // 关键：一条会话只交付一句话，随后就自己关掉了。
            // 之前没有重开，于是「第二句永远听不到」——日志里只有第一次的 session open。
            // 350ms 太急：实测紧接着重开会让新会话报 error=5（client），
            // 因为老会话的 cancel/destroy 还没走完。给 1.2 秒。
            if (active) main.postDelayed({ if (active) openSession() }, 1_200)
        }

        private fun first(b: Bundle?): String = runCatching {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        }.getOrDefault("")
    }

    /** 只关管道/流，保留识别器对象。 */
    private fun closePipe() {
        runCatching { out?.close() }
        out = null
        queue.clear()
        runCatching { pfdRead?.close() }
        runCatching { pfdWrite?.close() }
        pfdRead = null
        pfdWrite = null
    }

    private fun closeSession() {
        runCatching { out?.close() }
        out = null
        queue.clear()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { pfdRead?.close() }
        runCatching { pfdWrite?.close() }
        pfdRead = null
        pfdWrite = null
    }


    /** 读 voice_tuning.txt 里的 listen_lang=，没配就用 en-US。 */
    private fun listenLang(): String {
        val raw = runCatching {
            File(context.filesDir, "voice_tuning.txt").readText()
        }.getOrDefault("")
        val line = raw.lineSequence().firstOrNull { it.trim().startsWith("listen_lang=") }
            ?: return ""
        val v = line.substringAfter("=").trim()
        if (v == "auto") return ""
        return v
    }


    /** 端上识别器默认开启（昨天就是这么跑通的）；要退回网络识别器就写 listen_ondevice=0。 */
    private fun onDeviceEnabled(): Boolean = runCatching {
        val raw = File(context.filesDir, "voice_tuning.txt").readText()
        !raw.lineSequence().any { it.trim() == "listen_ondevice=0" }
    }.getOrDefault(true)

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }
}
