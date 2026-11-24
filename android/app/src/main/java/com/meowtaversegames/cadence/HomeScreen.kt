package com.meowtaversegames.cadence

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.unit.Velocity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val FocusAccent = Color(0xFF79D8BE)

@Composable
fun CadenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = FocusAccent, onPrimary = Color(0xFF102921),
        secondary = FocusAccent, tertiary = FocusAccent,
        background = Color(0xFF111B20), surface = Color(0xFF1D292E),
        onSurface = Color(0xFFF1F4F3), onBackground = Color(0xFFF1F4F3),
        onSurfaceVariant = Color(0xFFBAC7C7),
    )) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}

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
    drawer: Boolean,
    query: String,
    onDrawerChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    defaultHome: Boolean = true,
) {
    var menu by remember(homeRequest) { mutableStateOf<AppEntry?>(null) }
    val focus = LocalFocusManager.current
    fun closeDrawer() { onDrawerChange(false); onQueryChange(""); menu = null; focus.clearFocus() }
    // Activity resets drawer/query synchronously on HOME; this effect only dismisses the keyboard.
    LaunchedEffect(homeRequest) { focus.clearFocus() }
    BackHandler(enabled = consumesHomeBack(drawer, defaultHome)) { if (drawer) closeDrawer() }
    val apps = snapshot.apps
    LaunchedEffect(apps) { if (menu?.id !in apps.map { it.id }) menu = null }
    val favoriteApps = favorites.mapNotNull { id -> apps.find { it.id == id } }
    val visibleFavoriteIds = favoriteApps.map { it.id }
    val onOpenDrawer by rememberUpdatedState { onDrawerChange(true) }
    val onCloseDrawer by rememberUpdatedState { closeDrawer() }
    val sections = remember(snapshot.sections, query) {
        snapshot.sections.mapNotNull { section ->
            val matching = section.apps.filter { matchesApp(it.label, query) }
            if (matching.isEmpty()) null else section.copy(apps = matching)
        }
    }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val headerSwipe = Modifier.pointerInput(drawer, swipeThreshold) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
            distance += amount
            if (!drawer && distance < -swipeThreshold) { change.consume(); onOpenDrawer() }
            if (drawer && distance > swipeThreshold) { change.consume(); onCloseDrawer() }
        })
    }
    Column(Modifier.fillMaxSize().background(Color(0xDB101A20)).safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxWidth().then(headerSwipe).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CADENCE", Modifier.weight(1f), color = FocusAccent,
                    style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp)
                TextButton(onClick = onSettings) { Text("設定") }
            }
            val localTime = presentation.now.atZone(ZoneId.systemDefault())
            Text(DateTimeFormatter.ofPattern("HH:mm").format(localTime),
                fontSize = if (drawer) 32.sp else 58.sp, fontWeight = FontWeight.Light)
            if (!drawer) Text(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.JAPANESE).format(localTime),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(presentation.status, color = FocusAccent, style = MaterialTheme.typography.titleMedium)
            if (presentation.warnings.isNotEmpty()) {
                // One row, regardless of how many issues exist. All details remain in Settings.
                val first = presentation.warnings.first()
                val suffix = if (presentation.warnings.size > 1) " · ほか${presentation.warnings.size - 1}件" else ""
                Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(10.dp),
                    onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("!  $first$suffix  ›", Modifier.padding(12.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
            }
            if (presentation.notificationsMissing) {
                TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) {
                    Text("通知が出ません（ブロックは動作します）", style = MaterialTheme.typography.bodySmall)
                }
            }
            presentation.resting?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (drawer) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { closeDrawer() }) { Text("戻る") }
                OutlinedTextField(value = query, onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("アプリを検索") },
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("消去") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(query) { listState.scrollToItem(0) }
            val nested = remember(listState, swipeThreshold) {
                object : NestedScrollConnection {
                    var pull = 0f
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        pull = 0f
                        return Velocity.Zero
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && !listState.canScrollBackward && available.y > 0) {
                            pull += available.y
                            if (pull > swipeThreshold) { pull = 0f; onCloseDrawer() }
                        } else pull = 0f
                        return Offset.Zero
                    }
                }
            }
            Row(Modifier.weight(1f).padding(top = 12.dp)) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight().nestedScroll(nested),
                    contentPadding = PaddingValues(start = 28.dp, end = 8.dp, bottom = 24.dp)) {
                    if (sections.isEmpty()) item { Text("一致するアプリがありません", Modifier.padding(vertical = 24.dp)) }
                    for (section in sections) {
                        item(key = "heading-${section.bucket}") {
                            Text(section.heading, Modifier.padding(vertical = 12.dp), color = FocusAccent,
                                style = MaterialTheme.typography.labelLarge)
                        }
                        items(section.apps, key = { it.id }) { app ->
                            AppRow(app, snapshot.icons[app.id], { onLaunch(app) }, { menu = app })
                        }
                    }
                }
                // Use the same sections as LazyColumn, including search filtering and hidden targets.
                val offsets = buildList {
                    var offset = 0
                    for (section in sections) { add(offset); offset += section.apps.size + 1 }
                }
                var indexScroll by remember { mutableStateOf<Job?>(null) }
                SectionIndex(sections, Modifier.fillMaxHeight().width(44.dp)) { index ->
                    indexScroll?.cancel()
                    indexScroll = scope.launch { listState.scrollToItem(offsets[index]) }
                }
            }
        } else {
            val favoriteListState = rememberLazyListState()
            val favoriteSwipe = remember(favoriteListState, swipeThreshold) {
                object : NestedScrollConnection {
                    var pull = 0f
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        pull = 0f
                        return Velocity.Zero
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        // Scroll the list first. Unconsumed upward drag at the bottom opens the drawer,
                        // including drags over rows and the list's empty space.
                        if (source == NestedScrollSource.UserInput && !favoriteListState.canScrollForward && available.y < 0) {
                            pull -= available.y
                            if (pull > swipeThreshold) { pull = 0f; onOpenDrawer() }
                            return Offset(0f, available.y)
                        }
                        pull = 0f
                        return Offset.Zero
                    }
                }
            }
            LazyColumn(state = favoriteListState,
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(favoriteSwipe), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) {
                item { Text("お気に入り", Modifier.padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge) }
                if (favorites.isEmpty()) item {
                    Text("よく使うアプリを、ここに。", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                    Text("「すべてのアプリ」でアプリを長押しして、お気に入りに追加できます。最大8個まで並べられます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 26.sp)
                }
                else if (favoriteApps.isEmpty()) item {
                    Text("今表示できるお気に入りはありません", Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(favoriteApps, key = { it.id }) { app ->
                    AppRow(app, snapshot.icons[app.id], { onLaunch(app) }, { menu = app })
                }
            }
            TextButton(onClick = { onOpenDrawer() }, modifier = Modifier.fillMaxWidth().then(headerSwipe).padding(horizontal = 28.dp, vertical = 12.dp)) {
                Text("↑  すべてのアプリ", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    menu?.let { app ->
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

@Composable
private fun AppRow(app: AppEntry, icon: ImageBitmap?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick,
        onLongClickLabel = "アプリのメニュー").heightIn(min = 68.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
        else Box(Modifier.size(36.dp).background(FocusAccent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), color = FocusAccent)
        }
        Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionIndex(sections: List<AppSection>, modifier: Modifier, onSelect: (Int) -> Unit) {
    if (sections.isEmpty()) return
    var height by remember { mutableIntStateOf(1) }
    fun select(y: Float) {
        val index = (y / height * sections.size).toInt().coerceIn(sections.indices)
        onSelect(index)
    }
    Column(modifier.onSizeChanged { height = it.height.coerceAtLeast(1) }
        .semantics { contentDescription = "アプリの頭文字索引" }
        .pointerInput(sections, height) {
            detectDragGestures(onDragStart = { select(it.y) }, onDrag = { change, _ ->
                change.consume(); select(change.position.y)
            })
        }, horizontalAlignment = Alignment.CenterHorizontally) {
        sections.forEachIndexed { index, section ->
            Box(Modifier.weight(1f).fillMaxWidth().clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(section.heading, color = FocusAccent, fontSize = 10.sp, maxLines = 1)
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
    drawer: Boolean = false, query: String = "") {
    CadenceTheme {
        var showDrawer by remember { mutableStateOf(drawer) }
        var search by remember { mutableStateOf(query) }
        val visible = if (blocked) previewSnapshot.copy(sections = previewSnapshot.sections.filterNot { it.bucket == 4 }) else previewSnapshot
        HomeScreen(HomePresentation(Instant.parse("2030-01-01T11:00:00Z"),
            if (blocked) "集中の時間 · 20:30 まで" else "自由時間 · 次の集中 20:30",
            if (warning) listOf("使用状況へのアクセスが必要です", "既定のホームアプリが必要です") else emptyList(),
            if (blocked) "1 個のアプリを 20:30 まで休ませています" else null, notificationsMissing = warning),
            visible, if (empty) emptyList() else previewApps.map { it.id }, previewApps.map { it.id }.toSet(), false,
            setOf("synthetic.play"), emptySet(), false, 0,
            {}, {}, {}, {}, {}, {}, showDrawer, search, { showDrawer = it }, { search = it })
    }
}
@Preview(name = "自由時間", widthDp = 412, heightDp = 915)
@Composable private fun FreeHomePreview() = HomePreview()
@Preview(name = "ブロック中", widthDp = 412, heightDp = 915)
@Composable private fun FocusHomePreview() = HomePreview(blocked = true)
@Preview(name = "警告あり", widthDp = 412, heightDp = 915)
@Composable private fun WarningHomePreview() = HomePreview(warning = true)
@Preview(name = "お気に入りが空", widthDp = 412, heightDp = 915)
@Composable private fun EmptyHomePreview() = HomePreview(empty = true)
@Preview(name = "すべてのアプリ", widthDp = 412, heightDp = 915)
@Composable private fun AllAppsPreview() = HomePreview(drawer = true)
@Preview(name = "検索", widthDp = 412, heightDp = 915)
@Composable private fun SearchHomePreview() = HomePreview(drawer = true, query = "ｃａｎｖａｓ")
