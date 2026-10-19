package com.niki914.zafiro.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自己的日历 + 闹钟。
 *
 * 为什么不接 Google Calendar：那条路要 OAuth（交互式登录取 refresh token，测试模式
 * 七天过期），而这里真正需要的只是「到点提醒」和「周期性任务」。所以日历自己存，
 * 闹钟自己排，离线可用，也不用担心别人的 token 过期。
 *
 * 存储：filesDir/schedule.json，一个数组，元素形如
 *   {"id":1234,"title":"call plumber","at":1792400000000,"daily":false,"prompt":"..."}
 */
object ScheduleStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "schedule.json")

    @Synchronized
    fun load(ctx: Context): JSONArray = runCatching {
        val f = file(ctx)
        if (f.isFile) JSONArray(f.readText()) else JSONArray()
    }.getOrDefault(JSONArray())

    @Synchronized
    private fun save(ctx: Context, arr: JSONArray) {
        runCatching { file(ctx).writeText(arr.toString()) }
    }

    /** 下一次触发时间：一次性就用 at，每日就滚到下一个相同钟点。 */
    fun nextFire(at: Long, daily: Boolean, now: Long = System.currentTimeMillis()): Long {
        if (!daily) return at
        if (at > now) return at
        var t = at
        while (t <= now) t += 86_400_000L
        return t
    }

    @Synchronized
    fun add(ctx: Context, title: String, at: Long, daily: Boolean = false, prompt: String? = null): JSONObject {
        val arr = load(ctx)
        // 提前一天的「准备」提醒：一次性事件才需要，每日事件天天提醒没意义
        val prepAt = if (daily) 0L else at - 86_400_000L
        val obj = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("title", title)
            .put("at", at)
            .put("daily", daily)
            .put("prompt", prompt ?: JSONObject.NULL)
            .put("prepAt", prepAt)
        arr.put(obj)
        save(ctx, arr)
        arm(ctx, obj)
        return obj
    }

    @Synchronized
    fun list(ctx: Context): JSONArray = load(ctx)

    @Synchronized
    fun remove(ctx: Context, id: Long): Boolean {
        val arr = load(ctx)
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("id") == id) removed = true else out.put(o)
        }
        if (removed) {
            save(ctx, out)
            cancel(ctx, id)
        }
        return removed
    }

    /** 把这条事件排进 AlarmManager。 */
    fun arm(ctx: Context, obj: JSONObject) {
        val id = obj.optLong("id")
        val whenMs = nextFire(obj.optLong("at"), obj.optBoolean("daily"))
        armAt(ctx, id, whenMs, "main", obj)
        // 提前一天的那条：让 agent 有机会说「明天有个事要准备」
        val prepAt = obj.optLong("prepAt", 0L)
        if (prepAt > 0L && prepAt > System.currentTimeMillis()) {
            armAt(ctx, id, prepAt, "prep", obj)
        }
    }

    private fun armAt(ctx: Context, id: Long, whenMs: Long, kind: String, obj: JSONObject) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(ctx, id, kind)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            } else {
                // 没有精确闹钟权限时不硬来：退化成 setAndAllowWhileIdle，最多晚几分钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        }
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(ctx, id, "main")) }
        runCatching { am.cancel(pendingIntent(ctx, id, "prep")) }
    }

    /** 开机/重启后重新武装所有事件（闹钟不会自己活过重启）。 */
    fun rearmAll(ctx: Context) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) arm(ctx, arr.getJSONObject(i))
    }

    private fun pendingIntent(ctx: Context, id: Long, kind: String): PendingIntent {
        val intent = Intent(ctx, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_FIRE
            putExtra(ScheduleReceiver.EXTRA_ID, id)
            putExtra(ScheduleReceiver.EXTRA_KIND, kind)
        }
        // 主闹钟和提前提醒用不同的 requestCode，否则后一个会覆盖前一个
        val code = if (kind == "prep") (id + 1).toInt() else id.toInt()
        return PendingIntent.getBroadcast(
            ctx,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 让 Python 侧能按「分钟/小时」直接排，省得自己算毫秒。 */
    fun addInMinutes(ctx: Context, title: String, minutes: Int, prompt: String?): JSONObject =
        add(ctx, title, System.currentTimeMillis() + minutes * 60_000L, false, prompt)

    fun findById(ctx: Context, id: Long): JSONObject? {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("id") == id) return o
        }
        return null
    }
}
