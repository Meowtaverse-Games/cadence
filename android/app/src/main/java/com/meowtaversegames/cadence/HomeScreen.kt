package com.meowtaversegames.cadence

import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.onClick
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
    onOpenClock: () -> Unit,
    onOpenCalendar: () -> Unit,
    scrollMotion: ScrollMotion = ScrollMotion.LOWER_LEFT,
    animatorScale: Float = 1f,
    defaultHome: Boolean = true,
    widgets: List<SavedWidget> = emptyList(),
    widgetContent: @Composable (SavedWidget) -> Unit = {},
    onAddWidget: () -> Unit = {},
    widgetAddReason: String? = null,
    favoritesFailed: Boolean = false,
    categories: List<HomeCategory> = emptyList(),
    categoriesFailed: Boolean = false,
    onCategories: (List<HomeCategory>) -> Unit = {},
    categoryApps: List<AppEntry> = snapshot.apps,
    blocked: Boolean = false,
) {
    var menu by remember(homeRequest) { mutableStateOf<AppEntry?>(null) }
    var menuCategory by remember(homeRequest) { mutableStateOf<String?>(null) }
    var membership by remember(homeRequest) { mutableStateOf<AppEntry?>(null) }
    val pager = rememberHomePager(categories.map { it.id }, homeRequest)
    val pageCount = categories.size + 1
    val realPage = realHomePage(pager.currentPage, pageCount)
    val scope = rememberCoroutineScope()
    val categoryLists = remember { mutableMapOf<String, LazyListState>() }
    SideEffect { categoryLists.keys.retainAll(categories.map { it.id }.toSet()) }
    var searchFocused by remember { mutableStateOf(false) }
    var indexing by remember { mutableStateOf(false) }
    val searching = query.isNotEmpty()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val layout = remember(snapshot.sections, favorites, query, widgets, favoritesFailed, searching) {
        homeLayout(snapshot.sections, favorites, query, widgets, favoritesFailed)
    }
    val motionState = rememberEntranceMotion(listState, layout.items,
        scrollMotion == ScrollMotion.LOWER_LEFT && animatorScale != 0f &&
            (searching || (realPage == 0 && !pager.isScrollInProgress)), homeRequest)
    fun jumpTo(position: Int) {
        motionState.suppress()
        listState.requestScrollToItem(position)
    }
    fun turnPage(direction: Int): Boolean {
        if (indexing || searchFocused) return false
        val destination = adjacentHomePage(pager.currentPage, direction, pageCount) ?: return false
        scope.launch { pager.animateScrollToPage(destination) }
        return true
    }
    fun closeSearch() {
        onQueryChange(""); menu = null; focus.clearFocus(); keyboard?.hide()
        if (searching) jumpTo(0)
    }
    LaunchedEffect(homeRequest) { focus.clearFocus(); keyboard?.hide() }
    BackHandler(enabled = consumesHomeBack(searching || searchFocused, defaultHome, realPage != 0)) {
        if (searching || searchFocused) closeSearch()
        else if (realPage != 0) scope.launch {
            pager.animateScrollToPage(nearestHomePage(pager.currentPage, 0, pageCount))
        }
    }
    val apps = snapshot.apps
    LaunchedEffect(apps) {
        if (menu?.id !in apps.map { it.id }) menu = null
        if (membership?.id !in apps.map { it.id }) membership = null
    }
    val visibleFavoriteIds = visibleFavorites(favorites, apps).map { it.id }
    val colors = LocalHomeColors.current
    fun changeQuery(value: String) {
        onQueryChange(value)
        jumpTo(0)
        if (value.isEmpty()) { focus.clearFocus(); keyboard?.hide() }
    }
    fun launchFirst() { firstSearchResult(query, layout.items)?.let(onLaunch) }
    val standardContent: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 24.dp)) {
                items(layout.items, key = { it.key }) { item ->
                    val moving = item !is HomeItem.Widget && item.key != "header"
                    val rowModifier = if (!moving) Modifier else Modifier.homeEntranceMotion(motionState, item.key)
                    Box(rowModifier.fillMaxWidth()) {
                        when (item) {
                            is HomeItem.Widget -> widgetContent(item.widget)
                            is HomeItem.App -> AppRow(item.entry, snapshot.icons[item.entry.id], size,
                                { onLaunch(item.entry) }, { menuCategory = null; menu = item.entry })
                            is HomeItem.Heading -> Text(item.section.heading, Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge)
                            is HomeItem.Fixed -> when (item.key) {
                                "header" -> HomeHeader(presentation, onSettings, onOpenClock, onOpenCalendar, onAddWidget, widgetAddReason)
                                "empty-favorites" -> Text("下の一覧から長押しで追加できます", Modifier.padding(vertical = 16.dp))
                                "all-apps" -> Text(if (!searching) "すべてのアプリ" else "検索結果",
                                    Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
                                "empty-apps" -> Text("一致するアプリがありません", Modifier.padding(vertical = 24.dp))
                                else -> Unit
                            }
                        }
                    }
                }
            }
            SectionIndex(layout.index, Modifier.fillMaxHeight().width(44.dp), { indexing = it }) { index ->
                jumpTo(layout.index[index].position)
            }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(Modifier.weight(1f)) {
            if (searching) standardContent()
            else HorizontalPager(state = pager, userScrollEnabled = pageCount > 1 && !indexing && !searchFocused,
                modifier = Modifier.fillMaxSize().semantics {
                    collectionInfo = CollectionInfo(1, pageCount)
                    horizontalScrollAxisRange = ScrollAxisRange(
                        value = { realHomePage(pager.currentPage, pageCount).toFloat() },
                        maxValue = { (pageCount - 1).toFloat() })
                    if (pageCount > 1) {
                        if (!indexing && !searchFocused) {
                            customActions = listOf(
                                CustomAccessibilityAction("前のページ") { turnPage(-1) },
                                CustomAccessibilityAction("次のページ") { turnPage(1) },
                            )
                        }
                        scrollToIndex { page ->
                            if (page !in 0 until pageCount) false else {
                                scope.launch { pager.animateScrollToPage(nearestHomePage(pager.currentPage, page, pageCount)) }
                                true
                            }
                        }
                    }
                }) { virtualPage ->
                val page = realHomePage(virtualPage, pageCount)
                // Prefetch may compose another copy of this real page. Only its nearest
                // virtual occurrence may own a LazyListState or create widget host views.
                val ownsContent by remember(pager, virtualPage, pageCount) {
                    derivedStateOf {
                        ownsHomePageContent(virtualPage, pager.currentPage, pager.currentPageOffsetFraction, pageCount)
                    }
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val motion = homePageMotion((pager.currentPage - virtualPage) + pager.currentPageOffsetFraction, animatorScale)
                    scaleX = motion.scale; scaleY = motion.scale; alpha = motion.alpha
                }) {
                    if (ownsContent) {
                        if (page == 0) standardContent()
                        else categories.getOrNull(page - 1)?.let { category ->
                            val categoryList = remember(category.id) { categoryLists.getOrPut(category.id) { LazyListState() } }
                            CategoryPage(category, snapshot, categoryList, presentation, size,
                                categoryApps, targets, blocked,
                                scrollMotion == ScrollMotion.LOWER_LEFT && animatorScale != 0f &&
                                    pager.currentPage == virtualPage && !pager.isScrollInProgress,
                                homeRequest, onLaunch, { menuCategory = category.id; menu = it },
                                onSettings, onOpenClock, onOpenCalendar, onAddWidget, widgetAddReason)
                        }
                    }
                }
            }
        }
        if (pageCount > 1) {
            val pageName = categories.getOrNull(realPage - 1)?.name ?: "ホーム"
            Row(Modifier.align(Alignment.CenterHorizontally).horizontalScroll(rememberScrollState())
                .padding(top = 6.dp).semantics { contentDescription = "ページ ${realPage + 1} / $pageCount、$pageName" },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pageCount) { page ->
                    Box(Modifier.size(6.dp).background(if (page == realPage) colors.accent else colors.text.copy(alpha = 0.4f), CircleShape))
                }
            }
        }
        OutlinedTextField(value = query, onValueChange = ::changeQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .onFocusChanged { searchFocused = it.isFocused }, singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.plate,
                unfocusedContainerColor = colors.plate),
            label = { Text("アプリを検索") },
            trailingIcon = { if (searching) TextButton(onClick = ::closeSearch) { Text("消去") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { launchFirst() }, onGo = { launchFirst() }))
    }
    menu?.let { app ->
      CadenceTheme {
        val isFavorite = app.id in favorites
        val inventoryReady = installedIds != null && !inventoryFailed
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(app.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(enabled = isFavorite || inventoryReady, onClick = {
                        onFavorites(if (isFavorite) removeFavorite(favorites, app.id) else addFavorite(favorites, app.id, installedIds))
                        menu = null
                    }) { Text(if (isFavorite) "お気に入りから外す" else if (!inventoryReady) "一覧の読み込み後に追加できます" else "お気に入りに追加") }
                    TextButton(enabled = !categoriesFailed, onClick = { membership = app; menu = null }) { Text("カテゴリに入れる…") }
                    val category = categories.firstOrNull { it.id == menuCategory }
                    if (category != null) {
                        val visibleIds = visibleFavorites(category.appIds, apps).map { it.id }
                        listOf(-1 to "上へ", 1 to "下へ").forEach { (direction, label) ->
                            TextButton(enabled = !categoriesFailed && canMoveFavorite(visibleIds, app.id, direction), onClick = {
                                onCategories(updateCategory(categories, category.id) {
                                    it.copy(appIds = moveVisibleFavorite(it.appIds, visibleIds, app.id, direction))
                                }); menu = null
                            }) { Text(label) }
                        }
                        TextButton(enabled = !categoriesFailed, onClick = {
                            onCategories(updateCategory(categories, category.id) { it.copy(appIds = removeFavorite(it.appIds, app.id)) })
                            menu = null
                        }) { Text("このカテゴリから外す") }
                    }
                    if (isFavorite && category == null) {
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
    membership?.let { app -> CadenceTheme {
        CategoryMembershipDialog(app, categories, installedIds.takeUnless { inventoryFailed }, categoriesFailed,
            onCategories, { membership = null })
    } }
}

@Composable
internal fun HomeHeader(presentation: HomePresentation, onSettings: () -> Unit, onOpenClock: () -> Unit,
    onOpenCalendar: () -> Unit, onAddWidget: () -> Unit, addReason: String?, compact: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    if (menu) CadenceTheme {
        AlertDialog(onDismissRequest = { menu = false }, text = {
            TextButton(enabled = addReason == null, onClick = { menu = false; onAddWidget() }) {
                Text(addReason ?: "ウィジェットを追加")
            }
        }, confirmButton = { TextButton(onClick = { menu = false }) { Text("閉じる") } })
    }
    val colors = LocalHomeColors.current
    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
        detectTapGestures(onLongPress = { menu = true })
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CADENCE", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, letterSpacing = 4.sp)
            SettingsGear(onSettings)
        }
        val localTime = presentation.now.atZone(ZoneId.systemDefault())
        val time = DateTimeFormatter.ofPattern("HH:mm").format(localTime)
        val date = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.JAPANESE).format(localTime)
        if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(time, Modifier.heightIn(min = 48.dp).wrapContentHeight().combinedClickable(
                onClick = onOpenClock, onLongClick = { menu = true }, onLongClickLabel = "ウィジェットを追加"))
            Text("· ${localTime.monthValue}/${localTime.dayOfMonth} ·", Modifier.heightIn(min = 48.dp).wrapContentHeight()
                .combinedClickable(onClick = onOpenCalendar, onLongClick = { menu = true }, onLongClickLabel = "ウィジェットを追加")
                .semantics { contentDescription = "$date、カレンダーを開く" })
            Text(presentation.status, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        } else {
        Text(time, Modifier.combinedClickable(onClick = onOpenClock, onLongClick = { menu = true },
            onLongClickLabel = "ウィジェットを追加").semantics(mergeDescendants = true) {
                contentDescription = "$time、時計を開く"
            }, fontSize = 58.sp, fontWeight = FontWeight.Light)
        Text(date, Modifier.combinedClickable(
            onClick = onOpenCalendar, onLongClick = { menu = true }, onLongClickLabel = "ウィジェットを追加")
            .heightIn(min = 48.dp).wrapContentHeight().semantics(mergeDescendants = true) { contentDescription = "$date、カレンダーを開く" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
            Text(presentation.status, style = MaterialTheme.typography.titleMedium)
        }
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
internal fun AppRow(app: AppEntry, icon: ImageBitmap?, size: HomeSize, onClick: () -> Unit, onLongClick: () -> Unit) {
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
private fun SectionIndex(entries: List<HomeIndexEntry>, modifier: Modifier, onTouch: (Boolean) -> Unit, onSelect: (Int) -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentTouch by rememberUpdatedState(onTouch)
    DisposableEffect(Unit) { onDispose { currentTouch(false) } }
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
            // Reserve the index on DOWN, but retain the original drag-slop selection gate.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                currentTouch(true)
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val delta = change.position - down.position
                        if (!dragging) dragging = startsIndexDrag(delta.x, delta.y, viewConfiguration.touchSlop)
                        if (dragging) select(change.position.y)
                    }
                } finally { selected = null; currentTouch(false) }
            }
        }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            entries.forEachIndexed { index, entry ->
                Box(Modifier.weight(1f).fillMaxWidth()
                    .semantics {
                        contentDescription = if (index == 0) "先頭へ" else entry.label
                        onClick { currentSelect(index); true }
                    },
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
            {}, {}, {}, {}, {}, {}, search, { search = it }, rememberLazyListState(), size,
            onOpenClock = {}, onOpenCalendar = {})
    }
}
}
@Preview(name = "歯車・見出しなしのお気に入り", widthDp = 412, heightDp = 915)
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
