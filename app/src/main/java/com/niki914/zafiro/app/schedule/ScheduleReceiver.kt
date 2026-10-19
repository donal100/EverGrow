package com.niki914.zafiro.app.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.niki914.zafiro.app.MainActivity
import org.json.JSONObject

/** 闹钟到点：发通知；若是每日事件，顺手把下一次排上。 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val event = ScheduleStore.findById(context, id) ?: return
        notifyEvent(context, event)
        if (event.optBoolean("daily")) {
            ScheduleStore.arm(context, event)
        } else {
            ScheduleStore.remove(context, id)
        }
    }

    private fun notifyEvent(context: Context, event: JSONObject) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // 带上 prompt，点开可以把这句话直接交给 agent
                event.optString("prompt").takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_PROMPT, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = event.optString("prompt").takeIf { it.isNotBlank() }
            ?: "Tap to open Evergrow"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(event.optString("title", "Reminder"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(event.optLong("id").toInt(), n) }
    }

    companion object {
        const val ACTION_FIRE = "com.niki914.zafiro.action.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"
        const val EXTRA_PROMPT = "schedule_prompt"
        const val CHANNEL_ID = "schedule_reminders"
    }
}

/** 重启后闹钟全部丢失，这里重新武装。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            runCatching { ScheduleStore.rearmAll(context) }
        }
    }
}
