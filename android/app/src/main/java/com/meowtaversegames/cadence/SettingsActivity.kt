package com.meowtaversegames.cadence

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A2a settings, permission instructions, and debug controls. App enumeration uses the home worker. */
class SettingsActivity : ComponentActivity() {
    private val runtime by lazy { BlockingRuntime.get(this) }
    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var protectedPackages by mutableStateOf(emptySet<String>())
    private lateinit var repository: LauncherRepository
    private var favoritesFailed by mutableStateOf(false)
    private var homeTheme by mutableStateOf(HomeThemeSettings())
    private var homeThemeFailed by mutableStateOf(false)
    private var actionError by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        runtime.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LauncherRepository(this, loadIcons = false) { snapshot ->
            if (snapshot.failed) actionError = "アプリの一覧を読み込めませんでした"
            else apps = snapshot.apps
        }
        setContent {
            CadenceTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1_000)
                        if (hasWindowFocus() && runtime.decision?.nextBoundary?.let { java.time.Instant.now() >= it } == true) {
                            runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("設定", style = MaterialTheme.typography.headlineLarge) }
                    item { Text(runtime.statusText()) }
                    item {
                        if (runtime.readFailed) Text("設定を読み込めません。保存済みの設定を確認してください")
                        if (runtime.startFailed) Text("ブロックを始められませんでした")
                        if (runtime.launchFailed) Text("ブロック画面を出せませんでした。重ねて表示と端末独自の背景表示の設定を確認してください")
                        if (favoritesFailed) {
                            Text("お気に入りを読み込めません")
                            Text("保存済みのお気に入りのファイルを読み出せないため、空として表示しています。ブロックには影響しません。元のファイルを保護するため追加・並べ替えの保存は行いません。ホームへ戻ると再度読み込みます。")
                        }
                        actionError?.let { Text(it) }
                    }
                    item {
                        Text("見た目", style = MaterialTheme.typography.titleLarge)
                        if (homeThemeFailed) Text("見た目を読み込めません。元のファイルを保護するため保存できません")
                        ThemeChoices("文字の明暗", TextTone.entries, homeTheme.tone, !homeThemeFailed, { it.label }) {
                            saveHomeTheme(homeTheme.copy(tone = it))
                        }
                        ThemeChoices("アクセントの色", Accent.entries, homeTheme.accent, !homeThemeFailed, { it.label }) {
                            saveHomeTheme(homeTheme.copy(accent = it))
                        }
                        ThemeChoices("文字の大きさと行の間隔", HomeSize.entries, homeTheme.size, !homeThemeFailed, { it.label }) {
                            saveHomeTheme(homeTheme.copy(size = it))
                        }
                    }
                    items(PermissionKind.entries) { kind ->
                        val missing = kind in runtime.permissionState.missing()
                        Text("${kind.label}: ${if (missing) "必要です" else "許可済み"}")
                        if (kind == PermissionKind.EXACT && missing) Text("境目が遅れることがあります")
                        Button(onClick = { openSettings(Permissions.settings(this@SettingsActivity, kind)) }) { Text("設定を開く") }
                    }
                    if (!runtime.permissionState.notifications) item {
                        Text("通知が出ません（ブロックは動作します）")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }) { Text("通知の設定") }
                    }
                    item {
                        Text("電池の最適化から Cadence を除外してください。端末独自の電池管理でも背景の動作を許可してください。")
                        Text("Android 13 以降で許可できない場合は、アプリ情報の右上のメニューから「制限付き設定を許可」を選び、もう一度権限を付けてください。")
                        Text("端末メーカー独自の「背景からの画面の表示」「自動起動」などの権限も確認してください。")
                        Button(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("アプリ情報") }
                        Text("バージョン ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        DebugControls(runtime) { actionError = it }
                        Text("集中時間に止めるアプリ", style = MaterialTheme.typography.titleLarge)
                    }
                    items(apps, key = { it.id }) { app ->
                        val name = app.packageName
                        val protected = name in protectedPackages
                        Row {
                            Checkbox(checked = name in runtime.targets, enabled = !protected && !runtime.readFailed,
                                onCheckedChange = { checked ->
                                    // Read current targets at the tap, rather than a stale composition snapshot.
                                    if (name in Permissions.protectedPackages(this@SettingsActivity)) return@Checkbox
                                    val next = if (checked) runtime.targets + name else runtime.targets - name
                                    try {
                                        runtime.store.saveTargets(next)
                                        runtime.changed()
                                    } catch (_: Exception) { actionError = "設定を保存できませんでした" }
                                })
                            Text("${app.label}${if (protected) "（対象にできません）" else ""}")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        protectedPackages = Permissions.protectedPackages(this)
        favoritesFailed = runtime.store.readFavorites().failed
        val theme = runtime.store.homeTheme.read()
        homeTheme = theme.value ?: HomeThemeSettings()
        homeThemeFailed = theme.failed
        repository.refresh()
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }

    private fun saveHomeTheme(value: HomeThemeSettings) {
        try { runtime.store.homeTheme.save(value); homeTheme = value }
        catch (_: Exception) { actionError = "見た目を保存できませんでした。元のファイルは保護されています" }
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { actionError = "設定アプリから Cadence の権限を確認してください" }
        } catch (_: SecurityException) { actionError = "設定アプリから Cadence の権限を確認してください" }
    }
}

@Composable
private fun <T> ThemeChoices(title: String, options: List<T>, selected: T, enabled: Boolean,
    label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, modifier = Modifier.padding(top = 12.dp))
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text(label(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) },
                onClick = { onSelect(option); expanded = false }) }
        }
    }
}
