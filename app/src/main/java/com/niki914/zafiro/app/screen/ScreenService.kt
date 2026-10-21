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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshFilter()
        log("connected; packages=" + ScreenService.packages(this).joinToString(",").ifEmpty { "ALL" })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只用来知道前台是谁；真正读树是按需 pull，不在这里做重活
        event?.packageName?.let { lastPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
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
