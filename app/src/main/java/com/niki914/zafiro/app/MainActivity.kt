package com.niki914.zafiro.app

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.niki914.logging.Logger
import com.niki914.uikit.infra.GlobalTouchDispatcher
import com.niki914.zafiro.app.crash.CrashRecorder
import com.niki914.zafiro.app.ui.ZafiroApp
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.runBlocking

// tag:niki914 | tag:nexus-x-log | message:niki914 | message:nexus-x-log
class MainActivity : AppCompatActivity() {
    private fun applyLanguageTag(tag: String) {
        // 始终显式设置：空 tag = 清除应用内语言，回落系统；否则用户指定优先
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
    }

    // launcher 必须在 STARTED 前注册：MainActivity 预注册 → ApplicationService 持有结果路由
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requireService<ApplicationService>().onRuntimePermissionResult(granted)
    }



    /**
     * 问一次「忽略电池优化」。从 Activity 发起才能可靠弹出系统对话框
     * （Application 上下文起 Activity 在 Android 10+ 会被拦）。
     */
    private fun askIgnoreBatteryOptimizationOnce() {
        runCatching {
            val prefs = getSharedPreferences("zafiro_app", android.content.Context.MODE_PRIVATE)
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            if (prefs.getBoolean("battery_opt_prompted", false)) return
            prefs.edit().putBoolean("battery_opt_prompted", true).apply()
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + packageName)
                )
            )
            Logger.i("niki914_zafiro_BatteryOpt", "asked for battery optimisation exemption")
        }
    }

    /**
     * 问一次 BLUETOOTH_CONNECT。没有它，通话模式下的 startBluetoothSco 是静默空转，
     * 耳机麦克风永远拿不到 —— 实测 WhatsApp 能拿到耳机麦、我们拿不到，差别就在这个权限。
     */
    private fun askBluetoothConnectOnce() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        runCatching {
            val perm = android.Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val prefs = getSharedPreferences("zafiro_app", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("bt_connect_prompted", false)) return
            prefs.edit().putBoolean("bt_connect_prompted", true).apply()
            requestPermissions(arrayOf(perm), 4101)
            Logger.i("niki914_zafiro_BtAudio", "asked for BLUETOOTH_CONNECT")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // launcher 必须在 STARTED 前注册：预注册后装进 ApplicationService
        requireService<ApplicationService>()
            .installPermissionLauncher(permissionLauncher)
        // 通话模式要在后台一直听；系统掐后台时它会静默变聋。
        // 豁免只能由用户确认（没有静默授予的 API），所以开机问一次、记住不再骚扰。
        askIgnoreBatteryOptimizationOnce()
        askBluetoothConnectOnce()
        // 每次冷启动都重排一次闹钟：更新、强停、系统清理都可能把已排的提醒弄丢，
        // 而用户预期「设过的事一定响」。
        runCatching { com.niki914.zafiro.app.schedule.ScheduleStore.rearmAll(applicationContext) }

        val startupAssistantUi = resolveStartupAssistantUi()
        val launchDecision = runBlocking {
            val decision = AppLaunchDecision.resolve(startupAssistantUi)
            // 同步读主题偏好：深色模式冷启动首帧不能闪白
            ThemeController.load()
            // 回填型设置 flow 的冷启动回填（首帧真值，防“进设置页才生效”类 bug）
            runCatching { XRepo.hydrateSettings() }
                .onFailure { Logger.w("niki914_zafiro_Main", "hydrate failed ${it.message}") }
            decision
        }
        applyLanguageTag(launchDecision.languageTag)
        // 秒崩（启动窗口内崩）的报告不能依赖 UI 去取：首帧前崩掉时弹窗永远没机会出现。
        // 放在 setContent 之前、语言生效之后：最早期拿到报告，文案语言也对。
        CrashRecorder.consumeStartupCrash(this)

        handleIncomingIntent(intent)

        setContent {
            ZafiroApp(
                startupAssistantUi = startupAssistantUi,
                launchDecision = launchDecision,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val targetConversationId =
            intent?.getStringExtra(ApplicationService.EXTRA_TARGET_CONVERSATION_ID)
        findService<ApplicationService>()?.handleNavigationIntent(targetConversationId)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        GlobalTouchDispatcher.dispatch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // 前台跟踪由 ApplicationService 经 lifecycle callbacks 自动维护，无需手动转发
    }
}
