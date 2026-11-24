package com.meowtaversegames.cadence

import java.text.Normalizer
import java.util.Locale

const val FAVORITE_LIMIT = 8

/** IDs are flattened launcher components, not package names (one package can have several entries). */
data class AppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val bucket: Int,
    val heading: String,
    val canUninstall: Boolean = true,
)
data class AppSection(val bucket: Int, val heading: String, val apps: List<AppEntry>)
data class FavoriteChange(val ids: List<String>, val limitReached: Boolean = false)

fun addFavorite(ids: List<String>, id: String): FavoriteChange = when {
    id in ids -> FavoriteChange(ids)
    ids.size >= FAVORITE_LIMIT -> FavoriteChange(ids, limitReached = true)
    else -> FavoriteChange(ids + id)
}
fun removeFavorite(ids: List<String>, id: String): List<String> = ids.filterNot { it == id }
/** null means no successful inventory yet; failed/empty reads must never erase saved IDs. */
fun pruneFavorites(ids: List<String>, installedIds: Set<String>?, readFailed: Boolean): List<String> =
    if (readFailed || installedIds.isNullOrEmpty()) ids else ids.filter { it in installedIds }

fun installedFavoriteCount(ids: List<String>, installedIds: Set<String>?): Int =
    if (installedIds == null) ids.size else ids.count { it in installedIds }

fun canMoveFavorite(visibleIds: List<String>, id: String, direction: Int): Boolean {
    val position = visibleIds.indexOf(id)
    return direction in listOf(-1, 1) && position >= 0 && position + direction in visibleIds.indices
}

/** Swap visible neighbours in their saved slots; hidden favourites keep both their ID and slot. */
fun moveVisibleFavorite(ids: List<String>, visibleIds: List<String>, id: String, direction: Int): List<String> {
    val visible = visibleIds.filter { it in ids }
    if (!canMoveFavorite(visible, id, direction)) return ids
    val neighbour = visible[visible.indexOf(id) + direction]
    val from = ids.indexOf(id)
    val to = ids.indexOf(neighbour)
    return ids.toMutableList().apply { this[from] = neighbour; this[to] = id }
}

fun restingAppCount(apps: List<AppEntry>, targets: Set<String>): Int =
    apps.map { it.packageName }.toSet().intersect(targets).size

fun visibleApps(apps: List<AppEntry>, targets: Set<String>, blocked: Boolean): List<AppEntry> =
    if (blocked) apps.filterNot { it.packageName in targets } else apps

private fun searchKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
fun matchesApp(label: String, query: String): Boolean = searchKey(query).trim() in searchKey(label)

/** Platform ICU supplies bucket ordinals/labels; sorting never interleaves different buckets.
 * The injected comparator is the Japanese Collator in production and a deterministic stub in JVM tests. */
fun appSections(apps: List<AppEntry>, names: Comparator<String>): List<AppSection> = apps
    .sortedWith(compareBy<AppEntry> { it.bucket }.thenComparator { a, b ->
        names.compare(a.label, b.label).takeIf { it != 0 } ?: a.id.compareTo(b.id)
    })
    .groupBy { it.bucket }
    .map { (bucket, entries) -> AppSection(bucket, entries.first().heading, entries) }
