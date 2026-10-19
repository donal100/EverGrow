package com.niki914.zafiro.app.audio

import android.content.Context
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

/**
 * 听写引擎 —— 在线的、免费的、流式的。
 *
 * 关键发现（实测，2026-10）：Android 的 SpeechRecognizer 其实可以「吃音频文件」——
 * 但必须同时满足三条，缺一条就只会拿到空字符串：
 *   1. 用管道（ParcelFileDescriptor.createPipe）实时喂，不能传一个已经录好的文件；
 *   2. 带上 EXTRA_SEGMENTED_SESSION，否则结果从 onResults 里拿不到；
 *   3. 文本只在 onPartialResults 里逐步出现。
 * 三条都对上之后：说话结束 50ms 后就有最终文本（5.61s 结束 → 5.66s 出结果），
 * 比 Qwen ASR 的 ~1.1s 快，而且免费离线。
 *
 * 所以这里不做断句判断：麦克风开着就一直转写，句子边界由识别器自己给。
 */
class ListenEngine(
    private val context: Context,
    private val onSentence: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val recorder = UtteranceRecorder(context.filesDir)
    private var recognizer: SpeechRecognizer? = null
    private var out: FileOutputStream? = null
    private var pfdRead: ParcelFileDescriptor? = null
    private var pfdWrite: ParcelFileDescriptor? = null

    private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(64)
    private var writer: Thread? = null

    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var currentPartial = ""

    fun start() {
        if (active) return
        active = true
        recorder.onSamples = { buf, n -> feed(buf, n) }
        recorder.onVoiceActivity = null
        recorder.start()
        startWriter()
        main.post { openSession() }
    }

    private fun openSession() {
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            pfdRead = pipe[0]
            pfdWrite = pipe[1]
            out = FileOutputStream(pfdWrite!!.fileDescriptor)
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfdRead)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            }
            r.startListening(intent)
            recognizer = r
            log("session open")
        }.onFailure { log("session failed: " + it.message) }
    }

    /**
     * 把麦克风样本丢进队列，由专门的写线程送进管道。
     *
     * 为什么不在麦克风线程里直接写：管道满时 write() 会一直阻塞，
     * 而关闭会话要拿同一把锁 —— 主线程就被卡住，表现就是「应用卡死」。
     * 现在写不进去就丢样本（宁可丢几十毫秒音频，也不能卡住界面）。
     */
    private fun feed(buf: ShortArray, n: Int) {
        if (!active || paused) return
        val bytes = ByteArray(n * 2)
        var p = 0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            bytes[p++] = (v and 0xFF).toByte()
            bytes[p++] = ((v shr 8) and 0xFF).toByte()
        }
        // 队列有界：满了说明识别器跟不上，丢新样本即可
        queue.offer(bytes)
    }

    private fun startWriter() {
        if (writer != null) return
        writer = Thread {
            while (active) {
                val bytes = runCatching { queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                    ?: continue
                val stream = out ?: continue
                runCatching {
                    stream.write(bytes)
                    stream.flush()
                }.onFailure { log("writer: " + it.message) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { log("speech begins") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { log("speech ends") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            log("error=" + error)
            // 6/7 只是「这一段没听到内容」；管道还开着，继续喂即可
            if (error == 5) restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = first(partialResults)
            if (text.isNotEmpty()) currentPartial = text
        }

        override fun onResults(results: Bundle?) {
            val text = first(results).ifEmpty { currentPartial }
            currentPartial = ""
            if (text.length >= 2) {
                log("sentence: " + text.take(70))
                main.post { onSentence(text) }
            }
        }

        private fun first(bundle: Bundle?): String = runCatching {
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun restart() {
        closeSession()
        if (active) main.postDelayed({ if (active) openSession() }, 250)
    }

    fun pause() {
        paused = true
        // 看门狗：播报失败时（请求卡住、引擎异常）没人会来调 resume，
        // 麦克风就永远静音 —— 实测出现过「不说话了，也不再听」。
        // 12 秒还没恢复就自己解开，宁可多听一句，不要变成聋子。
        main.removeCallbacks(resumeGuard)
        main.postDelayed(resumeGuard, 6_000)
    }

    fun resume() {
        main.removeCallbacks(resumeGuard)
        paused = false
        currentPartial = ""
        // 会话可能已经因为写管道失败而关掉了：恢复时顺手确认它还活着，
        // 否则会出现「麦克风开着、识别器却不存在」——听不见任何东西。
        if (active && recognizer == null) {
            log("resume: no session, reopening")
            main.post { openSession() }
        }
    }

    private val resumeGuard = Runnable {
        if (paused) {
            paused = false
            currentPartial = ""
            log("watchdog: resumed listening after stuck pause")
        }
    }

    fun stop() {
        active = false
        recorder.onSamples = null
        recorder.stop()
        // 先让写线程退出，再关管道：否则它会继续往已经关掉的流里写
        writer?.interrupt()
        writer = null
        queue.clear()
        closeSession()
    }

    private fun closeSession() {
        runCatching { out?.close() }
        out = null
        runCatching { pfdWrite?.close() }
        pfdWrite = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { pfdRead?.close() }
        runCatching { pfdWrite?.close() }
        pfdRead = null
        pfdWrite = null
    }

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }
}
