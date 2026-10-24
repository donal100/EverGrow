package com.niki914.zafiro.app.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 让听写在锁屏/后台继续工作的前台服务。
 *
 * 为什么必须有它：麦克风属于「使用期间」权限，界面一进后台，Android 就可能把录音掐掉。
 * 声明 foregroundServiceType="microphone" 的前台服务才能合法地一直录。
 * 代价是常驻通知——这其实是对的，用户应该能看到「现在在听」。
 */
class ListenService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        // 两套耳朵：默认 Qwen（本地 VAD 切句 + 云端识别，没有会静默死掉的会话），
        // 系统识别器保留为回退 —— voice_tuning.txt 里 asr_engine=google 就切回去。
        if (engine == null && qwenEngine == null) {
            if (useQwen()) {
                qwenEngine = QwenListenEngine(this) { text -> ListenBus.onSentence?.invoke(text) }
                    .also { it.start() }
            } else {
                engine = ListenEngine(this) { text -> ListenBus.onSentence?.invoke(text) }.also {
                    it.onSpeechStart = { text -> ListenBus.onSpeechStart?.invoke(text) }
                    it.start()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        qwenEngine?.stop()
        qwenEngine = null
        super.onDestroy()
    }

    private fun useQwen(): Boolean = runCatching {
        val f = java.io.File(filesDir, "voice_tuning.txt")
        if (!f.isFile) return@runCatching true
        val v = f.readLines().firstOrNull { it.trim().startsWith("asr_engine=") }
            ?.substringAfter("=")?.trim()
        v != "google"
    }.getOrDefault(true)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Evergrow is listening")
        .setContentText("Voice input stays on while the screen is off")
        .setOngoing(true)
        .setSilent(true)
        .build()

    companion object {
        const val ACTION_START = "com.niki914.zafiro.action.LISTEN_START"
        const val ACTION_STOP = "com.niki914.zafiro.action.LISTEN_STOP"
        private const val CHANNEL_ID = "listen_mode"
        private const val NOTIFICATION_ID = 4712
        @Volatile internal var engine: ListenEngine? = null
        @Volatile internal var qwenEngine: QwenListenEngine? = null
    }
}

/** 服务里的转写结果送出去的唯一出口。 */
object ListenBus {
    @Volatile var onSentence: ((String) -> Unit)? = null
    /** 播报开始/结束时静音或恢复麦克风（外放时的半双工）。 */
    fun setMuted(muted: Boolean) { ListenService.engine?.muted = muted }
    @Volatile var onSpeechStart: ((String) -> Unit)? = null
}
