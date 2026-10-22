package com.niki914.zafiro.app.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * 跨进程命令入口。
 *
 * Python 跑在 `:python` 进程里，界面/无障碍服务在主进程 —— 两边静态变量互不可见，
 * 所以从 Python 直接调 ScreenBridge 永远是 null。这里用「广播 + 文件」传递：
 * 命令写 filesDir/screen_cmd.json，广播只当敲门砖，结果写回 screen_result.json。
 *
 * 只接受本 app 的广播（android:exported="false"），别的东西敲不进来。
 */
class ScreenCmdReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                handle(app)
            } catch (t: Throwable) {
                writeResult(app, -1, "ERR: " + t.message)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(app: Context) {
        val cmdFile = File(app.filesDir, "screen_cmd.json")
        if (!cmdFile.isFile) {
            writeResult(app, -1, "ERR: no command file")
            return
        }
        val cmd = runCatching { JSONObject(cmdFile.readText()) }.getOrElse {
            writeResult(app, -1, "ERR: bad command json")
            return
        }
        val id = cmd.optLong("id", -1)
        val op = cmd.optString("op", "read")
        val out = when (op) {
            "read" -> ScreenBridge.readTree(cmd.optInt("max", 150))
            "tap" -> ScreenBridge.tap(cmd.optInt("token", -1))
            "type" -> ScreenBridge.setText(cmd.optInt("token", -1), cmd.optString("text", ""))
            "back" -> ScreenBridge.back()
            "home" -> ScreenBridge.home()
            "status" -> "ready=" + ScreenBridge.ready() + " foreground=" + ScreenBridge.foreground() +
                " whitelist=" + ScreenService.packages(app).joinToString(",").ifEmpty { "ALL" }
            "packages" -> ScreenBridge.setPackages(cmd.optString("packages", ""))
            "swipe" -> ScreenBridge.swipe(
                cmd.optInt("x1", 600), cmd.optInt("y1", 1900),
                cmd.optInt("x2", 600), cmd.optInt("y2", 700),
                cmd.optLong("ms", 320L),
            )
            "down" -> ScreenBridge.scrollDown()
            "up" -> ScreenBridge.scrollUp()
            "tapxy" -> ScreenBridge.tapXY(cmd.optInt("x", 600), cmd.optInt("y", 1200))
            "shot" -> ScreenBridge.shot(app, cmd.optString("name", "screen_shot.png"))
            "off" -> ScreenBridge.disableSelf()
            "enable" -> ScreenBridge.openSettings(app)
            else -> "ERR: unknown op " + op
        }
        writeResult(app, id, out)
    }

    private fun writeResult(app: Context, id: Long, out: String) {
        runCatching {
            val obj = JSONObject()
            obj.put("id", id)
            obj.put("out", out)
            obj.put("at", System.currentTimeMillis())
            File(app.filesDir, "screen_result.json").writeText(obj.toString())
        }
    }

    companion object {
        const val ACTION = "com.niki914.zafiro.evergrow.screen.CMD"
    }
}
