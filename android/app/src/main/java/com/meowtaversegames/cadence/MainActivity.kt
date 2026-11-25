package com.meowtaversegames.cadence

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val runtime by lazy { BlockingRuntime.get(this) }
    private lateinit var widgets: WidgetController
    private lateinit var repository: LauncherRepository
    private var snapshot by mutableStateOf(LauncherSnapshot())
    private var favorites by mutableStateOf(emptyList<String>())
    private var favoritesFailed by mutableStateOf(false)
    private var protectedPackages by mutableStateOf(emptySet<String>())
    private var error by mutableStateOf<String?>(null)
    private val homeListState = androidx.compose.foundation.lazy.LazyListState()
    private var animatorScale by mutableFloatStateOf(1f)
    private val animationScaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshAnimatorScale() }
    }
    private var themeSettings by mutableStateOf(HomeThemeSettings())
    private var wallpaper by mutableStateOf<WallpaperPalette?>(null)
    private var awaitingWallpaper by mutableStateOf(true)
    private var cachedAppearance: HomeAppearance? = null
    private lateinit var wallpaperObserver: WallpaperObserver
    private var query by mutableStateOf("")
    private var homeRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        themeSettings = runtime.store.homeTheme.read().value ?: HomeThemeSettings()
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false, animationScaleObserver)
        cachedAppearance = savedInstanceState?.takeIf { it.containsKey("homeAccentSource") }?.let {
            HomeAppearance(it.getBoolean("homeDarkText"), it.getInt("homeAccentSource"))
        } ?: lastAppearance
        wallpaperObserver = WallpaperObserver(this) {
            wallpaper = it
            awaitingWallpaper = false
        }
        widgets = WidgetController(this, runtime, savedInstanceState) { error = it }
        if (savedInstanceState == null && intent.action == ACTION_ADD_WIDGET) widgets.openPicker()
        query = savedInstanceState?.getString("query").orEmpty()
        repository = LauncherRepository(this, onPackageRemoved = { removePackageFavorites(it); widgets.packageRemoved(it) }) {
            if (it.failed) {
                snapshot = snapshot.copy(failed = true) // Retain the last successful inventory.
                error = "アプリの一覧を読み込めませんでした"
            } else {
                snapshot = it
                widgets.refresh()
            }
        }
        setContent {
            val colors = homeColors(themeSettings, wallpaper, cachedAppearance.takeIf { awaitingWallpaper })
            SideEffect {
                cachedAppearance = HomeAppearance(colors.darkText, colors.accentSource)
                lastAppearance = cachedAppearance
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
                    if (widgets.failed) add("ウィジェットを読み込めません")
                    if (favoritesFailed) add("お気に入りを読み込めません")
                }
                val visibleSnapshot = remember(snapshot, runtime.targets, blocked) {
                    snapshot.copy(sections = snapshot.sections.mapNotNull { section ->
                        val visible = visibleApps(section.apps, runtime.targets, blocked)
                        if (visible.isEmpty()) null else section.copy(apps = visible)
                    })
                }
                val visibleWidgets = visibleWidgets(widgets.saved, widgets.providers.keys, runtime.targets, blocked)
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
                    onOpenClock = { openHeaderApp(Intent(AlarmClock.ACTION_SHOW_ALARMS), "時計のアプリが見つかりません") },
                    onOpenCalendar = { openHeaderApp(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                        Intent.CATEGORY_APP_CALENDAR), "カレンダーのアプリが見つかりません") },
                    scrollMotion = themeSettings.scrollMotion, animatorScale = animatorScale,
                    defaultHome = runtime.permissionState.home,
                    widgets = visibleWidgets,
                    widgetContent = { widget -> widgets.providers[widget.id]?.let { info ->
                        HostedWidget(widget, info, widgets, visibleWidgets.map { it.id }) { runtime.store.log("widget_create_failed") }
                    } },
                    onAddWidget = widgets::openPicker, widgetAddReason = widgets.addReason,
                )
                if (widgets.picker) WidgetPicker(runtime.targets, blocked, widgets.pending == null, widgets::add, { widgets.picker = false })
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
        refreshAnimatorScale()
        wallpaperObserver.refresh()
        widgets.refresh()
        // Check metadata without discarding temporarily unavailable favorites.
        repository.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        error = null
        if (intent.action == ACTION_ADD_WIDGET) widgets.openPicker() else widgets.picker = false
        query = ""
        homeListState.requestScrollToItem(0)
        window.decorView.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        homeRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        widgets.saveState(outState)
        outState.putString("query", query)
        cachedAppearance?.let {
            outState.putBoolean("homeDarkText", it.darkText)
            outState.putInt("homeAccentSource", it.accentSource)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    override fun onStop() {
        widgets.stopListening()
        super.onStop()
    }

    @Deprecated("Bridge for AppWidgetHost configuration request codes")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.dispatchConfigurationResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(animationScaleObserver)
        wallpaperObserver.close()
        repository.close()
        super.onDestroy()
    }

    companion object {
        // Reopening HOME in the same process also keeps the last colors without a saved Bundle.
        private var lastAppearance: HomeAppearance? = null
    }

    private fun refreshAnimatorScale() {
        animatorScale = try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            catch (_: RuntimeException) { 1f }
    }

    private fun openHeaderApp(intent: Intent, missingMessage: String) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { Toast.makeText(this, missingMessage, Toast.LENGTH_SHORT).show() }
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
