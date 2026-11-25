package com.meowtaversegames.cadence

sealed interface HomeItem {
    val key: String
    data class Fixed(override val key: String) : HomeItem
    data class Heading(val section: AppSection) : HomeItem { override val key = "heading-${section.bucket}" }
    data class App(val entry: AppEntry, val favorite: Boolean = false) : HomeItem {
        override val key = "${if (favorite) "favorite" else "app"}-${entry.id}"
    }
}
data class HomeIndexEntry(val label: String, val position: Int)
data class HomeLayout(val items: List<HomeItem>, val index: List<HomeIndexEntry>)

/** One source of truth for rows and index offsets, after visibility and search filtering. */
fun homeLayout(sections: List<AppSection>, favorites: List<String>, query: String): HomeLayout {
    val visible = sections.mapNotNull { section ->
        section.apps.filter { matchesApp(it.label, query) }.takeIf { it.isNotEmpty() }?.let { section.copy(apps = it) }
    }
    val index = mutableListOf(HomeIndexEntry("↑", 0))
    val rows = buildList {
        if (query.isEmpty()) {
            add(HomeItem.Fixed("header"))
            add(HomeItem.Fixed("widgets")) // Zero-height insertion point for A2d.
            add(HomeItem.Fixed("favorites"))
            val apps = visibleFavorites(favorites, sections.flatMap { it.apps })
            if (apps.isEmpty()) add(HomeItem.Fixed("empty-favorites"))
            apps.forEach { add(HomeItem.App(it, favorite = true)) }
        }
        add(HomeItem.Fixed("all-apps"))
        visible.forEach { section ->
            index.add(HomeIndexEntry(section.heading, size))
            add(HomeItem.Heading(section))
            section.apps.forEach { add(HomeItem.App(it)) }
        }
        if (visible.isEmpty()) add(HomeItem.Fixed("empty-apps"))
    }
    return HomeLayout(rows, index)
}

fun indexAt(y: Float, height: Float, count: Int): Int? {
    if (count <= 0 || height <= 0 || !height.isFinite() || !y.isFinite()) return null
    return ((y / height).coerceIn(0f, 1f) * count).toInt().coerceIn(0, count - 1)
}

/** IME actions only launch a nonblank search result, never a favorite row. */
fun firstSearchResult(query: String, items: List<HomeItem>): AppEntry? {
    if (query.isBlank()) return null
    return items.filterIsInstance<HomeItem.App>().firstOrNull { !it.favorite }?.entry
}
