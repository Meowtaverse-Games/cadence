package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class LauncherPolicyTest {
    private fun app(id: String, label: String = id, bucket: Int = 1, heading: String = "A", pkg: String = id) =
        AppEntry(id, pkg, label, bucket, heading)

    @Test fun addsFavoritesInUserOrderWithoutDuplicates() {
        assertEquals(FavoriteChange(listOf("b", "a")), addFavorite(listOf("b"), "a"))
        assertEquals(FavoriteChange(listOf("b", "a")), addFavorite(listOf("b", "a"), "b"))
    }
    @Test fun eighthFavoriteFitsButNinthIsRejected() {
        val seven = (1..7).map { "app$it" }
        val eight = addFavorite(seven, "eighth")
        assertFalse(eight.limitReached)
        assertEquals(8, eight.ids.size)
        assertEquals(FavoriteChange(eight.ids, true), addFavorite(eight.ids, "ninth"))
        assertFalse(addFavorite(eight.ids, "eighth").limitReached)
    }
    @Test fun removesOnlyTheRequestedFavorite() {
        assertEquals(listOf("a", "c"), removeFavorite(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a"), removeFavorite(listOf("a"), "missing"))
    }
    @Test fun movesOnlyBetweenVisibleNeighbours() {
        val ids = listOf("a", "hidden", "b", "c")
        val visible = listOf("a", "b", "c")
        assertEquals(listOf("b", "hidden", "a", "c"), moveVisibleFavorite(ids, visible, "a", 1))
        assertEquals(listOf("b", "hidden", "a", "c"), moveVisibleFavorite(ids, visible, "b", -1))
        assertEquals(listOf("a", "hidden", "c", "b"), moveVisibleFavorite(ids, visible, "b", 1))
    }
    @Test fun visibleEdgesDisableMovementRegardlessOfHiddenSavedNeighbours() {
        val ids = listOf("hiddenFirst", "a", "b", "hiddenLast")
        val visible = listOf("a", "b")
        assertFalse(canMoveFavorite(visible, "a", -1))
        assertTrue(canMoveFavorite(visible, "a", 1))
        assertTrue(canMoveFavorite(visible, "b", -1))
        assertFalse(canMoveFavorite(visible, "b", 1))
        assertFalse(canMoveFavorite(visible, "missing", 1))
        assertFalse(canMoveFavorite(listOf("a"), "a", 1))
        assertFalse(canMoveFavorite(emptyList(), "a", -1))
        assertEquals(ids, moveVisibleFavorite(ids, visible, "a", -1))
        assertEquals(ids, moveVisibleFavorite(ids, visible, "b", 1))
        assertEquals(ids, moveVisibleFavorite(ids, visible, "missing", 1))
    }
    @Test fun unavailableFavoriteFreesSlotWithoutErasingSavedId() {
        val ids = (1..8).map { "app$it" }
        val installed = (ids - "app4" + "new").toSet()
        assertEquals(7, installedFavoriteCount(ids, installed))
        val added = addFavorite(ids, "new", installed)
        assertFalse(added.limitReached)
        assertEquals(ids + "new", added.ids)
        assertTrue(addFavorite(added.ids, "ninth", installed).limitReached)
        assertEquals(8, installedFavoriteCount(added.ids, installed))
    }
    @Test fun onlyConfirmedPackageRemovalErasesAllItsComponents() {
        val ids = listOf("sample.one/First", "sample.two/Main", "sample.one/Second", "sample.one.more/Main")
        assertEquals(listOf("sample.two/Main", "sample.one.more/Main"), removePackageFavorites(ids, "sample.one"))
        assertEquals(ids, removePackageFavorites(ids, "missing"))
    }
    @Test fun unavailableFavoriteDoesNotAffectDisplayOrMovementAndReturnsInSavedSlot() {
        val ids = listOf("a", "missing", "b")
        val inventory = listOf(app("a"), app("b"))
        val visible = visibleFavorites(ids, inventory).map { it.id }
        assertEquals(listOf("a", "b"), visible)
        assertEquals(2, installedFavoriteCount(ids, inventory.map { it.id }.toSet()))
        val moved = moveVisibleFavorite(ids, visible, "a", 1)
        assertEquals(listOf("b", "missing", "a"), moved)
        assertFalse(canMoveFavorite(visible, "b", 1))
        val restored = inventory + app("missing")
        assertEquals(moved, visibleFavorites(moved, restored).map { it.id })
    }
    @Test fun returningAppsKeepSavedIdsButHomeShowsAtMostEight() {
        val ids = (1..8).map { "app$it" }
        val available = (ids - "app1" + "new").map { app(it) }
        val saved = addFavorite(ids, "new", available.map { it.id }.toSet()).ids
        assertEquals(ids + "new", saved)
        assertEquals(ids - "app1" + "new", visibleFavorites(saved, available).map { it.id })
        assertEquals(ids, visibleFavorites(saved, available + app("app1")).map { it.id })
        assertEquals(ids + "new", saved)
        assertEquals(ids - "app1" + "new", visibleFavorites(removeFavorite(saved, "app1"), available).map { it.id })
    }
    @Test fun emptyInventoryDoesNotPreventAddingWithoutDeletingDormantIds() {
        val ids = (1..8).map { "app$it" }
        assertEquals(0, installedFavoriteCount(ids, emptySet()))
        assertEquals(ids + "new", addFavorite(ids, "new", setOf("new")).ids)
        assertEquals(ids, addFavorite(ids, "new", null).ids)
    }
    @Test fun perItemFailureKeepsOtherItemsInOrderAndReportsEachFailure() {
        for (failed in 1..3) {
            val errors = mutableListOf<Exception>()
            val result = mapAvailableItems(listOf(1, 2, 3), { errors.add(it) }) {
                if (it == failed) throw IllegalStateException("synthetic failure")
                it * 10
            }
            assertEquals((1..3).filter { it != failed }.map { it * 10 }, result)
            assertEquals(1, errors.size)
        }
    }
    @Test fun allFailedItemsReturnEmptyRatherThanThrowing() {
        assertEquals(emptyList<Int>(), mapAvailableItems(listOf(1, 2)) { throw Exception("synthetic failure") })
        assertEquals(emptyList<Int>(), mapAvailableItems(emptyList<Int>()) { it })
    }
    @Test fun drawerConsumesBackRegardlessOfHomeRoleButFavoritesOnlyForDefaultHome() {
        assertTrue(consumesHomeBack(drawer = true, defaultHome = false))
        assertTrue(consumesHomeBack(drawer = true, defaultHome = true))
        assertTrue(consumesHomeBack(drawer = false, defaultHome = true))
        assertFalse(consumesHomeBack(drawer = false, defaultHome = false))
    }
    @Test fun blockedFavoritesStillOccupySlotsAndAreNeverPruned() {
        val ids = (1..8).map { "app$it" }
        val installed = ids.toSet()
        val visible = ids - "app2"
        assertEquals(8, installedFavoriteCount(ids, installed))
        assertEquals(listOf("app3", "app2", "app1") + ids.drop(3),
            moveVisibleFavorite(ids, visible, "app1", 1))
        assertTrue(addFavorite(ids, "ninth").limitReached)
    }
    @Test fun unknownInventoryUsesSavedCountUntilFirstSuccess() {
        assertEquals(2, installedFavoriteCount(listOf("a", "b"), null))
    }
    @Test fun restingCountUsesDistinctInstalledPackagesOnly() {
        val apps = listOf(app("one", pkg = "synthetic.blocked"), app("two", pkg = "synthetic.blocked"), app("safe"))
        assertEquals(1, restingAppCount(apps, setOf("synthetic.blocked", "uninstalled")))
        assertEquals(0, restingAppCount(apps, setOf("uninstalled")))
        assertEquals(0, restingAppCount(emptyList(), setOf("synthetic.blocked")))
    }
    @Test fun allComponentsOfBlockedPackagesAreHidden() {
        val apps = listOf(app("one", pkg = "synthetic.blocked"), app("two", pkg = "synthetic.blocked"), app("safe"))
        assertEquals(listOf(app("safe")), visibleApps(apps, setOf("synthetic.blocked"), true))
        assertEquals(apps, visibleApps(apps, setOf("synthetic.blocked"), false))
    }
    @Test fun filteringKeepsFavoriteOrderAndSearchCannotRevealHiddenApps() {
        val apps = listOf(app("one", "Canvas Sample"), app("two", "Canvas Two"), app("three", "Notes"))
        val visible = visibleApps(apps, setOf("one"), true)
        assertEquals(listOf("three", "two"), listOf("one", "three", "two").mapNotNull { id -> visible.find { it.id == id } }.map { it.id })
        assertEquals(listOf("two"), visible.filter { matchesApp(it.label, "canvas") }.map { it.id })
        assertEquals(apps, visibleApps(apps, setOf("one"), false))
    }
    @Test fun searchMatchesSubstringIgnoringCaseAndWidth() {
        assertTrue(matchesApp("Canvas Sample", "ＮＶＡＳ"))
        assertTrue(matchesApp("ＣＡＮＶＡＳ", "canvas"))
        assertTrue(matchesApp("カナの道具", "ｶﾅ"))
        assertTrue(matchesApp("はなノート", "ノート"))
        assertFalse(matchesApp("Canvas", "notes"))
        assertTrue(matchesApp("Anything", "  "))
    }
    @Test fun sectionsUseIcuOrdinalsBeforeCollation() {
        // A stub comparator models the platform contract: Japanese Collator runs only inside a bucket.
        val names = Comparator<String> { a, b -> b.compareTo(a) }
        val sections = appSections(listOf(app("c", "c", 9, "その他"), app("a", "a", 2, "A"),
            app("b", "b", 2, "A"), app("kana", "あ", 1, "あ")), names)
        assertEquals(listOf(1, 2, 9), sections.map { it.bucket })
        assertEquals(listOf("あ", "A", "その他"), sections.map { it.heading })
        assertEquals(listOf("kana", "b", "a", "c"), sections.flatMap { it.apps }.map { it.id })
    }
    @Test fun identicalLabelsHaveStableComponentOrder() {
        assertEquals(listOf("a", "b"), appSections(listOf(app("b", "same"), app("a", "same")),
            Comparator { _, _ -> 0 }).single().apps.map { it.id })
    }
    @Test fun emptyInputProducesNoIndexEntries() {
        assertTrue(appSections(emptyList(), naturalOrder()).isEmpty())
    }
    @Test fun underflowAndOverflowHaveDistinctHeadingsInBucketOrder() {
        val sections = appSections(listOf(app("last", bucket = 40, heading = "その他"),
            app("first", bucket = 0, heading = "#")), naturalOrder())
        assertEquals(listOf(0, 40), sections.map { it.bucket })
        assertEquals(listOf("#", "その他"), sections.map { it.heading })
    }
}
