package com.niki914.zafiro.app.web

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 应用内的「登录窗口」：一个真正显示出来的 WebView。
 *
 * 为什么要单独的 Activity：隐藏的 WebView 里点不动登录按钮 —— 实测 scmp 的登录页
 * 是单页应用，合成的 click 事件它不认（dispatchEvent 之后页面毫无变化）。
 * 把它显示出来，用户自己点一次、输一次，cookie 落在本 app 的 WebView cookie 罐里，
 * 之后 Python 就能拿这份 cookie 直接取正文 —— 不需要密码，也不需要读浏览器的 cookie。
 */
class WebLoginActivity : Activity() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL) ?: "https://account.scmp.com/login"
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.webViewClient = WebViewClient()
        val root = FrameLayout(this)
        root.addView(
            w,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        web = w
        w.loadUrl(url)
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) {
            w.goBack()
        } else {
            CookieManager.getInstance().flush()
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
