package com.niki914.zafiro.app.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.io.File

/**
 * 屏幕桥的服务端：Android 只允许 AccessibilityService 读取别的 app 的界面树。
 *
 * 上游把整块无障碍代码删掉了（manifest 里连 BIND_ACCESSIBILITY_SERVICE 都没有），
 * 所以这里是最小重建：读树、点、输入，外加一个包名白名单 —— 只有白名单里的 app
 * 的事件会被接收，其他 app 的内容根本不进来。
 *
 * 白名单文件：filesDir/screen_packages.txt，一行一个包名，# 开头是注释。
 * 文件不存在 = 不限（保持上游那种全设备控制），存在且有内容 = 只看这些包。
 */
class ScreenService : AccessibilityService() {

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var ttlRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshFilter()
        log("connected; packages=" + ScreenService.packages(this).joinToString(",").ifEmpty { "ALL" })
        armTtl()
    }

    /**
     * 权限是借的，要自己还。开启后最多留 ttlMinutes() 分钟，到点自己关掉 ——
     * 银行类 app 检测的是「有没有无障碍在开着」，忘了关就等于把它们锁死。
     * 时长写在 filesDir/screen_ttl_minutes.txt，默认 60；0 = 不自动关。
     */
    private fun armTtl() {
        val minutes = ttlMinutes()
        if (minutes <= 0) {
            log("ttl: disabled by config")
            return
        }
        val stamp = File(filesDir, STAMP)
        val enabledAt = runCatching { stamp.readText().trim().toLong() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        // 上次开启到现在已经超时（进程被杀过、手机重启过）：立刻还回去
        if (enabledAt > 0 && now - enabledAt > minutes * 60_000L) {
            log("ttl: expired (" + ((now - enabledAt) / 60_000) + " min) - disabling")
            runCatching { stamp.writeText("") }
            disableSelf()
            return
        }
        if (enabledAt == 0L) runCatching { stamp.writeText(now.toString()) }
        val left = (minutes * 60_000L) - (now - (if (enabledAt > 0) enabledAt else now))
        ttlRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            log("ttl: reached " + minutes + " min - disabling")
            runCatching { stamp.writeText("") }
            disableSelf()
        }
        ttlRunnable = r
        main.postDelayed(r, left.coerceAtLeast(1_000L))
        log("ttl: will disable in " + (left / 60_000) + " min")
    }

    private fun ttlMinutes(): Int = runCatching {
        val f = File(filesDir, TTL_FILE)
        if (!f.isFile) return@runCatching 60
        f.readText().trim().toIntOrNull() ?: 60
    }.getOrDefault(60)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只用来知道前台是谁；真正读树是按需 pull，不在这里做重活
        event?.packageName?.let { lastPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        ttlRunnable?.let { main.removeCallbacks(it) }
        ttlRunnable = null
        if (instance === this) instance = null
        log("destroyed")
        super.onDestroy()
    }

    /** 重新读白名单文件并应用到运行中的服务（不必重新授权）。 */
    fun refreshFilter() {
        runCatching {
            val pkgs = packages(this)
            val info = serviceInfo
            info.packageNames = if (pkgs.isEmpty()) null else pkgs.toTypedArray()
            serviceInfo = info
            log("filter=" + (if (pkgs.isEmpty()) "ALL" else pkgs.joinToString(",")))
        }.onFailure { log("filter failed: " + it.message) }
    }

    private fun log(msg: String) {
        runCatching {
            val f = File(filesDir, "screen.log")
            if (f.length() > 200_000) f.writeText("")
            f.appendText(System.currentTimeMillis().toString() + "  " + msg + "\n")
        }
    }

    companion object {
        const val WHITELIST = "screen_packages.txt"
        const val TTL_FILE = "screen_ttl_minutes.txt"
        const val STAMP = "screen_enabled_at.txt"

        @Volatile
        var instance: ScreenService? = null

        @Volatile
        var lastPackage: String = ""

        fun packages(context: android.content.Context): List<String> = runCatching {
            val f = File(context.filesDir, WHITELIST)
            if (!f.isFile) return@runCatching emptyList()
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .distinct()
        }.getOrDefault(emptyList())

        fun enabled(context: android.content.Context): List<String> = runCatching {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            flat.split(':').filter { it.contains(context.packageName) }
        }.getOrDefault(emptyList())
    }
}
