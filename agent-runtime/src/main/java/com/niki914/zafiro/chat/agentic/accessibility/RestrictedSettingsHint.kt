package com.niki914.zafiro.chat.agentic.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 13+ "restricted settings".
 *
 * An app installed from outside an app store cannot be granted accessibility access: the
 * toggle in Settings > Accessibility silently refuses and stays off, with a dialog that
 * only says "App was denied access". The actual switch lives behind
 * App info > overflow menu (top right) > "Allow restricted settings".
 *
 * The default permission chain only knows ROOT_SHELL / SHIZUKU / JUMP_SETTINGS. All three
 * fail in this state and report DENIED_BY_USER, which reads as if the user refused when in
 * fact the OS blocked the toggle. This object detects that situation so the failure message
 * can name the real cause and the real next step.
 */
internal object RestrictedSettingsHint {

    /** Installers that are app stores; restricted settings do not apply to these. */
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.google.android.feedback",
        "com.xiaomi.market",
        "com.miui.packageinstaller",
    )

    /** True when this build was sideloaded, i.e. restricted settings may apply. */
    fun isSideloaded(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == null || installer !in STORE_INSTALLERS
    }.getOrDefault(false)

    /** Lands on the App info screen that hosts the "Allow restricted settings" menu item. */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Empty string when the build came from a store, where this cannot happen. */
    fun guidance(context: Context): String {
        if (!isSideloaded(context)) return ""
        return " NOTE: '${context.packageName}' was installed from outside an app store, so Android 13+ " +
                "may have placed it in restricted settings, in which case the accessibility toggle will " +
                "silently refuse. That switch is NOT under Settings > Accessibility. Open App info for " +
                "'${context.packageName}' > three-dot menu (top right) > 'Allow restricted settings', then " +
                "enable the service again."
    }
}
