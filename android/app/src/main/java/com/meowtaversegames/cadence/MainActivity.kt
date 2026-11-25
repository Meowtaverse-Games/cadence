package com.meowtaversegames.cadence

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Process
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
    private val homeListState = androidx.compose.foundation.lazy.LazyListState()
    private var themeSettings by mutableStateOf(HomeThemeSettings())
    private var wallpaper by mutableStateOf<WallpaperPalette?>(null)
    private lateinit var wallpaperObserver: WallpaperObserver
    private var query by mutableStateOf("")
    private var homeRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        wallpaperObserver = WallpaperObserver(this) { wallpaper = it }
        query = savedInstanceState?.getString("query").orEmpty()
        repository = LauncherRepository(this, onPackageRemoved = ::removePackageFavorites) {
            if (it.failed) {
                snapshot = snapshot.copy(failed = true) // Retain the last successful inventory for counting.
                error = "アプリの一覧を読み込めませんでした"
            } else {
                snapshot = it
            }
        }
        setContent {
            val colors = homeColors(themeSettings, wallpaper)
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = colors.darkText
                    isAppearanceLightNavigationBars = colors.darkText
                }
            }
            LauncherTheme(colors) {
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
                    runtime.permissionState.missing().forEach { add("${it.label}が必要です") }
                    if (runtime.startFailed) add("ブロックを始められませんでした")
                    if (runtime.launchFailed) add("ブロック画面を出せませんでした")
                    if (runtime.readFailed) add("設定を読み込めません")
                    if (favoritesFailed) add("お気に入りを読み込めません")
                }
                val visibleSnapshot = remember(snapshot, runtime.targets, blocked) {
                    snapshot.copy(sections = snapshot.sections.mapNotNull { section ->
                        val visible = visibleApps(section.apps, runtime.targets, blocked)
                        if (visible.isEmpty()) null else section.copy(apps = visible)
                    })
                }
                val restingCount = restingAppCount(snapshot.apps, runtime.targets)
                HomeScreen(
                    HomePresentation(now, runtime.statusText(), warnings,
                        runtime.decision?.end?.takeIf { restingCount > 0 }?.let {
                            "$restingCount 個のアプリを ${clockText(it)} まで休ませています"
                        }, notificationsMissing = !runtime.permissionState.notifications),
                    visibleSnapshot, favorites, snapshot.installedIds, snapshot.failed,
                    runtime.targets, protectedPackages, runtime.readFailed,
                    homeRequest, ::launchApp, ::saveFavorites, ::toggleTarget, ::showAppInfo,
                    { app -> open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))) },
                    { open(Intent(this, SettingsActivity::class.java)) },
                    query, { query = it }, homeListState, themeSettings.size,
                    defaultHome = runtime.permissionState.home,
                )
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
        themeSettings = runtime.store.homeTheme.read().value ?: HomeThemeSettings()
        wallpaperObserver.refresh()
        // Check metadata without discarding temporarily unavailable favorites.
        repository.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        error = null
        query = ""
        homeListState.requestScrollToItem(0)
        window.decorView.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        homeRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        wallpaperObserver.close()
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

    private fun removePackageFavorites(packageName: String) {
        // Read the file afresh: a callback can arrive while this activity is stopped.
        val stored = runtime.store.readFavorites()
        if (stored.failed) return
        val ids = stored.value.orEmpty()
        val next = removePackageFavorites(ids, packageName)
        if (next != ids) saveFavorites(next)
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

    private fun showAppInfo(app: AppEntry) {
        try {
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(
                requireNotNull(ComponentName.unflattenFromString(app.id)), Process.myUserHandle(), null, null)
        } catch (_: RuntimeException) { error = "アプリ情報を開けませんでした" }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) { error = "この操作を開けませんでした" }
    }
}
