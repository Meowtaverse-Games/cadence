package com.meowtaversegames.cadence

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private data class WidgetChoice(val info: AppWidgetProviderInfo, val app: String, val label: String)
private data class WidgetChoices(val values: List<WidgetChoice> = emptyList(), val error: Boolean = false, val loading: Boolean = true)

@Composable
fun WidgetPicker(targets: Set<String>, blocked: Boolean, enabled: Boolean, onSelect: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit) {
    val context = LocalContext.current
    val choices by produceState(WidgetChoices(), context) {
        value = withContext(Dispatchers.IO) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                var failed = false
                val entries = mapAvailableItems(manager.getInstalledProvidersForProfile(Process.myUserHandle()),
                    { failed = true }) { info ->
                    WidgetChoice(info, context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString(),
                        info.loadLabel(context.packageManager))
                }
                val collator = Collator.getInstance(Locale.JAPANESE)
                WidgetChoices(entries.sortedWith(Comparator { a, b ->
                    collator.compare(a.app, b.app).takeIf { it != 0 }
                        ?: a.info.provider.packageName.compareTo(b.info.provider.packageName).takeIf { it != 0 }
                        ?: collator.compare(a.label, b.label).takeIf { it != 0 }
                        ?: a.info.provider.flattenToString().compareTo(b.info.provider.flattenToString())
                }), error = failed, loading = false)
            } catch (_: Exception) { WidgetChoices(error = true, loading = false) }
        }
    }
    BackHandler(onBack = onClose)
    CadenceTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("ウィジェットを追加", style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = onClose) { Text("閉じる") } }
                if (choices.loading) item { Text("読み込み中…") }
                if (choices.error) item { Text("一部のウィジェットを読み込めませんでした") }
                val visible = choices.values.filterNot { blocked && it.info.provider.packageName in targets }
                if (!choices.loading && visible.isEmpty()) item { Text("選べるウィジェットはありません") }
                visible.groupBy { it.info.provider.packageName }.forEach { (name, group) ->
                    item(key = "app-$name") { Text(group.first().app, style = MaterialTheme.typography.titleLarge) }
                    items(group, key = { it.info.provider.flattenToString() }) { choice ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(choice.info) }.padding(vertical = 8.dp)) {
                            WidgetPreview(choice.info)
                            Text(choice.label, style = MaterialTheme.typography.titleMedium)
                            val density = LocalDensity.current.density
                            Text("目安: ${(choice.info.minWidth / density).toInt()} × ${(choice.info.minHeight / density).toInt()} dp")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val width = with(density) { 240.dp.roundToPx() }
    val height = with(density) { 112.dp.roundToPx() }
    val preview by produceState<ImageBitmap?>(null, info.provider, width, height) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = try { info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi) }
                    catch (_: RuntimeException) { null }
                    ?: context.packageManager.getApplicationIcon(info.provider.packageName)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val scale = minOf(width.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1),
                    height.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1))
                val w = (drawable.intrinsicWidth.coerceAtLeast(1) * scale).toInt().coerceAtLeast(1)
                val h = (drawable.intrinsicHeight.coerceAtLeast(1) * scale).toInt().coerceAtLeast(1)
                drawable.setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    preview?.let { Image(it, contentDescription = null, modifier = Modifier.size(240.dp, 112.dp)) }
}

@Composable
fun HostedWidget(widget: SavedWidget, info: AppWidgetProviderInfo, controller: WidgetController,
    visibleIds: List<Int>, log: () -> Unit) {
    val density = LocalDensity.current.density
    val screenHeight = LocalConfiguration.current.screenHeightDp.toFloat()
    val policy = info.heightPolicy(density)
    val height = if (policy.vertical) widget.height.coerceIn(policy.bounds(screenHeight)) else widget.height
    var menu by remember(widget.id) { mutableStateOf(false) }
    var resizing by remember(widget.id) { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        AndroidView(modifier = Modifier.fillMaxWidth().height(height.dp).onSizeChanged { width = it.width / density },
            factory = { context ->
                try { controller.host.createView(context, widget.id, info) }
                catch (_: RuntimeException) {
                    log()
                    TextView(context).apply {
                        text = "読み込めません"
                        setOnLongClickListener { menu = true; true }
                    }
                }
            }, update = { view ->
                (view as? CadenceWidgetView)?.apply {
                    onHold = { menu = true }
                    reportSize(width, height)
                }
            })
    }
    if (menu) CadenceTheme {
        AlertDialog(onDismissRequest = { menu = false }, title = { Text("ウィジェット") }, text = {
            Column {
                TextButton(onClick = { controller.remove(widget); menu = false }) { Text("外す") }
                if (policy.vertical) TextButton(onClick = { menu = false; resizing = true }) { Text("高さを変える") }
                TextButton(enabled = canMoveWidget(visibleIds, widget.id, -1), onClick = {
                    controller.move(widget, -1); menu = false
                }) { Text("上へ") }
                TextButton(enabled = canMoveWidget(visibleIds, widget.id, 1), onClick = {
                    controller.move(widget, 1); menu = false
                }) { Text("下へ") }
                if (info.canReconfigure()) TextButton(onClick = { controller.reconfigure(widget); menu = false }) { Text("設定し直す") }
            }
        }, confirmButton = { TextButton(onClick = { menu = false }) { Text("閉じる") } })
    }
    if (resizing) CadenceTheme {
        AlertDialog(onDismissRequest = { resizing = false }, title = { Text("高さを変える") }, text = {
            Column {
                Text("${height.toInt()} dp")
                TextButton(enabled = height > policy.bounds(screenHeight).start, onClick = {
                    controller.resize(widget, policy.step(height, -1, screenHeight))
                }) { Text("低くする") }
                TextButton(enabled = height < policy.bounds(screenHeight).endInclusive, onClick = {
                    controller.resize(widget, policy.step(height, 1, screenHeight))
                }) { Text("高くする") }
            }
        }, confirmButton = { TextButton(onClick = { resizing = false }) { Text("完了") } })
    }
}
