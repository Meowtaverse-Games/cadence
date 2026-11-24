package com.meowtaversegames.cadence

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val runtime by lazy { BlockingRuntime.get(this) }
    private lateinit var repository: LauncherRepository
    private var snapshot by mutableStateOf(LauncherSnapshot())
    private var favorites by mutableStateOf(emptyList<String>())
    private var favoritesFailed by mutableStateOf(false)
    private var protectedPackages by mutableStateOf(emptySet<String>())
    private var error by mutableStateOf<String?>(null)
    private var uninstallApp by mutableStateOf<AppEntry?>(null)
    private var homeRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        repository = LauncherRepository(this) {
            if (it.failed) error = "アプリの一覧を読み込めませんでした"
            else snapshot = it
        }
        setContent {
            CadenceTheme {
                var now by remember { mutableStateOf(Instant.now()) }
                LaunchedEffect(Unit) {
                    // Alarms and runtime own the block decision. This also handles delayed boundary delivery
                    // while home is visible, and refreshes the clock exactly at the next minute.
                    while (true) {
                        val current = Instant.now()
                        if (current.epochSecond / 60 != now.epochSecond / 60) now = current
                        if (hasWindowFocus() && runtime.decision?.nextBoundary?.let { current >= it } == true) {
                            runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
                        }
                        delay(minOf(1_000L, 60_000 - current.toEpochMilli() % 60_000))
                    }
                }
                val blocked = runtime.decision?.end != null
                val warnings = buildList {
                    if (runtime.permissionState.missing().isNotEmpty()) add("権限の設定が必要です")
                    if (!runtime.permissionState.notifications) add("通知が出ません")
                    if (runtime.startFailed) add("ブロックを始められませんでした")
                    if (runtime.launchFailed) add("ブロック画面を出せませんでした")
                    if (runtime.readFailed) add("設定を読み込めません")
                    if (!runtime.readFailed && runtime.decision?.nextBoundary == null && !blocked) add("時間割がありません")
                    if (favoritesFailed) add("お気に入りを読み込めません")
                }
                val visibleSnapshot = remember(snapshot, runtime.targets, blocked) {
                    snapshot.copy(sections = snapshot.sections.mapNotNull { section ->
                        val visible = visibleApps(section.apps, runtime.targets, blocked)
                        if (visible.isEmpty()) null else section.copy(apps = visible)
                    })
                }
                HomeScreen(
                    HomePresentation(now, runtime.statusText(), warnings,
                        runtime.decision?.end?.let { "${runtime.targets.size} 個のアプリを ${clockText(it)} まで休ませています" }),
                    visibleSnapshot, favorites, runtime.targets, protectedPackages, runtime.readFailed,
                    homeRequest, ::launchApp, ::saveFavorites, ::toggleTarget,
                    { app -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) },
                    { app -> uninstallApp = app },
                    { open(Intent(this, SettingsActivity::class.java)) },
                )
                uninstallApp?.let { app ->
                    // Direct package deletion UI needs REQUEST_DELETE_PACKAGES. Keep the existing
                    // permission surface and guide the user through the system's app-info screen.
                    androidx.compose.material3.AlertDialog(onDismissRequest = { uninstallApp = null },
                        title = { androidx.compose.material3.Text("アンインストール") },
                        text = { androidx.compose.material3.Text("${app.label} のアプリ情報を開き、「アンインストール」を選んでください。システムのアプリなど、削除できないものもあります。") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = {
                            uninstallApp = null
                            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                        }) { androidx.compose.material3.Text("アプリ情報を開く") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { uninstallApp = null }) {
                            androidx.compose.material3.Text("キャンセル")
                        } })
                }
                error?.let { message ->
                    androidx.compose.material3.AlertDialog(onDismissRequest = { error = null },
                        text = { androidx.compose.material3.Text(message) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { error = null }) {
                            androidx.compose.material3.Text("閉じる")
                        } })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        protectedPackages = Permissions.protectedPackages(this)
        val stored = runtime.store.readFavorites()
        favorites = stored.value.orEmpty()
        favoritesFailed = stored.failed
        repository.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        error = null
        uninstallApp = null
        homeRequest++
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }

    private fun launchApp(app: AppEntry) {
        // Recheck at the tap: a boundary may have arrived since composition.
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        if (runtime.decision?.end != null && app.packageName in runtime.targets) return
        try {
            getSystemService(LauncherApps::class.java).startMainActivity(
                requireNotNull(ComponentName.unflattenFromString(app.id)), Process.myUserHandle(), null, null)
        } catch (_: RuntimeException) { error = "アプリを開けませんでした" }
    }

    private fun saveFavorites(ids: List<String>) {
        try {
            runtime.store.saveFavorites(ids)
            favorites = ids
            favoritesFailed = false
        } catch (_: Exception) {
            error = "お気に入りを保存できませんでした。読み込めないファイルは保護されています"
        }
    }

    private fun toggleTarget(app: AppEntry) {
        if (runtime.readFailed || app.packageName in Permissions.protectedPackages(this)) return
        try {
            val next = if (app.packageName in runtime.targets) runtime.targets - app.packageName else runtime.targets + app.packageName
            runtime.store.saveTargets(next)
            runtime.changed()
        } catch (_: Exception) { error = "設定を保存できませんでした" }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) { error = "この操作を開けませんでした" }
    }
}
