package com.meowtaversegames.cadence

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.delay

/** Temporary A2a UI; launcher design and interactions belong to A2b. */
open class CadenceActivity : ComponentActivity() {
    protected val runtime by lazy { BlockingRuntime.get(this) }
    private var apps by mutableStateOf<List<LauncherActivityInfo>>(emptyList())
    private var actionError by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        runtime.refreshPermissions()
    }
    protected open val settingsPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) {
                    // Refresh the clock/status while visible; OS alarms remain responsible in background.
                    while (true) {
                        delay(1_000)
                        if (hasWindowFocus() && runtime.decision?.nextBoundary?.let { java.time.Instant.now() >= it } == true) {
                            runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
                        }
                    }
                }
                var selected by remember { mutableStateOf(runtime.targets) }
                LaunchedEffect(runtime.targets, runtime.decision) { selected = runtime.targets }
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(if (settingsPage) "設定" else "Cadence", style = MaterialTheme.typography.headlineLarge) }
                    item { Text(runtime.statusText()) }
                    item {
                        if (runtime.readFailed) Text("設定を読み込めません。保存済みの設定を確認してください")
                        if (runtime.startFailed) Text("ブロックを始められませんでした")
                        if (runtime.launchFailed) Text("ブロック画面を出せませんでした。重ねて表示と端末独自の背景表示の設定を確認してください")
                        actionError?.let { Text(it) }
                    }
                    val permissionKinds = if (settingsPage) PermissionKind.entries else runtime.permissionState.missing()
                    items(permissionKinds) { kind ->
                        val missing = kind in runtime.permissionState.missing()
                        Text("${kind.label}: ${if (missing) "必要です" else "許可済み"}")
                        if (kind == PermissionKind.EXACT && missing) Text("境目が遅れることがあります")
                        Button(onClick = { openSettings(Permissions.settings(this@CadenceActivity, kind)) }) { Text("設定を開く") }
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
                    if (!settingsPage) item {
                        Button(onClick = { startActivity(Intent(this@CadenceActivity, SettingsActivity::class.java)) }) { Text("設定") }
                    }
                    if (settingsPage) {
                        item {
                            Text("電池の最適化から Cadence を除外してください。端末独自の電池管理でも背景の動作を許可してください。")
                            Text("Android 13 以降で許可できない場合は、アプリ情報の右上のメニューから「制限付き設定を許可」を選び、もう一度権限を付けてください。")
                            Text("端末メーカー独自の「背景からの画面の表示」「自動起動」などの権限も確認してください。")
                            Button(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("アプリ情報") }
                            Text("バージョン ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            DebugControls(runtime) { actionError = it }
                            Text("集中時間に止めるアプリ")
                        }
                    } else item { Text("アプリ") }
                    items(apps, key = { it.componentName.flattenToString() }) { app ->
                        val name = app.componentName.packageName
                        if (settingsPage) {
                            val protected = name in Permissions.protectedPackages(this@CadenceActivity)
                            Row {
                                Checkbox(checked = name in selected, enabled = !protected && !runtime.readFailed,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) selected + name else selected - name
                                        try {
                                            runtime.store.saveTargets(next)
                                            selected = next
                                            runtime.changed()
                                        } catch (_: Exception) { actionError = "設定を保存できませんでした" }
                                    })
                                Text("${app.label}${if (protected) "（対象にできません）" else ""}")
                            }
                        } else TextButton(onClick = {
                            try {
                                getSystemService(LauncherApps::class.java).startMainActivity(app.componentName, Process.myUserHandle(), null, null)
                            } catch (_: RuntimeException) { actionError = "アプリを開けませんでした" }
                        }) { Text(app.label.toString()) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        val collator = Collator.getInstance(Locale.JAPANESE)
        apps = getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
            .sortedWith { a, b -> collator.compare(a.label.toString(), b.label.toString()) }
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: RuntimeException) { actionError = "設定アプリから Cadence の権限を確認してください" }
        } catch (_: SecurityException) { actionError = "設定アプリから Cadence の権限を確認してください" }
    }
}

class MainActivity : CadenceActivity()
class SettingsActivity : CadenceActivity() {
    override val settingsPage = true
}
