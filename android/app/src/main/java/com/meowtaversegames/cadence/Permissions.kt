package com.meowtaversegames.cadence

import android.Manifest
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager

/** Notification permission is intentionally not part of the five enforcement prerequisites. */
data class PermissionState(
    val usage: Boolean,
    val overlay: Boolean,
    val home: Boolean,
    val battery: Boolean,
    val exact: Boolean,
    val notifications: Boolean,
) {
    fun missing(): List<PermissionKind> = buildList {
        if (!usage) add(PermissionKind.USAGE)
        if (!overlay) add(PermissionKind.OVERLAY)
        if (!home) add(PermissionKind.HOME)
        if (!battery) add(PermissionKind.BATTERY)
        if (!exact) add(PermissionKind.EXACT)
    }
}

enum class PermissionKind(val label: String) {
    USAGE("使用状況へのアクセス"), OVERLAY("他のアプリの上に重ねて表示"),
    HOME("既定のホームアプリ"), BATTERY("電池の最適化の対象外"), EXACT("正確なアラーム"),
}

object Permissions {
    fun usage(context: Context): Boolean {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    fun exact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun read(context: Context) = PermissionState(
        usage(context), Settings.canDrawOverlays(context),
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        exact(context), context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
    )

    fun settings(context: Context, kind: PermissionKind): Intent {
        val uri = Uri.parse("package:${context.packageName}")
        return when (kind) {
            PermissionKind.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, uri)
            PermissionKind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            PermissionKind.HOME -> Intent(Settings.ACTION_HOME_SETTINGS)
            PermissionKind.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
            PermissionKind.EXACT -> if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)
            } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
    }

    fun protectedPackages(context: Context): Set<String> = buildSet {
        add(context.packageName)
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let(::add)
        // Include every system handler, even if resolveActivity would return the chooser.
        context.packageManager.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_SYSTEM_ONLY)
            .forEach { add(it.activityInfo.packageName) }
    }
}
