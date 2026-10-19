package com.niki914.zafiro.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.niki914.zafiro.app.MainActivity
import java.io.File

/**
 * 跑步记录：前台定位服务。
 *
 * 为什么必须是前台服务（而不是 Python 里挂个线程继续收点）：
 * 从 Android 10 起，「仅在使用中」的定位权限在应用退到后台就作废；
 * 只有 declared `foregroundServiceType="location"` 的前台服务还能继续拿点。
 * 所以跑动采样放在这里，Python 只负责开、停、读、算。
 *
 * 采点策略：3 秒或 0 米触发（minDistance=0 是为了室内/低速时不至于一个点都不来），
 * 每个点 append 一行 JSON 到 filesDir/run_points.jsonl，宁可多写也不丢。
 */
class RunTrackingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                // Android 的硬性契约：凡是 startForegroundService() 启动的，5 秒内必须
                // 调一次 startForeground()，不管这个 action 打算干什么。以前这里直接
                // stopSelf()，系统于是判定「前台服务没起来」，抛出
                // ForegroundServiceDidNotStartInTimeException 把整个进程杀掉 ——
                // 表现就是「点停止跑步，应用崩了」。
                runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startTracking()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun startTracking() {
        if (listener != null) return
        val granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            log("no location permission, stopping")
            stopSelf()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            log("no LocationManager")
            stopSelf()
            return
        }
        // 新的一趟从零开始：清空旧点。用户明确要的是「重启就是重来」。
        pointsFile(this).writeText("")
        runCatching { activeMarker(this).writeText(System.currentTimeMillis().toString()) }

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                appendPoint(location)
            }
        }
        listener = l
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(provider, SAMPLE_INTERVAL_MS, 0f, l, Looper.getMainLooper())
            }.onFailure { log("request failed for " + provider + ": " + it.message) }
        }
        log("tracking started")
    }

    private fun stopTracking() {
        val l = listener ?: run {
            runCatching { activeMarker(this).delete() }
            return
        }
        listener = null
        runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.removeUpdates(l)
        }
        runCatching { activeMarker(this).delete() }
        log("tracking stopped")
    }

    private fun appendPoint(location: Location) {
        runCatching {
            val line = buildString {
                append("{\"t\":")
                append(location.time)
                append(",\"lat\":")
                append(location.latitude)
                append(",\"lon\":")
                append(location.longitude)
                append(",\"acc\":")
                append(if (location.hasAccuracy()) location.accuracy else -1f)
                append(",\"spd\":")
                append(if (location.hasSpeed()) location.speed else -1f)
                append(",\"prv\":\"")
                append(location.provider ?: "?")
                append("\"}")
                append("\n")
            }
            pointsFile(this).appendText(line)
        }
    }

    private fun activeMarker(ctx: Context) = File(ctx.filesDir, "run_active")

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_mylocation
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Run tracking")
            .setContentText("Recording distance and time")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Run tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the agent records a run"
                setShowBadge(false)
            },
        )
    }

    private fun log(msg: String) {
        runCatching {
            File(filesDir, "run_service.log")
                .appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    companion object {
        const val ACTION_START = "com.niki914.zafiro.action.RUN_START"
        const val ACTION_STOP = "com.niki914.zafiro.action.RUN_STOP"
        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIFICATION_ID = 4711
        private const val SAMPLE_INTERVAL_MS = 3000L

        fun pointsFile(context: Context) = File(context.filesDir, "run_points.jsonl")
    }
}
