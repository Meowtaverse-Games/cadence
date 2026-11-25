package com.meowtaversegames.cadence

import kotlinx.serialization.json.*

const val WIDGET_LIMIT = 5

data class SavedWidget(val id: Int, val provider: String, val height: Float,
    val extras: JsonObject = JsonObject(emptyMap())) {
    val packageName: String get() = provider.substringBefore('/')
}
data class WidgetDocument(val widgets: List<SavedWidget> = emptyList(),
    val extras: JsonObject = JsonObject(emptyMap()))

/** Preserve unknown fields at both levels, including when editing known fields. */
object WidgetJson {
    fun decode(text: String): WidgetDocument {
        val root = Json.parseToJsonElement(text).jsonObject
        val widgets = root["widgets"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            SavedWidget(value.getValue("id").jsonPrimitive.int,
                value.getValue("provider").jsonPrimitive.content,
                value["height"]?.jsonPrimitive?.float ?: 72f,
                JsonObject(value.filterKeys { it !in setOf("id", "provider", "height") }))
        }.orEmpty()
        validate(widgets)
        return WidgetDocument(widgets, JsonObject(root.filterKeys { it != "widgets" }))
    }
    fun validate(widgets: List<SavedWidget>) {
        require(widgets.map { it.id }.distinct().size == widgets.size)
        require(widgets.all { it.id > 0 && it.height.isFinite() && it.height > 0 &&
            it.provider.substringBefore('/').isNotBlank() && it.provider.substringAfter('/', "").isNotBlank() &&
            it.provider.none(Char::isWhitespace) })
    }
    fun encode(document: WidgetDocument): String {
        validate(document.widgets)
        return JsonObject(document.extras + ("widgets" to JsonArray(document.widgets.map { widget ->
            JsonObject(widget.extras + mapOf("id" to JsonPrimitive(widget.id),
                "provider" to JsonPrimitive(widget.provider), "height" to JsonPrimitive(widget.height)))
        }))).toString()
    }
}

class WidgetStore(private val readText: () -> String?, private val writeText: (String) -> Unit,
    private val onFailure: () -> Unit = {}) {
    fun read(): ReadValue<WidgetDocument> = try {
        ReadValue(readText()?.let(WidgetJson::decode) ?: WidgetDocument(), false)
    } catch (_: Exception) { onFailure(); ReadValue(null, true) }
    fun save(widgets: List<SavedWidget>) {
        val current = read()
        check(!current.failed) { "Unreadable widgets must be preserved" }
        writeText(WidgetJson.encode(requireNotNull(current.value).copy(widgets = widgets)))
    }
}

fun widgetAddReason(widgets: List<SavedWidget>, failed: Boolean): String? = when {
    failed -> "ウィジェットの保存を読み込めないため追加できません。元のファイルは保護されています"
    widgets.size >= WIDGET_LIMIT -> "ウィジェットは5個までです"
    else -> null
}
fun orphanWidgetIds(hostIds: List<Int>, saved: List<SavedWidget>, pendingId: Int?, readFailed: Boolean): List<Int> =
    if (readFailed) emptyList() else hostIds.filter { id -> id != pendingId && saved.none { it.id == id } }
fun visibleWidgets(saved: List<SavedWidget>, available: Set<Int>, targets: Set<String>, blocked: Boolean): List<SavedWidget> =
    saved.filter { it.id in available && (!blocked || it.packageName !in targets) }
fun removedPackageWidgets(saved: List<SavedWidget>, packageName: String, replacing: Boolean): List<SavedWidget> =
    if (replacing) emptyList() else saved.filter { it.packageName == packageName }
fun canMoveWidget(visible: List<Int>, id: Int, direction: Int): Boolean =
    direction in listOf(-1, 1) && visible.indexOf(id).let { it >= 0 && it + direction in visible.indices }
fun moveWidget(saved: List<SavedWidget>, visible: List<Int>, id: Int, direction: Int): List<SavedWidget> {
    val ids = visible.filter { candidate -> saved.any { it.id == candidate } }
    if (!canMoveWidget(ids, id, direction)) return saved
    val from = saved.indexOfFirst { it.id == id }
    val to = saved.indexOfFirst { it.id == ids[ids.indexOf(id) + direction] }
    return saved.toMutableList().apply { this[from] = saved[to]; this[to] = saved[from] }
}
data class WidgetHeight(val minHeight: Float, val minResizeHeight: Float, val maxResizeHeight: Float,
    val vertical: Boolean) {
    fun bounds(screenHeight: Float): ClosedFloatingPointRange<Float> {
        val cap = (screenHeight * .8f).coerceAtLeast(1f)
        val upper = if (maxResizeHeight > 0) minOf(maxResizeHeight, cap) else cap
        // An oversized provider minimum cannot override the window safety cap.
        val lower = (if (minResizeHeight > 0) minResizeHeight else minHeight).coerceIn(1f, upper)
        return lower..upper
    }
    fun step(current: Float, direction: Int, screenHeight: Float): Float =
        if (!vertical) current else (current + direction.coerceIn(-1, 1) * 72f).coerceIn(bounds(screenHeight))
}

enum class WidgetStage { BIND, CONFIGURE, SAVE, RECONFIGURE }
data class WidgetPending(val widget: SavedWidget, val stage: WidgetStage, val needsConfiguration: Boolean)
enum class WidgetEvent { BOUND, CONFIGURED, SAVED, CANCELLED, FAILED }
data class WidgetTransition(val pending: WidgetPending?, val deleteId: Int? = null)
/** The adapter accepts results only for the current phase; duplicates are no-ops. */
fun transitionWidget(pending: WidgetPending?, event: WidgetEvent): WidgetTransition {
    if (pending == null) return WidgetTransition(null)
    if (event == WidgetEvent.CANCELLED || event == WidgetEvent.FAILED) return WidgetTransition(null,
        pending.widget.id.takeUnless { pending.stage == WidgetStage.RECONFIGURE })
    return when {
        event == WidgetEvent.BOUND && pending.stage == WidgetStage.BIND -> WidgetTransition(pending.copy(
            stage = if (pending.needsConfiguration) WidgetStage.CONFIGURE else WidgetStage.SAVE))
        event == WidgetEvent.CONFIGURED && pending.stage == WidgetStage.CONFIGURE -> WidgetTransition(pending.copy(stage = WidgetStage.SAVE))
        event == WidgetEvent.CONFIGURED && pending.stage == WidgetStage.RECONFIGURE -> WidgetTransition(null)
        event == WidgetEvent.SAVED && pending.stage == WidgetStage.SAVE -> WidgetTransition(null)
        else -> WidgetTransition(pending)
    }
}
fun needsWidgetConfiguration(hasConfigure: Boolean, api: Int, optional: Boolean): Boolean = hasConfigure && !(api >= 31 && optional)
