package com.niki914.zafiro.app.screen

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 给 Python 侧用的屏幕操作门面。
 *
 * 句柄用整数 token：readTree() 会把当前窗口的节点按顺序编号并缓存，
 * 之后 tap(token) / setText(token) 指的就是那一次读到的节点。
 * 页面变了就重新读 —— token 只对最近一次 read 有效。
 */
object ScreenBridge {

    @Volatile
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    fun ready(): Boolean = ScreenService.instance != null

    /** 最近一次收到事件的包名，用来确认「现在是不是停在目标 app 上」。 */
    fun foreground(): String = ScreenService.lastPackage.ifEmpty { "?" }

    fun readTree(maxNodes: Int = 150): String {
        val svc = ScreenService.instance ?: return "ERR: accessibility service not enabled"
        val root = svc.rootInActiveWindow ?: return "ERR: no active window (open an app first)"
        val out = StringBuilder()
        val list = ArrayList<AccessibilityNodeInfo>()
        walk(root, 0, maxNodes, list, out)
        nodes = list
        return out.toString()
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxNodes: Int,
        list: ArrayList<AccessibilityNodeInfo>,
        out: StringBuilder,
    ) {
        if (node == null || list.size >= maxNodes || depth > 25) return
        val idx = list.size
        list.add(node)
        val r = Rect()
        node.getBoundsInScreen(r)
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            .replace('\n', ' ')
            .take(60)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        out.append('[').append(idx).append("] ")
            .append("  ".repeat(depth))
            .append(cls)
            .append(if (node.isClickable) "*" else "")
            .append(" (").append(r.left).append(',').append(r.top).append(',')
            .append(r.right).append(',').append(r.bottom).append(')')
        if (label.isNotEmpty()) out.append(" \"").append(label).append('"')
        out.append('\n')
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, maxNodes, list, out)
    }

    fun tap(token: Int): String {
        val n = nodes.getOrNull(token) ?: return "ERR: no node $token (call read first)"
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok: click"
        var p: AccessibilityNodeInfo? = n
        var hops = 0
        while (p != null && hops < 6) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok: click ancestor"
            p = p.parent
            hops++
        }
        val svc = ScreenService.instance ?: return "ERR: service gone"
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return "ERR: node has empty bounds"
        val path = Path()
        path.moveTo(r.exactCenterX(), r.exactCenterY())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return if (svc.dispatchGesture(gesture, null, null)) "ok: gesture" else "ERR: gesture rejected"
    }

    fun setText(token: Int, text: String): String {
        val n = nodes.getOrNull(token) ?: return "ERR: no node $token (call read first)"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok: text set" else "ERR: node is not editable"
    }

    /** 后退 / 回桌面，省得 Python 侧再拼 keyevent。 */
    fun back(): String {
        val svc = ScreenService.instance ?: return "ERR: service not enabled"
        return if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) "ok: back" else "ERR: back rejected"
    }

    fun home(): String {
        val svc = ScreenService.instance ?: return "ERR: service not enabled"
        return if (svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) "ok: home" else "ERR: home rejected"
    }

    fun setPackages(csv: String): String {
        val svc = ScreenService.instance
        val file = java.io.File(
            (svc?.filesDir ?: return "ERR: service not enabled").absolutePath,
            ScreenService.WHITELIST,
        )
        runCatching { file.writeText(csv.replace(",", "\n")) }.onFailure { return "ERR: " + it.message }
        svc.refreshFilter()
        return "ok: packages=" + ScreenService.packages(svc).joinToString(",").ifEmpty { "ALL" }
    }

    /**
     * 用完就把自己关掉：银行类 app 检测的是「有没有无障碍服务开着」，而不是白名单，
     * 所以借完权限必须还回去。disableSelf 不需要任何额外权限，开回来才需要用户点。
     */
    fun disableSelf(): String {
        val svc = ScreenService.instance ?: return "ERR: already disabled"
        runCatching { svc.disableSelf() }
        return "ok: accessibility disabled"
    }

    /** 把用户送到「无障碍」设置页，让他自己打开 Evergrow 那一项。 */
    fun openSettings(context: android.content.Context): String = runCatching {
        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "ok: opened accessibility settings - enable " + context.packageName + "/.screen.ScreenService"
    }.getOrElse { "ERR: " + it.message }

    /**
     * 滑动。有它才能翻商品列表和评论区 —— 之前的桥只有 tap/read，
     * 结果就是只能看到第一屏四个商品，用户说「你得能读评论」。
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 320L): String {
        val svc = ScreenService.instance ?: return "ERR: service not enabled"
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(60L, 1500L)))
            .build()
        return if (svc.dispatchGesture(gesture, null, null)) "ok: swipe" else "ERR: gesture rejected"
    }

    /** 整屏上下翻滚，交给 Python 说「往下」而不是每次算坐标。 */
    fun scrollDown(): String = swipe(600, 1900, 600, 700)
    fun scrollUp(): String = swipe(600, 700, 600, 1900)

    /** 按坐标点，用于没有可用节点的自绘界面。 */
    fun tapXY(x: Int, y: Int): String {
        val svc = ScreenService.instance ?: return "ERR: service not enabled"
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return if (svc.dispatchGesture(gesture, null, null)) "ok: tap" else "ERR: gesture rejected"
    }

    /**
     * 截屏。自绘界面（淘宝的规格面板、购物车）在无障碍树里是空的，
     * 只有拿到像素才能判断按钮在哪，再用 tapXY 点下去。
     * API 30+ 的 AccessibilityService.takeScreenshot，配置里已经声明了 canTakeScreenshot。
     */
    fun shot(ctx: android.content.Context, name: String = "screen_shot.png"): String {
        val svc = ScreenService.instance ?: return "ERR: service not enabled"
        val latch = java.util.concurrent.CountDownLatch(1)
        val out = arrayOf("ERR: timeout")
        val file = java.io.File(ctx.filesDir, name)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        runCatching {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        result: android.accessibilityservice.AccessibilityService.ScreenshotResult,
                    ) {
                        runCatching {
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace,
                            )
                            val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            java.io.FileOutputStream(file).use { fos ->
                                soft?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                            }
                            val w = soft?.width ?: 0
                            val h = soft?.height ?: 0
                            soft?.recycle()
                            hw?.recycle()
                            runCatching { result.hardwareBuffer.close() }
                            out[0] = "ok: " + file.absolutePath + " " + w + "x" + h
                        }.onFailure { out[0] = "ERR: " + it.message }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        out[0] = "ERR: screenshot failed code=" + errorCode
                        latch.countDown()
                    }
                },
            )
        }.onFailure {
            out[0] = "ERR: " + it.message
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { executor.shutdown() }
        return out[0]
    }
}
