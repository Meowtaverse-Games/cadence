package com.meowtaversegames.cadence

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import java.util.Random

private class PagerMemory {
    var ids: List<String> = emptyList()
    var request: Int = 0
    var pager: PagerState? = null
}

/** Rebuild the virtual coordinates atomically while retaining the logical category identity.
 * HOME creates a centered standard page immediately, without a launched scroll coroutine. */
@Composable
fun rememberHomePager(categoryIds: List<String>, homeRequest: Int): PagerState {
    val memory = remember { PagerMemory() }
    return remember(categoryIds, homeRequest) {
        val previousId = memory.pager?.let { homePageId(it.currentPage, memory.ids) }
        val page = if (memory.request != homeRequest) 0 else retainedHomePage(previousId, categoryIds)
        val count = categoryIds.size + 1
        PagerState(currentPage = if (count == 1) 0 else centeredHomePage(page, count), pageCount = {
            if (count == 1) 1 else VIRTUAL_HOME_PAGES
        }).also { memory.ids = categoryIds; memory.request = homeRequest; memory.pager = it }
    }
}

// One small generated tile per process. No wallpaper access or allocation during drawing.
private val glassNoise by lazy {
    val random = Random(47)
    val pixels = IntArray(64 * 64) { if (random.nextBoolean()) android.graphics.Color.WHITE else android.graphics.Color.BLACK }
    ShaderBrush(ImageShader(Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888).asImageBitmap(),
        TileMode.Repeated, TileMode.Repeated))
}

fun Modifier.homeEntranceMotion(state: EntranceMotionState, key: String): Modifier = graphicsLayer {
    val motion = state.motion(key)
    translationX = size.width * motion.translationXFraction
    translationY = size.height * motion.translationYFraction
    scaleX = motion.scale; scaleY = motion.scale
    alpha = motion.alpha; rotationZ = motion.rotation
    transformOrigin = TransformOrigin(0f, 1f)
}

@Composable
fun CategoryPage(category: HomeCategory, snapshot: LauncherSnapshot, listState: LazyListState,
    presentation: HomePresentation, size: HomeSize, hasBlockedApps: Boolean, motionEnabled: Boolean, homeRequest: Int,
    onLaunch: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit,
    onSettings: () -> Unit, onOpenClock: () -> Unit, onOpenCalendar: () -> Unit,
    onAddWidget: () -> Unit, widgetAddReason: String?) {
    val apps = visibleFavorites(category.appIds, snapshot.apps)
    val rows = remember(apps) { apps.map { HomeItem.App(it) } }
    val motion = rememberEntranceMotion(listState, rows, motionEnabled, homeRequest)
    val colors = LocalHomeColors.current
    val shape = RoundedCornerShape(CategoryGlass.cornerDp.dp)
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 52.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeHeader(presentation, onSettings, onOpenClock, onOpenCalendar, onAddWidget, widgetAddReason, compact = true)
        Column(Modifier.weight(1f).fillMaxWidth().clip(shape)
            .background(Color(0xFF000000.toInt() or CategoryGlass.plateRgb(colors.darkText)).copy(alpha = CategoryGlass.plateAlpha))
            .background(Brush.verticalGradient(listOf(colors.text.copy(alpha = CategoryGlass.highlightAlpha), Color.Transparent)))
            .drawWithCache { val noise = glassNoise; onDrawBehind { drawRect(noise, alpha = CategoryGlass.noiseAlpha) } }
            .border(CategoryGlass.borderDp.dp, Brush.verticalGradient(listOf(
                colors.text.copy(alpha = CategoryGlass.borderTopAlpha), colors.text.copy(alpha = CategoryGlass.borderBottomAlpha))), shape)
            .padding(horizontal = 16.dp)) {
            Text(category.name, Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.headlineMedium)
            if (apps.isEmpty()) Text(
                if (hasBlockedApps) "集中時間中のアプリは隠れています"
                else "アプリを長押しして「カテゴリに入れる」を選ぶと、ここに並びます",
                Modifier.padding(bottom = 16.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows, key = { it.key }) { row ->
                    Box(Modifier.homeEntranceMotion(motion, row.key)) {
                        AppRow(row.entry, snapshot.icons[row.entry.id], size, { onLaunch(row.entry) }, { onMenu(row.entry) })
                    }
                }
            }
        }
    }
}
