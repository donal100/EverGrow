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
    private val recorder = UtteranceRecorder(context.filesDir)
    private val queue = ArrayBlockingQueue<ByteArray>(64)

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

    private fun enqueue(buf: ShortArray, n: Int) {
        if (!active) return
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

    private fun openSession() {
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
            log("error=" + error)
            // 任何错误都只做一件事：重开一条会话。没有别的分支。
            if (active) main.postDelayed({ if (active) openSession() }, 300)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) currentPartial = t
        }

        override fun onResults(results: Bundle?) {
            val t = first(results).ifEmpty { currentPartial }
            currentPartial = ""
            if (t.length >= 2 && t != lastFinal) {
                lastFinal = t
                log("sentence: " + t.take(70))
                onSentence(t)
            }
            // 关键：一条会话只交付一句话，随后就自己关掉了。
            // 之前没有重开，于是「第二句永远听不到」——日志里只有第一次的 session open。
            if (active) main.postDelayed({ if (active) openSession() }, 60)
        }

        private fun first(b: Bundle?): String = runCatching {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        }.getOrDefault("")
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

    private fun log(msg: String) {
        runCatching {
            val f = File(context.filesDir, "call_mode.log")
            if (f.length() > 400_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }
}
