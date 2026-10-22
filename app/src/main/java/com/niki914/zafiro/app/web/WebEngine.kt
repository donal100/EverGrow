package com.niki914.zafiro.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一个看不见的 WebView，用来读需要 JavaScript 才能渲染的页面（以及带着登录态读）。
 *
 * Python 里没有浏览器内核：Chaquopy 装不了 Chromium，也没有 Playwright。
 * 但 WebView 就在系统里，而且它的 cookie 罐由主进程持有 —— 所以「登录一次、
 * 之后用 cookie 直接取文本」这条路只能在主进程里跑。
 *
 * 约定：命令写 filesDir/webview_cmd.json，广播到 WebViewCmdReceiver，
 * 结果写 webview_result.json。和屏幕桥同一套模式（Python 在 :python 进程）。
 */
object WebEngine {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(ctx: Context): WebView {
        web?.let { return it }
        val w = WebView(ctx.applicationContext)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.userAgentString = UA
        w.settings.blockNetworkImage = true   // 只要文本，省流量也省时间
        val created = w
        web = created
        return created
    }

    /**
     * 载入 url，等 onPageFinished，然后（可选）执行 js 取结果。
     * js 为空时抓 document.body.innerText。
     */
    fun load(ctx: Context, url: String, js: String?, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        val out = arrayOf("ERR: timeout")
        main.post {
            runCatching {
                val w = ensure(ctx)
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        val script = js?.takeIf { it.isNotBlank() }
                            ?: "document.body ? document.body.innerText : ''"
                        view.evaluateJavascript(script) { value ->
                            out[0] = value ?: ""
                            latch.countDown()
                        }
                    }
                }
                w.loadUrl(url)
            }.onFailure {
                out[0] = "ERR: " + it.message
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out[0]
    }

    fun cookies(url: String): String = runCatching {
        CookieManager.getInstance().getCookie(url) ?: ""
    }.getOrDefault("")

    fun setCookie(url: String, cookie: String): String = runCatching {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
            cm.setCookie(url, it)
        }
        cm.flush()
        "ok: cookie set"
    }.getOrElse { "ERR: " + it.message }

    /** 把 cookie 罐里某个 url 的 cookie 写到文件，供 Python 的 requests 复用。 */
    fun saveCookies(ctx: Context, url: String, path: String): String = runCatching {
        val c = cookies(url)
        java.io.File(ctx.filesDir, path).writeText(c)
        "ok: " + c.split(";").size + " cookies -> " + path
    }.getOrElse { "ERR: " + it.message }
}
