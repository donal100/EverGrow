package com.niki914.zafiro.app.translate

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.niki914.logging.Logger
import java.io.File

/**
 * 把字幕投到外接显示器（XREAL 之类的 USB-C 眼镜）上：整块屏只有黑底白字。
 *
 * 为什么不用投屏：镜像会把整个手机界面搬上去 —— 状态栏、按钮、通知，
 * 在眼镜里既看不清也不体面。Presentation 是 Android 给「第二块屏」准备的
 * 独立窗口，不需要任何权限，手机本身照常显示自己的界面。
 *
 * 前提：手机要把眼镜当成**扩展**显示器（不是镜像）。镜像时这里拿不到
 * 独立 Display，什么都不显示。
 */
object GlassesCaptions {

    private const val TAG = "niki914_zafiro_Glasses"
    /** 屏幕上留几行：眼镜视野有限，太长反而读不过来。 */
    private const val LINES = 5

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null
    private var presentation: CaptionPresentation? = null
    private var ticker: Runnable? = null
    private var lastVersion = -1

    /** voice_tuning.txt 里 glasses_captions=0 可以关掉这块屏。 */
    private fun enabled(ctx: Context): Boolean = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("glasses_captions=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    fun attach(ctx: Context) {
        if (dm != null) return
        if (!enabled(ctx)) { Logger.i(TAG, "glasses captions off"); return }
        val a = ctx.applicationContext
        app = a
        val m = a.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        dm = m
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        listener = l
        runCatching { m.registerDisplayListener(l, main) }
        refresh()
        val t = object : Runnable {
            override fun run() {
                val v = Translator.linesVersion
                if (v != lastVersion) {
                    lastVersion = v
                    presentation?.render(Translator.snapshotLines())
                }
                main.postDelayed(this, 250)
            }
        }
        ticker = t
        main.post(t)
        Logger.i(TAG, "caption surface attached displays=" + m.displays.size)
    }

    fun detach() {
        runCatching { listener?.let { dm?.unregisterDisplayListener(it) } }
        listener = null
        dm = null
        runCatching { ticker?.let { main.removeCallbacks(it) } }
        ticker = null
        runCatching { presentation?.dismiss() }
        presentation = null
        app = null
        lastVersion = -1
    }

    /** 找到第一块「不是手机自带」的显示器，把字幕窗口挂上去。 */
    private fun refresh() {
        val m = dm ?: return
        val a = app ?: return
        val target = runCatching {
            m.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }.getOrNull()
        if (target == null) {
            runCatching { presentation?.dismiss() }
            presentation = null
            return
        }
        val current = presentation
        if (current != null && current.display.displayId == target.displayId) return
        runCatching { current?.dismiss() }
        val p = runCatching { CaptionPresentation(a, target) }.getOrNull() ?: return
        runCatching { p.show() }
        presentation = p
        p.render(Translator.snapshotLines())
        Logger.i(TAG, "showing on display=" + target.displayId + " " + target.name)
    }

    private class CaptionPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {

        private val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(72, 48, 72, 48)
        }

        private val rows = (0 until LINES).map {
            TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34f)
                setLineSpacing(6f, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                text = ""
            }
        }

        init {
            rows.forEach { box.addView(it) }
            setContentView(box)
        }

        /** 只留最后几行；最新一行最亮，旧的灰一点，眼睛自然落在最新那句。 */
        fun render(lines: List<String>) {
            runCatching {
                val tail = lines.takeLast(LINES)
                rows.forEachIndexed { i, tv ->
                    val idx = tail.size - rows.size + i
                    val text = if (idx >= 0 && idx < tail.size) tail[idx] else ""
                    tv.text = text
                    tv.alpha = if (text.isBlank()) 0f else 0.45f + 0.55f * ((i + 1).toFloat() / rows.size)
                }
            }
        }
    }
}
