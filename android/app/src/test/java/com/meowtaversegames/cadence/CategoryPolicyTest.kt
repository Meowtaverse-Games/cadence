package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class CategoryPolicyTest {
    private val alpha = AppEntry("synthetic.alpha/Main", "synthetic.alpha", "Alpha", 1, "A")
    private val beta = AppEntry("synthetic.beta/Main", "synthetic.beta", "Beta", 2, "B")
    private val hidden = "synthetic.hidden/Main"
    private val apps = listOf(beta, alpha)
    private val inventory = apps.map { it.id }.toSet()
    private fun category(ids: List<String> = emptyList()) = HomeCategory("work", "Work", ids)

    @Test fun trimsNamesAndRejectsEmptyOrWhitespace() {
        assertEquals("Work", newCategory(" \tWork\n", "work").name)
        assertNotNull(categoryNameError("\t \n　"))
        assertNotNull(categoryNameError(""))
    }
    @Test fun acceptsTwentyCodePointsAndRejectsTwentyOne() {
        assertNull(categoryNameError("A".repeat(20)))
        assertNotNull(categoryNameError("A".repeat(21)))
    }
    @Test fun emojiCountAsOneCodePointNotTwoUtf16Units() {
        assertNull(categoryNameError("😀".repeat(20)))
        assertNotNull(categoryNameError("😀".repeat(21)))
    }
    @Test fun combiningSequencesCountTheirCodePointsWithoutNormalization() {
        assertNull(categoryNameError("e\u0301".repeat(10)))
        assertNotNull(categoryNameError("e\u0301".repeat(10) + "😀"))
        assertNotNull(categoryNameError("👩‍💻".repeat(7)))
    }
    @Test fun renamingKeepsIdentityAndAllowsDuplicateNames() {
        val original = category(listOf(alpha.id))
        val renamed = renameCategory(original, " Docs ")
        assertEquals(original.id, renamed.id)
        assertEquals(original.appIds, renamed.appIds)
        assertEquals("Docs", renamed.name)
        assertEquals(2, CategoryJson.decode(CategoryJson.encode(listOf(original, newCategory("Work", "other")))).size)
    }
    @Test fun generatedIdentityIsIndependentOfName() {
        assertNotEquals(newCategory("Work").id, newCategory("Work").id)
    }
    @Test fun additionsAppendWithoutDuplicatesAndUnknownInventoryPreventsAddition() {
        val ids = listOf(hidden, alpha.id)
        assertEquals(ids + beta.id, addFavorite(ids, beta.id, inventory))
        assertEquals(ids, addFavorite(ids, alpha.id, inventory))
        assertEquals(ids, addFavorite(ids, beta.id, null))
    }
    @Test fun removingAnAppKeepsOtherIdsAndCategories() {
        val original = listOf(category(listOf(alpha.id, hidden)), HomeCategory("docs", "Docs", listOf(alpha.id)))
        val result = updateCategory(original, "work") { it.copy(appIds = removeFavorite(it.appIds, alpha.id)) }
        assertEquals(listOf(hidden), result[0].appIds)
        assertEquals(original[1], result[1])
    }
    @Test fun packageRemovalPrunesEveryComponentFromEveryCategoryOnlyForExactPackage() {
        val ids = listOf(alpha.id, "synthetic.alpha/Other", "synthetic.alpha.extra/Main", hidden)
        val result = removePackageCategories(listOf(category(ids), HomeCategory("docs", "Docs", ids)), alpha.packageName)
        result.forEach { assertEquals(listOf("synthetic.alpha.extra/Main", hidden), it.appIds) }
    }
    @Test fun movingVisibleNeighboursPreservesHiddenSlots() {
        val ids = listOf(alpha.id, hidden, beta.id)
        assertEquals(listOf(beta.id, hidden, alpha.id), moveVisibleFavorite(ids, listOf(alpha.id, beta.id), alpha.id, 1))
        assertEquals(ids, moveVisibleFavorite(ids, listOf(alpha.id, beta.id), alpha.id, -1))
    }
    @Test fun bulkSelectionRetainsOrderAndAppendsOnlyNewSelectionsByName() {
        val gamma = AppEntry("synthetic.gamma/Main", "synthetic.gamma", "Gamma", 3, "G")
        val all = apps + gamma
        assertEquals(listOf(gamma.id, hidden, alpha.id, beta.id), applyCategorySelection(
            listOf(gamma.id, hidden), all.map { it.id }.toSet(), all, all.map { it.id }.toSet()))
    }
    @Test fun bulkSelectionRemovesOnlyDeselectedVisibleApps() {
        assertEquals(listOf(hidden, beta.id), applyCategorySelection(listOf(alpha.id, hidden, beta.id), setOf(beta.id), apps, inventory))
    }
    @Test fun bulkSelectionWithUnknownInventoryDoesNothing() {
        val ids = listOf(alpha.id, hidden)
        assertEquals(ids, applyCategorySelection(ids, setOf(beta.id), apps, null))
    }
    @Test fun bulkSelectionUsesInjectedNameCollationNotInventoryOrBucketOrder() {
        assertEquals(listOf(beta.id, alpha.id), applyCategorySelection(emptyList(), inventory, apps, inventory, reverseOrder()))
    }
    @Test fun movingAndDeletingCategoriesPreservesRemainingData() {
        val work = category(listOf(alpha.id))
        val docs = HomeCategory("docs", "Docs", listOf(beta.id))
        val original = listOf(work, docs)
        assertEquals(listOf(docs, work), moveCategory(original, "docs", -1))
        assertEquals(original, moveCategory(original, "work", -1))
        assertEquals(listOf(docs), removeCategory(original, "work"))
    }
    @Test fun blockingAndMissingInventoryOnlyAffectVisibility() {
        val category = category(listOf(beta.id, hidden, alpha.id))
        assertEquals(listOf(beta), visibleCategoryApps(category, apps, setOf(alpha.packageName), true))
        assertEquals(listOf(beta, alpha), visibleCategoryApps(category, apps, setOf(alpha.packageName), false))
        assertEquals(listOf(beta.id, hidden, alpha.id), category.appIds)
    }
    @Test fun jsonRoundTripPreservesOrderIdentityUnicodeAndEscapes() {
        val categories = listOf(HomeCategory("docs", "Docs 😀 \"\\", listOf(beta.id, hidden)), category(listOf(alpha.id)))
        assertEquals(categories, CategoryJson.decode(CategoryJson.encode(categories)))
    }
    @Test fun invalidDocumentsFailInsteadOfSilentlyDroppingData() {
        listOf("{", "{}", "null", "[{}]", "[{\"id\":1,\"name\":\"Work\",\"apps\":[]}]",
            "[{\"id\":\"work\",\"name\":\"Work\",\"apps\":[\"invalid\"]}]").forEach {
            assertTrue(runCatching { CategoryJson.decode(it) }.isFailure)
        }
        assertTrue(runCatching { CategoryJson.encode(listOf(category(), category())) }.isFailure)
        assertTrue(runCatching { CategoryJson.encode(listOf(category(listOf(alpha.id, alpha.id)))) }.isFailure)
    }
    @Test fun missingFileStartsEmptyAndSaves() {
        var file: String? = null
        val store = CategoryStore({ file }, { file = it }, {})
        assertFalse(store.read().failed)
        assertEquals(emptyList<HomeCategory>(), store.read().value)
        store.save(listOf(category()))
        assertEquals(listOf(category()), store.read().value)
    }
    @Test fun corruptFileIsEmptyButCannotBeOverwritten() {
        var file = "broken"
        val store = CategoryStore({ file }, { file = it }, {})
        assertTrue(store.read().failed)
        assertEquals(emptyList<HomeCategory>(), store.read().value)
        assertTrue(runCatching { store.save(listOf(category())) }.isFailure)
        assertEquals("broken", file)
    }
    @Test fun readIoFailureIsProtectedAndRecoveryAllowsSaving() {
        var unreadable = true
        var writes = 0
        val store = CategoryStore({ if (unreadable) error("synthetic read failure") else null }, { writes++ }, {})
        assertTrue(store.read().failed)
        assertTrue(runCatching { store.save(emptyList()) }.isFailure)
        assertEquals(0, writes)
        unreadable = false
        store.save(listOf(category()))
        assertEquals(1, writes)
    }
    @Test fun saveRereadsFileEvenAfterASuccessfulRead() {
        var file = "[]"
        val store = CategoryStore({ file }, { file = it }, {})
        assertFalse(store.read().failed)
        file = "broken"
        assertTrue(runCatching { store.save(emptyList()) }.isFailure)
        assertEquals("broken", file)
    }
}
