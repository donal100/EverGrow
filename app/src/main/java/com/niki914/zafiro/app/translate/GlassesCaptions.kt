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

    /** 眼镜插着吗：按 USB 音频设备名判断（XREAL / Nreal）。 */
    private fun glassesPresent(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return@runCatching false
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { d ->
            (d.productName ?: "").toString().lowercase().let {
                it.contains("xreal") || it.contains("nreal")
            }
        }
    }.getOrDefault(false)

    /**
     * 镜像模式下应用拿不到第二块屏，于是改在这块屏上画全屏字幕：
     * 系统镜像会把这一页原样送到眼镜，黑底在光学上等于透明，只剩白字。
     * glasses_overlay=0 可以关掉。
     */
    private fun overlayWanted(ctx: Context): Boolean = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("glasses_overlay=") }
            ?.substringAfter("=")?.trim() != "0"
    }.getOrDefault(true)

    private fun showOverlay(ctx: Context) {
        if (!overlayWanted(ctx) || !glassesPresent(ctx)) return
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, CaptionActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Logger.i(TAG, "caption overlay launched (mirror mode)")
        }.onFailure { Logger.w(TAG, "overlay failed " + it.javaClass.simpleName) }
    }


    /**
     * 字幕字号：voice_tuning.txt 里的 glasses_font=40（sp，18..96）。
     * 眼镜的视野和投影尺寸因人而异，字号只能由用户按自己的眼睛定；
     * 改成文件里的一个数字就能调，不必重新编译。
     */
    fun fontSize(ctx: Context): Float = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("glasses_font=") }
            ?.substringAfter("=")?.trim()?.toFloatOrNull()?.coerceIn(8f, 120f) ?: 40f
    }.getOrDefault(40f)


    /**
     * 字幕贴在哪：voice_tuning.txt 的 glasses_pos=top|center|bottom（默认 top）。
     * 默认放最上面是有原因的 —— 戴着眼镜看别的东西（电视、舞台、街上招牌）时，
     * 字幕占住顶部，画面其余部分一点不挡。
     */
    fun gravity(ctx: Context): Int = when (runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("glasses_pos=") }
            ?.substringAfter("=")?.trim()?.lowercase() ?: "top"
    }.getOrDefault("top")) {
        "center" -> Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }


    /**
     * 左右留白：voice_tuning.txt 的 glasses_margin=140（像素）。
     * 满宽的一行字会一直顶到镜片边缘，那里既不清楚也在视野之外；
     * 两侧收进来，一句话就落在视野中段。
     */
    fun margin(ctx: Context): Int = runCatching {
        File(ctx.filesDir, "voice_tuning.txt").readLines()
            .firstOrNull { it.trim().startsWith("glasses_margin=") }
            ?.substringAfter("=")?.trim()?.toIntOrNull()?.coerceIn(0, 600) ?: 140
    }.getOrDefault(140)

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
        // 先把看到的每一块屏写进日志：镜像模式下应用可能只看到一块，
        // 那时无论怎么挂都挂不上，必须由用户在系统里切成「扩展」。
        runCatching {
            m.displays.forEach { d ->
                val mode = runCatching { d.mode }.getOrNull()
                Logger.i(
                    TAG,
                    "display id=" + d.displayId + " name=" + d.name +
                        " flags=0x" + Integer.toHexString(d.flags) +
                        " valid=" + d.isValid + " state=" + d.state +
                        " size=" + (mode?.physicalWidth ?: -1) + "x" + (mode?.physicalHeight ?: -1),
                )
            }
        }
        if (m.displays.size <= 1 && presentation == null) {
            Logger.i(TAG, "no external display visible (count=" + m.displays.size + ")")
        }
        val target = runCatching {
            m.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }.getOrNull()
        if (target == null) {
            runCatching { presentation?.dismiss() }
            presentation = null
            showOverlay(a)
            return
        }
        val current = presentation
        if (current != null && current.display.displayId == target.displayId) return
        runCatching { current?.dismiss() }
        // 失败也要留下原因：SecurityException（私有屏）和「屏不存在」是两件事。
        runCatching {
            val p = CaptionPresentation(a, target)
            p.show()
            presentation = p
            p.render(Translator.snapshotLines())
            Logger.i(TAG, "showing on display=" + target.displayId + " " + target.name)
        }.onFailure { e ->
            Logger.w(
                TAG,
                "presentation failed on " + target.displayId + ": " +
                    e.javaClass.simpleName + " " + (e.message ?: ""),
            )
        }
    }

    private class CaptionPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {

        private val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(margin(ctx), 36, margin(ctx), 36)
        }

        // 只显示当前这一句：历史越多，眼睛越要在一堆同样的白字里找最新那行。
        private val text = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize(ctx))
            setLineSpacing(8f, 1f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        init {
            box.gravity = gravity(ctx)
            box.addView(text)
            setContentView(box)
        }

        fun render(lines: List<String>) {
            runCatching { text.text = lines.lastOrNull().orEmpty() }
        }
    }
}
