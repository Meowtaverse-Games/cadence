package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun emptyQueryKeepsHeaderAndFavoritesUntilTextIsEntered() {
        val app = AppEntry("synthetic.alpha/Main", "synthetic.alpha", "Alpha", 1, "A")
        val layout = homeLayout(listOf(AppSection(1, "A", listOf(app))), listOf(app.id), "")
        assertTrue(layout.items.any { it.key == "header" })
        assertEquals(listOf(HomeItem.App(app, favorite = true), HomeItem.App(app)), layout.items.filterIsInstance<HomeItem.App>())
    }

    @Test fun touchingOrMovingWithinIndexSlopDoesNotSelectAHeading() {
        assertFalse(startsIndexDrag(0f, 0f, 8f))
        assertFalse(startsIndexDrag(0f, 7.9f, 8f))
        assertFalse(startsIndexDrag(8f, 0f, 8f))
        assertFalse(startsIndexDrag(4f, -4f, 8f))
    }
    @Test fun indexSelectionStartsAfterSlopInEitherDirection() {
        assertTrue(startsIndexDrag(0f, 8.1f, 8f))
        assertTrue(startsIndexDrag(0f, -8.1f, 8f))
        assertTrue(startsIndexDrag(-8.1f, 0f, 8f))
        assertTrue(startsIndexDrag(6f, -6f, 8f))
    }

    private val a = AppEntry("sample.alpha/Main", "sample.alpha", "Alpha", 1, "A")
    private val b = AppEntry("sample.beta/Main", "sample.beta", "Beta", 2, "B")
    private val c = AppEntry("sample.canvas/Main", "sample.canvas", "Canvas", 2, "B")
    private val sections = listOf(AppSection(1, "A", listOf(a)), AppSection(2, "B", listOf(b, c)))

    @Test fun emptySearchDoesNotLaunchFavoriteOrApp() {
        assertNull(firstSearchResult("", homeLayout(sections, listOf(b.id), "").items))
    }
    @Test fun whitespaceSearchDoesNotLaunch() {
        for (query in listOf(" ", "\t\n", "　")) {
            assertNull(firstSearchResult(query, homeLayout(sections, listOf(b.id), query).items))
        }
    }
    @Test fun missingSearchResultDoesNotLaunch() {
        assertNull(firstSearchResult("missing", homeLayout(sections, listOf(b.id), "missing").items))
        assertNull(firstSearchResult("Beta", listOf(HomeItem.App(b, favorite = true))))
    }
    @Test fun firstResultSkipsFavoriteRows() {
        val results = homeLayout(sections, emptyList(), "a").items
        assertEquals(a, firstSearchResult("a", listOf(HomeItem.App(b, favorite = true)) + results))
    }

    @Test fun rowsAndIndexSharePositionsIncludingFavorites() {
        val layout = homeLayout(sections, listOf(b.id, a.id), "")
        assertEquals(listOf("header", "favorite-${b.id}", "favorite-${a.id}",
            "all-apps", "heading-1", "app-${a.id}", "heading-2", "app-${b.id}", "app-${c.id}"), layout.items.map { it.key })
        assertEquals(listOf(HomeIndexEntry("↑", 0), HomeIndexEntry("A", 4), HomeIndexEntry("B", 6)), layout.index)
        assertEquals(layout.items.size, layout.items.map { it.key }.distinct().size)
    }
    @Test fun hiddenSectionAndHiddenFavoriteDoNotShiftIndexIncorrectly() {
        val hidden = sections.map { it.copy(apps = visibleApps(it.apps, setOf(a.packageName, b.packageName), true)) }
        val layout = homeLayout(hidden, listOf(a.id, c.id), "")
        assertEquals(listOf("↑", "B"), layout.index.map { it.label })
        assertEquals(HomeItem.Heading(hidden[1]), layout.items[layout.index[1].position])
        assertEquals(listOf(c.id, c.id), layout.items.filterIsInstance<HomeItem.App>().map { it.entry.id })
    }
    @Test fun searchCollapsesHeaderAndFavoritesUsingExistingMatching() {
        val layout = homeLayout(sections, listOf(a.id), "ＢＥＴＡ")
        assertEquals(listOf("all-apps", "heading-2", "app-${b.id}"), layout.items.map { it.key })
        assertEquals(HomeIndexEntry("B", 1), layout.index.last())
    }
    @Test fun emptyAndNoResultsKeepTopIndex() {
        val empty = homeLayout(emptyList(), emptyList(), "")
        assertEquals(listOf("header", "empty-favorites", "all-apps", "empty-apps"), empty.items.map { it.key })
        assertEquals(listOf(HomeIndexEntry("↑", 0)), empty.index)
        val search = homeLayout(sections, emptyList(), "missing")
        assertEquals(listOf("all-apps", "empty-apps"), search.items.map { it.key })
        assertEquals(listOf(HomeIndexEntry("↑", 0)), search.index)
    }
    @Test fun widgetsAreIndividualRowsAndIndexFollowsTheirCount() {
        val widgets = listOf(SavedWidget(11, "sample.clock/Widget", 72f), SavedWidget(12, "sample.weather/Widget", 144f))
        val layout = homeLayout(sections, listOf(b.id), "", widgets)
        assertEquals(listOf("header", "widget-11", "widget-12", "favorite-${b.id}"), layout.items.take(4).map { it.key })
        layout.index.drop(1).forEach { assertTrue(layout.items[it.position] is HomeItem.Heading) }
        assertTrue(homeLayout(sections, emptyList(), "Beta", widgets).items.none { it is HomeItem.Widget })
    }
    @Test fun dormantFavoritesDoNotShowEmptyHintOrHeading() {
        val layout = homeLayout(sections, listOf("sample.missing/Main"), "")
        assertEquals(listOf("header", "all-apps"), layout.items.take(2).map { it.key })
        assertFalse(layout.items.any { it.key == "favorites" || it.key == "empty-favorites" })
    }
    @Test fun unreadableFavoritesHideEmptyHintAndRecoveryRestoresIt() {
        val failed = homeLayout(sections, emptyList(), "", favoritesFailed = true)
        assertFalse(failed.items.any { it.key == "empty-favorites" })
        assertEquals(listOf("header", "all-apps"), failed.items.take(2).map { it.key })
        failed.index.drop(1).forEach { assertTrue(failed.items[it.position] is HomeItem.Heading) }
        val recovered = homeLayout(sections, emptyList(), "", favoritesFailed = false)
        assertTrue(recovered.items.any { it.key == "empty-favorites" })
        recovered.index.drop(1).forEach { assertTrue(recovered.items[it.position] is HomeItem.Heading) }
    }
    @Test fun unlimitedFavoritesStillKeepAccurateIndexPositions() {
        val apps = (1..20).map { a.copy(id = "sample.app$it/Main") }
        val layout = homeLayout(listOf(AppSection(1, "A", apps)), apps.map { it.id }, "")
        assertEquals(20, layout.items.filterIsInstance<HomeItem.App>().count { it.favorite })
        assertEquals(22, layout.index.last().position)
        assertTrue(layout.items[22] is HomeItem.Heading)
    }
    @Test fun indexSelectionClampsAndHandlesInvalidDimensions() {
        assertEquals(0, indexAt(-5f, 100f, 4))
        assertEquals(0, indexAt(24.9f, 100f, 4))
        assertEquals(1, indexAt(25f, 100f, 4))
        assertEquals(3, indexAt(100f, 100f, 4))
        assertEquals(3, indexAt(1000f, 100f, 4))
        assertNull(indexAt(0f, 0f, 4))
        assertNull(indexAt(0f, 100f, 0))
        assertNull(indexAt(Float.NaN, 100f, 4))
    }
}
