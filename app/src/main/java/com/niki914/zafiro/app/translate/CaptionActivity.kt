package com.niki914.zafiro.app.translate

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.niki914.logging.Logger

/**
 * 全屏字幕页：纯黑底 + 白字，专门给「镜像到眼镜」用的一条路。
 *
 * 为什么需要它：系统的镜像模式只把手机这一块屏复制出去，应用拿不到第二块 Display
 * （实测 DisplayManager 只报 1 块），所以 Presentation 那条路在这些机型上根本挂不上。
 * 但镜像会把这块全屏页面原样送到眼镜上，而眼镜的黑像素是不发光的 ——
 * 看起来就只剩飘着的白字，效果和「直接画在外接屏上」几乎一样。
 *
 * 代价：手机屏幕也被字幕占满。所以退出就是返回键/音量下，或者停止翻译。
 */
class CaptionActivity : Activity() {

    private companion object {
        const val TAG = "niki914_zafiro_Glasses"
    }

    private lateinit var text: TextView
    private val main = Handler(Looper.getMainLooper())
    private var version = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val m = GlassesCaptions.margin(this@CaptionActivity)
            setPadding(m, 40, m, 40)
        }
        val sp = GlassesCaptions.fontSize(this)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
            setLineSpacing(8f, 1f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        box.gravity = GlassesCaptions.gravity(this)
        box.addView(text)
        setContentView(box)
        Logger.i(TAG, "caption activity shown")
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // 翻译停了就自己关掉，别把手机屏幕一直占着。
            if (!Translator.isRunning) {
                runCatching { finish() }
                return
            }
            val v = Translator.linesVersion
            if (v != version) {
                version = v
                render(Translator.snapshotLines())
            }
            main.postDelayed(this, 250)
        }
    }

    private fun render(lines: List<String>) {
        text.text = lines.lastOrNull().orEmpty()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        Logger.i(TAG, "caption activity closed")
        super.onDestroy()
    }
}
