package com.niki914.zafiro.app.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * WebView 命令入口，跑在主进程（WebView 必须活在主进程，且操作要在主线程）。
 * 和 ScreenCmdReceiver 同一套「文件 + 广播」协议，只给本 app 用（exported=false）。
 */
class WebViewCmdReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                handle(app)
            } catch (t: Throwable) {
                write(app, -1, "ERR: " + t.message)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(app: Context) {
        val f = File(app.filesDir, "webview_cmd.json")
        if (!f.isFile) {
            write(app, -1, "ERR: no command file")
            return
        }
        val cmd = runCatching { JSONObject(f.readText()) }.getOrElse {
            write(app, -1, "ERR: bad command json")
            return
        }
        val id = cmd.optLong("id", -1)
        val op = cmd.optString("op", "fetch")
        val url = cmd.optString("url", "")
        val timeout = cmd.optLong("timeout_ms", 30_000L)
        val out = when (op) {
            "fetch" -> WebEngine.load(
                app, url, cmd.optString("js", ""), timeout, cmd.optLong("delay_ms", 0L),
            )
            "js" -> WebEngine.evalJs(app, cmd.optString("js", ""), timeout)
            "cookies" -> WebEngine.cookies(url)
            "setcookie" -> WebEngine.setCookie(url, cmd.optString("cookie", ""))
            "savecookies" -> WebEngine.saveCookies(app, url, cmd.optString("path", "webview_cookies.txt"))
            else -> "ERR: unknown op " + op
        }
        write(app, id, out)
    }

    private fun write(app: Context, id: Long, out: String) {
        runCatching {
            val obj = JSONObject()
            obj.put("id", id)
            obj.put("out", out)
            obj.put("at", System.currentTimeMillis())
            File(app.filesDir, "webview_result.json").writeText(obj.toString())
        }
    }

    companion object {
        const val ACTION = "com.niki914.zafiro.evergrow.webview.CMD"
    }
}
