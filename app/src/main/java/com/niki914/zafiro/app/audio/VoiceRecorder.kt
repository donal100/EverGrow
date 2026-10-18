package com.niki914.zafiro.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * 单声道 AAC 录音，产物落在应用私有目录 filesDir/recordings/。
 *
 * 当前阶段只负责「把声音记下来」：不转写、不上传、不进草稿。
 * 选 MediaRecorder 而非 AudioRecord，是因为这里要的是一个能直接播放的文件，不是 PCM 流。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /** 开始录音，返回将要写入的文件。失败时抛异常，由调用方决定怎么提示。 */
    @Suppress("DEPRECATION")
    fun start(): File {
        release()
        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val file = File(dir, "rec_" + System.currentTimeMillis() + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(96000)
        r.setAudioChannels(1)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        currentFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        return file
    }

    /**
     * 停止并返回 (文件, 毫秒)。
     *
     * 太短（< [MIN_DURATION_MS]）或 stop() 失败都返回 null，并把残file删掉：
     * MediaRecorder 在头几帧尚未写盘时 stop() 会抛 RuntimeException，这不是异常路径而是正常边界，
     * 若留下 0 字节文件，用户会以为录音成功了。
     */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val file = currentFile
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        recorder = null
        currentFile = null
        var stopped = false
        return try {
            r.stop()
            stopped = true
            if (elapsed < MIN_DURATION_MS) {
                file?.delete()
                null
            } else {
                file?.takeIf { it.exists() && it.length() > 0 }?.let { it to elapsed }
            }
        } catch (t: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
            if (!stopped) file?.delete()
        }
    }

    /** 放弃当前录音并清理。 */
    fun cancel() {
        val r = recorder ?: return
        val file = currentFile
        recorder = null
        currentFile = null
        try {
            r.stop()
        } catch (t: RuntimeException) {
            // 头几帧不足，忽略
        } finally {
            r.release()
            file?.delete()
        }
    }

    /** 离开界面时调用，避免 MediaRecorder 泄漏占着麦克风。 */
    fun release() {
        cancel()
    }

    companion object {
        private const val RECORDINGS_DIR = "recordings"
        private const val MIN_DURATION_MS = 300L
    }
}
