package com.meowtaversegames.cadence

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object BlockingNotifications {
    const val ID = 1
    private const val CHANNEL = "blocking"

    private var channelCreated = false
    private var lastContent: Content? = null
    private data class Content(val title: String, val warnings: String)

    private fun content(runtime: BlockingRuntime): Content {
        val warnings = buildList {
            if (runtime.readFailed) add("設定を読み込めません")
            if (runtime.startFailed) add("ブロックを始められませんでした")
            if (!runtime.permissionState.usage) add("使用状況へのアクセスを許可してください")
            if (!runtime.permissionState.exact) add("正確なアラームが使えません。境目が遅れることがあります")
            if (runtime.launchFailed) add("ブロック画面を出せませんでした。重ねて表示と端末独自の背景表示の設定を確認してください")
            if (runtime.blockWindow.resumed && runtime.blockWindow.multiWindow) add("分割画面を閉じてください")
        }.joinToString("\n")
        val end = runtime.decision?.end
        val title = if (runtime.startFailed) "ブロックを始められませんでした"
            else if (end != null) "集中の時間です · ${clockText(end)} まで" else "設定を読み込めません"
        return Content(title, warnings)
    }

    fun build(context: Context, runtime: BlockingRuntime): Notification = build(context, content(runtime))

    private fun build(context: Context, content: Content): Notification {
        if (!channelCreated) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "集中時間", NotificationManager.IMPORTANCE_LOW),
            )
            channelCreated = true
        }
        val settings = PendingIntent.getActivity(context, 0,
            Intent(context, SettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(content.title).setContentText(content.warnings.ifEmpty { "タップして設定を開く" })
            .setStyle(Notification.BigTextStyle().bigText(content.warnings))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    fun show(context: Context, runtime: BlockingRuntime) {
        val next = content(runtime)
        if (!runtime.permissionState.notifications) return
        // Android 14+ allows users to dismiss ongoing notifications. Republish on the next tick/reconcile.
        val manager = context.getSystemService(NotificationManager::class.java)
        if (next == lastContent && manager.activeNotifications.any { it.id == ID }) return
        if ((Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            runtime.permissionState.notifications) {
            context.getSystemService(NotificationManager::class.java).notify(ID, build(context, next))
            lastContent = next
        }
    }

    // startForeground always publishes; record its content to avoid an identical notify next tick.
    fun foregroundPublished(runtime: BlockingRuntime) { lastContent = content(runtime) }
    fun foregroundRemoved() { lastContent = null }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
        foregroundRemoved()
    }
}
