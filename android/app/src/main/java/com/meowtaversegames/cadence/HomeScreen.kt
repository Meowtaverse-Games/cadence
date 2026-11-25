package com.meowtaversegames.cadence

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

data class HomePresentation(
    val now: Instant,
    val status: String,
    val warnings: List<String> = emptyList(),
    val resting: String? = null,
    val notificationsMissing: Boolean = false,
)

@Composable
fun HomeScreen(
    presentation: HomePresentation,
    snapshot: LauncherSnapshot,
    favorites: List<String>,
    installedIds: Set<String>?,
    inventoryFailed: Boolean,
    targets: Set<String>,
    protectedPackages: Set<String>,
    targetsReadFailed: Boolean,
    homeRequest: Int,
    onLaunch: (AppEntry) -> Unit,
    onFavorites: (List<String>) -> Unit,
    onToggleTarget: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onSettings: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    size: HomeSize,
    defaultHome: Boolean = true,
) {
    var menu by remember(homeRequest) { mutableStateOf<AppEntry?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        onQueryChange(""); menu = null; focus.clearFocus(); keyboard?.hide()
        listState.requestScrollToItem(0)
    }
    LaunchedEffect(homeRequest) { focus.clearFocus(); keyboard?.hide() }
    BackHandler(enabled = consumesHomeBack(query.isNotEmpty(), defaultHome)) {
        if (query.isNotEmpty()) closeSearch()
    }
    val apps = snapshot.apps
    LaunchedEffect(apps) { if (menu?.id !in apps.map { it.id }) menu = null }
    val visibleFavoriteIds = visibleFavorites(favorites, apps).map { it.id }
    val layout = remember(snapshot.sections, favorites, query) { homeLayout(snapshot.sections, favorites, query) }
    val colors = LocalHomeColors.current
    fun changeQuery(value: String) {
        onQueryChange(value)
        listState.requestScrollToItem(0)
        if (value.isEmpty()) { focus.clearFocus(); keyboard?.hide() }
    }
    fun launchFirst() { firstSearchResult(query, layout.items)?.let(onLaunch) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 24.dp)) {
                items(layout.items, key = { it.key }) { item ->
                    when (item) {
                        is HomeItem.App -> AppRow(item.entry, snapshot.icons[item.entry.id], size,
                            { onLaunch(item.entry) }, { menu = item.entry })
                        is HomeItem.Heading -> Text(item.section.heading, Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge)
                        is HomeItem.Fixed -> when (item.key) {
                            "header" -> HomeHeader(presentation, onSettings)
                            "widgets" -> Unit
                            "favorites" -> Text("お気に入り", Modifier.padding(top = 20.dp, bottom = 12.dp),
                                style = MaterialTheme.typography.labelLarge)
                            "empty-favorites" -> Text(if (favorites.isEmpty())
                                "下の一覧から長押しで追加できます。最大8個まで並べられます。"
                                else "今表示できるお気に入りはありません", Modifier.padding(vertical = 16.dp))
                            "all-apps" -> Text(if (query.isEmpty()) "すべてのアプリ" else "検索結果",
                                Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
                            "empty-apps" -> Text("一致するアプリがありません", Modifier.padding(vertical = 24.dp))
                            else -> Unit
                        }
                    }
                }
            }
            SectionIndex(layout.index, Modifier.fillMaxHeight().width(44.dp)) { index ->
                listState.requestScrollToItem(layout.index[index].position)
            }
        }
        OutlinedTextField(value = query, onValueChange = ::changeQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.plate,
                unfocusedContainerColor = colors.plate),
            label = { Text("アプリを検索") },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = ::closeSearch) { Text("消去") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { launchFirst() }, onGo = { launchFirst() }))
    }
    menu?.let { app ->
      CadenceTheme {
        val isFavorite = app.id in favorites
        val full = !isFavorite && installedFavoriteCount(favorites, installedIds) >= FAVORITE_LIMIT
        val inventoryReady = installedIds != null && !inventoryFailed
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(app.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(enabled = !full && (isFavorite || inventoryReady), onClick = {
                        onFavorites(if (isFavorite) removeFavorite(favorites, app.id) else addFavorite(favorites, app.id, installedIds).ids)
                        menu = null
                    }) { Text(if (isFavorite) "お気に入りから外す" else if (full) "お気に入りは8個までです" else if (!inventoryReady) "一覧の読み込み後に追加できます" else "お気に入りに追加") }
                    if (isFavorite) {
                        TextButton(enabled = canMoveFavorite(visibleFavoriteIds, app.id, -1), onClick = {
                            onFavorites(moveVisibleFavorite(favorites, visibleFavoriteIds, app.id, -1)); menu = null
                        }) { Text("上へ") }
                        TextButton(enabled = canMoveFavorite(visibleFavoriteIds, app.id, 1), onClick = {
                            onFavorites(moveVisibleFavorite(favorites, visibleFavoriteIds, app.id, 1)); menu = null
                        }) { Text("下へ") }
                    }
                    if (app.packageName !in protectedPackages) TextButton(enabled = !targetsReadFailed, onClick = {
                        onToggleTarget(app); menu = null
                    }) { Text(if (app.packageName in targets) "集中時間に止めるアプリから外す" else "集中時間に止めるアプリにする") }
                    TextButton(onClick = { onAppInfo(app); menu = null }) { Text("アプリ情報") }
                    if (app.canUninstall) TextButton(onClick = { onUninstall(app); menu = null }) { Text("アンインストール") }
                }
            }, confirmButton = { TextButton(onClick = { menu = null }) { Text("閉じる") } })
      }
    }
}

@Composable
private fun HomeHeader(presentation: HomePresentation, onSettings: () -> Unit) {
    val colors = LocalHomeColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CADENCE", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp)
            TextButton(onClick = onSettings) { Text("設定") }
        }
        val localTime = presentation.now.atZone(ZoneId.systemDefault())
        Text(DateTimeFormatter.ofPattern("HH:mm").format(localTime), fontSize = 58.sp, fontWeight = FontWeight.Light)
        Text(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.JAPANESE).format(localTime))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
            Text(presentation.status, style = MaterialTheme.typography.titleMedium)
        }
        if (presentation.warnings.isNotEmpty()) {
            val first = presentation.warnings.first()
            val suffix = if (presentation.warnings.size > 1) " · ほか${presentation.warnings.size - 1}件" else ""
            Surface(color = colors.plate, shape = RoundedCornerShape(10.dp),
                onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text("!  $first$suffix  ›", Modifier.padding(12.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
        }
        if (presentation.notificationsMissing) TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) {
            Text("通知が出ません（ブロックは動作します）", style = MaterialTheme.typography.bodySmall)
        }
        presentation.resting?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AppRow(app: AppEntry, icon: ImageBitmap?, size: HomeSize, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = LocalHomeColors.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick,
        onLongClickLabel = "アプリのメニュー").heightIn(min = size.rowDp.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val iconModifier = Modifier.size(36.dp).shadow(3.dp, CircleShape, clip = false,
            ambientColor = colors.opposite, spotColor = colors.opposite)
        if (icon != null) Image(icon, contentDescription = null, modifier = iconModifier)
        else Box(iconModifier, contentAlignment = Alignment.Center) { Text(app.label.take(1)) }
        Text(app.label, fontSize = size.textSp.sp, lineHeight = size.lineHeightSp.sp, style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionIndex(entries: List<HomeIndexEntry>, modifier: Modifier, onSelect: (Int) -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val currentSelect by rememberUpdatedState(onSelect)
    val colors = LocalHomeColors.current
    val bubbleHeight = with(LocalDensity.current) { 56.dp.toPx() }
    val bubbleWidth = with(LocalDensity.current) { 104.dp.toPx() }
    fun select(y: Float) {
        indexAt(y, height.toFloat(), entries.size)?.let {
            selected = it; fingerY = y; currentSelect(it)
        }
    }
    Box(modifier.onSizeChanged { height = it.height.coerceAtLeast(1) }
        .semantics { contentDescription = "アプリの頭文字索引" }
        .pointerInput(entries, height) {
            detectDragGestures(onDragStart = { select(it.y) },
                onDragEnd = { selected = null }, onDragCancel = { selected = null },
                onDrag = { change, _ -> change.consume(); select(change.position.y) })
        }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            entries.forEachIndexed { index, entry ->
                Box(Modifier.weight(1f).fillMaxWidth().clickable { currentSelect(index) }
                    .semantics { contentDescription = if (index == 0) "先頭へ" else entry.label },
                    contentAlignment = Alignment.Center) {
                    Text(entry.label, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        selected?.let { index ->
            Box(Modifier.offset { IntOffset(-bubbleWidth.roundToInt(),
                (fingerY - bubbleHeight / 2).coerceIn(0f, (height - bubbleHeight).coerceAtLeast(0f)).roundToInt()) }
                .requiredWidth(104.dp).height(56.dp).background(colors.plate, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text(entries[index].label, color = colors.accent, fontSize = 28.sp, maxLines = 1)
            }
        }
    }
}
// Entirely synthetic launcher entries and status strings; previews never read installed apps or storage.
private val previewApps = listOf(
    AppEntry("synthetic.notes/Main", "synthetic.notes", "あおのノート", 1, "あ"),
    AppEntry("synthetic.clock/Main", "synthetic.clock", "かぜの時計", 2, "か"),
    AppEntry("synthetic.canvas/Main", "synthetic.canvas", "Canvas Sample", 3, "C"),
    AppEntry("synthetic.play/Main", "synthetic.play", "創作あそび", 4, "その他"),
)
private val previewSnapshot = LauncherSnapshot(previewApps.groupBy { it.bucket }.map { (id, apps) -> AppSection(id, apps.first().heading, apps) })

@Composable
private fun HomePreview(blocked: Boolean = false, warning: Boolean = false, empty: Boolean = false,
    darkText: Boolean = false, size: HomeSize = HomeSize.MEDIUM, query: String = "") {
    val colors = HomeColors(darkText, Color(0xFF398B81))
    Box(Modifier.background(if (darkText) Color(0xFFF2E5C9) else Color(0xFF182936))) {
    LauncherTheme(colors) {
        var search by remember { mutableStateOf(query) }
        val visible = if (blocked) previewSnapshot.copy(sections = previewSnapshot.sections.filterNot { it.bucket == 4 }) else previewSnapshot
        HomeScreen(HomePresentation(Instant.parse("2030-01-01T11:00:00Z"),
            if (blocked) "集中の時間 · 20:30 まで" else "自由時間 · 次の集中 20:30",
            if (warning) listOf("使用状況へのアクセスが必要です", "既定のホームアプリが必要です") else emptyList(),
            if (blocked) "1 個のアプリを 20:30 まで休ませています" else null, notificationsMissing = warning),
            visible, if (empty) emptyList() else previewApps.map { it.id }, previewApps.map { it.id }.toSet(), false,
            setOf("synthetic.play"), emptySet(), false, 0,
            {}, {}, {}, {}, {}, {}, search, { search = it }, rememberLazyListState(), size)
    }
}
}
@Preview(name = "暗い壁紙・白い文字", widthDp = 412, heightDp = 915)
@Composable private fun FreeHomePreview() = HomePreview()
@Preview(name = "ブロック中", widthDp = 412, heightDp = 915)
@Composable private fun FocusHomePreview() = HomePreview(blocked = true)
@Preview(name = "警告あり", widthDp = 412, heightDp = 915)
@Composable private fun WarningHomePreview() = HomePreview(warning = true)
@Preview(name = "お気に入りが空", widthDp = 412, heightDp = 915)
@Composable private fun EmptyHomePreview() = HomePreview(empty = true)
@Preview(name = "明るい壁紙・黒い文字", widthDp = 412, heightDp = 915)
@Composable private fun AllAppsPreview() = HomePreview(darkText = true)
@Preview(name = "検索", widthDp = 412, heightDp = 915)
@Composable private fun SearchHomePreview() = HomePreview(query = "ｃａｎｖａｓ")

@Preview(name = "文字サイズ大", widthDp = 412, heightDp = 915)
@Composable private fun LargeHomePreview() = HomePreview(size = HomeSize.LARGE)
