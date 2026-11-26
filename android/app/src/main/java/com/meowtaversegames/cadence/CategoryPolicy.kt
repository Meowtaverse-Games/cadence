package com.meowtaversegames.cadence

import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

data class HomeCategory(val id: String, val name: String, val appIds: List<String> = emptyList())

fun categoryNameError(input: String): String? {
    val name = input.trim()
    val count = name.codePointCount(0, name.length)
    return when {
        count == 0 -> "名前を入力してください"
        count > 20 -> "名前は20文字以内で入力してください"
        else -> null
    }
}
fun newCategory(name: String, id: String = UUID.randomUUID().toString()): HomeCategory {
    require(categoryNameError(name) == null)
    return HomeCategory(id, name.trim())
}
fun renameCategory(category: HomeCategory, name: String): HomeCategory {
    require(categoryNameError(name) == null)
    return category.copy(name = name.trim())
}
fun updateCategory(categories: List<HomeCategory>, id: String, update: (HomeCategory) -> HomeCategory) =
    categories.map { if (it.id == id) update(it) else it }
fun removeCategory(categories: List<HomeCategory>, id: String) = categories.filterNot { it.id == id }
fun moveCategory(categories: List<HomeCategory>, id: String, direction: Int): List<HomeCategory> {
    val ids = categories.map { it.id }
    val byId = categories.associateBy { it.id }
    return moveVisibleFavorite(ids, ids, id, direction).map { byId.getValue(it) }
}
fun removePackageCategories(categories: List<HomeCategory>, packageName: String) = categories.map {
    it.copy(appIds = removePackageFavorites(it.appIds, packageName))
}
/** Only the displayed inventory is editable; temporarily unavailable IDs keep their slots. */
fun applyCategorySelection(ids: List<String>, selected: Set<String>, apps: List<AppEntry>,
    installedIds: Set<String>?, names: Comparator<String> = Comparator(Collator.getInstance(Locale.JAPANESE)::compare)): List<String> {
    if (installedIds == null) return ids
    val editable = apps.map { it.id }.toSet().intersect(installedIds)
    val retained = ids.filter { it !in editable || it in selected }
    val additions = apps.filter { it.id in editable && it.id in selected && it.id !in ids }
        .sortedWith(Comparator { a, b -> names.compare(a.label, b.label).takeIf { it != 0 } ?: a.id.compareTo(b.id) })
    return retained + additions.map { it.id }.distinct()
}
fun visibleCategoryApps(category: HomeCategory, apps: List<AppEntry>, targets: Set<String>, blocked: Boolean) =
    visibleFavorites(category.appIds, visibleApps(apps, targets, blocked))

object CategoryJson {
    private fun validate(categories: List<HomeCategory>): List<HomeCategory> {
        require(categories.map { it.id }.distinct().size == categories.size)
        categories.forEach {
            require(it.id.isNotBlank())
            require(categoryNameError(it.name) == null && it.name == it.name.trim())
            require(it.appIds.distinct().size == it.appIds.size)
            require(it.appIds.all { id -> id.isNotBlank() && '/' in id && id.none(Char::isWhitespace) })
        }
        return categories
    }
    fun encode(categories: List<HomeCategory>): String = JsonArray(validate(categories).map { category ->
        buildJsonObject {
            put("id", category.id); put("name", category.name)
            put("apps", JsonArray(category.appIds.map(::JsonPrimitive)))
        }
    }).toString()
    fun decode(text: String): List<HomeCategory> {
        fun JsonElement.string(): String {
            val value = jsonPrimitive
            require(value.isString)
            return value.content
        }
        return validate(Json.parseToJsonElement(text).jsonArray.map {
            val doc = it.jsonObject
            HomeCategory(doc.getValue("id").string(), doc.getValue("name").string(),
                doc.getValue("apps").jsonArray.map { id -> id.string() })
        })
    }
}

/** LocalStore supplies AtomicTextWrite-backed I/O, like favorites. */
class CategoryStore(private val readFile: () -> String?, private val writeFile: (String) -> Unit,
    private val logFailure: () -> Unit) {
    @Synchronized fun read(): ReadValue<List<HomeCategory>> = try {
        ReadValue(readFile()?.let(CategoryJson::decode) ?: emptyList(), false)
    } catch (_: Exception) { logFailure(); ReadValue(emptyList(), true) }
    @Synchronized fun save(categories: List<HomeCategory>) {
        check(!read().failed) { "Unreadable categories must be preserved" }
        writeFile(CategoryJson.encode(categories))
    }
}
