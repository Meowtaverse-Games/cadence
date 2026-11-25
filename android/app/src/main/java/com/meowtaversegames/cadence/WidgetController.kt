package com.meowtaversegames.cadence

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.*

const val ACTION_ADD_WIDGET = "com.meowtaversegames.cadence.ADD_WIDGET"

/** Owns IDs on the main thread. Persist before exposing a widget, delete only after a successful removal. */
class WidgetController(private val activity: ComponentActivity, private val runtime: BlockingRuntime,
    savedState: Bundle?, private val onError: (String) -> Unit) {
    private val manager = AppWidgetManager.getInstance(activity)
    val host = CadenceWidgetHost(activity, { refresh() }, { log("widget_provider_failed") })
    var saved by mutableStateOf(emptyList<SavedWidget>()); private set
    var failed by mutableStateOf(false); private set
    var providers by mutableStateOf(emptyMap<Int, AppWidgetProviderInfo>()); private set
    var picker by mutableStateOf(savedState?.getBoolean("widgetPicker") ?: false)
    var pending by mutableStateOf(restorePending(savedState)); private set
    private val sizeReports = WidgetSizeReports()
    fun reportSize(id: Int, view: CadenceWidgetView, width: Float, height: Float) =
        sizeReports.report(id, width, height) { view.reportSize(width, height) }
    val addReason: String? get() = widgetAddReason(saved, failed, providers.keys)

    // AppWidgetHost only exposes a request-code configuration API. This registry bridges it to
    // registerForActivityResult, including registry state/pending-result restoration.
    private val configureRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            val id = (input as Intent).getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
        }
    }
    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current = pending
        if (current?.stage == WidgetStage.BIND) {
            if (result.resultCode == Activity.RESULT_OK && boundInfo(current.widget) != null) advance(WidgetEvent.BOUND)
            else advance(WidgetEvent.CANCELLED)
        }
    }
    private val configure = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), configureRegistry) { result ->
        if (pending?.stage in listOf(WidgetStage.CONFIGURE, WidgetStage.RECONFIGURE)) {
            advance(if (result.resultCode == Activity.RESULT_OK) WidgetEvent.CONFIGURED else WidgetEvent.CANCELLED)
        }
    }

    init {
        configureRegistry.onRestoreInstanceState(savedState?.getBundle("widgetResultRegistry"))
        refresh()
        // A save can have completed before the system captured the previous pending Bundle.
        if (pending?.stage != WidgetStage.RECONFIGURE && saved.any { it.id == pending?.widget?.id }) pending = null
        try {
            val hostIds = host.appWidgetIds.toSet()
            if (!failed) {
                val retained = retainedHostWidgets(saved, hostIds, pending?.widget?.id)
                if (retained != saved) persist(retained)
                orphanWidgetIds(hostIds.toList(), saved, pending?.widget?.id, failed).forEach(::delete)
            }
        } catch (_: RuntimeException) { log("widget_cleanup_failed") }
        // SAVE never awaits an external result and is safe to resume idempotently.
        if (pending?.stage == WidgetStage.SAVE) commitPending()
    }

    fun dispatchConfigurationResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        configureRegistry.dispatchResult(requestCode, resultCode, data)

    fun saveState(out: Bundle) {
        out.putBoolean("widgetPicker", picker)
        out.putBundle("widgetResultRegistry", Bundle().also(configureRegistry::onSaveInstanceState))
        pending?.let {
            out.putString("widgetPending", WidgetJson.encode(WidgetDocument(listOf(it.widget))))
            out.putString("widgetStage", it.stage.name)
            out.putBoolean("widgetNeedsConfiguration", it.needsConfiguration)
        }
    }
    fun refresh() {
        val stored = runtime.store.widgets.read()
        saved = stored.value?.widgets.orEmpty()
        failed = stored.failed
        providers = saved.mapNotNull { widget -> boundInfo(widget)?.let { widget.id to it } }.toMap()
    }
    private fun boundInfo(widget: SavedWidget): AppWidgetProviderInfo? = widgetProvider(activity, widget) {
        log("widget_info_failed")
    }

    fun startListening() { try { host.startListening() } catch (_: RuntimeException) { log("widget_listen_failed") } }
    fun stopListening() { try { host.stopListening() } catch (_: RuntimeException) { log("widget_stop_failed") } }
    fun openPicker() {
        refresh()
        if (pending != null) {
            onError("ウィジェットの許可・設定の結果を待っています。開いている画面で完了するか取り消してください")
            return
        }
        addReason?.let { onError(it); return }
        picker = true
    }
    fun add(info: AppWidgetProviderInfo) {
        if (!picker || pending != null) return
        refresh()
        addReason?.let { onError(it); return }
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        if (runtime.decision?.end != null && info.provider.packageName in runtime.targets) return
        try {
            val id = host.allocateAppWidgetId()
            val height = initialWidgetHeight(info.minHeight / activity.resources.displayMetrics.density,
                if (Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0, Build.VERSION.SDK_INT)
            pending = WidgetPending(SavedWidget(id, info.provider.flattenToString(), height), WidgetStage.BIND,
                needsWidgetConfiguration(info.configure != null, Build.VERSION.SDK_INT,
                    Build.VERSION.SDK_INT >= 31 && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0))
            picker = false
            if (manager.bindAppWidgetIdIfAllowed(id, Process.myUserHandle(), info.provider, null)) advance(WidgetEvent.BOUND)
            else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, Process.myUserHandle()))
        } catch (_: RuntimeException) { fail() }
    }
    private fun advance(event: WidgetEvent) {
        val before = pending
        val transition = transitionWidget(before, event)
        pending = transition.pending
        transition.deleteId?.let(::delete)
        if (pending == before) return
        when (pending?.stage) {
            WidgetStage.CONFIGURE -> launchConfigure()
            WidgetStage.SAVE -> commitPending()
            else -> Unit
        }
    }
    private fun launchConfigure() {
        try { configure.launch(Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, requireNotNull(pending).widget.id)) }
        catch (_: RuntimeException) { fail() }
    }
    private fun commitPending() {
        val widget = pending?.takeIf { it.stage == WidgetStage.SAVE }?.widget ?: return
        refresh()
        if (saved.any { it.id == widget.id }) { pending = null; return }
        if (addReason != null || boundInfo(widget) == null) { fail(); return }
        if (persist(saved + widget)) advance(WidgetEvent.SAVED) else advance(WidgetEvent.FAILED)
    }
    fun reconfigure(widget: SavedWidget) {
        if (pending != null || !canInteract(widget)) return
        val info = providers[widget.id] ?: return
        if (!info.canReconfigure()) return
        pending = WidgetPending(widget, WidgetStage.RECONFIGURE, true)
        launchConfigure()
    }
    fun remove(widget: SavedWidget) {
        refresh()
        if (persist(saved.filterNot { it.id == widget.id })) delete(widget.id)
    }
    fun packageRemoved(packageName: String) {
        refresh()
        if (failed) return
        val removed = removedPackageWidgets(saved, packageName, replacing = false)
        if (removed.isNotEmpty() && persist(saved - removed.toSet())) removed.forEach { delete(it.id) }
        if (pending?.widget?.packageName == packageName) advance(WidgetEvent.FAILED)
    }
    fun move(widget: SavedWidget, direction: Int) {
        refresh()
        if (!canInteract(widget)) return
        val visible = visibleWidgets(saved, providers.keys, runtime.targets, runtime.decision?.end != null)
        persist(moveWidget(saved, visible.map { it.id }, widget.id, direction))
    }
    fun resize(widget: SavedWidget, height: Float) {
        refresh()
        if (canInteract(widget)) persist(saved.map { if (it.id == widget.id) it.copy(height = height) else it })
    }
    private fun canInteract(widget: SavedWidget): Boolean {
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        return saved.any { it.id == widget.id } && !(runtime.decision?.end != null && widget.packageName in runtime.targets)
    }
    private fun persist(next: List<SavedWidget>): Boolean = try {
        runtime.store.widgets.save(next); refresh(); true
    } catch (_: Exception) { log("widget_save_failed"); onError("ウィジェットを保存できませんでした。元のファイルは保護されています"); false }
    private fun fail() { log("widget_add_failed"); advance(WidgetEvent.FAILED); onError("ウィジェットを追加・設定できませんでした") }
    private fun delete(id: Int) { sizeReports.forget(id); try { host.deleteAppWidgetId(id) } catch (_: RuntimeException) { log("widget_delete_failed") } }
    private fun log(event: String) = runtime.store.log(event)
}

private fun restorePending(state: Bundle?): WidgetPending? = try {
    state?.getString("widgetPending")?.let { text -> WidgetPending(WidgetJson.decode(text).widgets.single(),
        WidgetStage.valueOf(requireNotNull(state.getString("widgetStage"))), state.getBoolean("widgetNeedsConfiguration")) }
} catch (_: Exception) { null }

fun AppWidgetProviderInfo.canReconfigure(): Boolean = configure != null &&
    widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
fun AppWidgetProviderInfo.heightPolicy(density: Float): WidgetHeight = WidgetHeight(minHeight / density,
    minResizeHeight / density, if (Build.VERSION.SDK_INT >= 31) maxResizeHeight / density else 0f,
    resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0)

/** Provider absence never implies that an allocated host ID was deleted. */
fun widgetProvider(context: android.content.Context, widget: SavedWidget, onFailure: () -> Unit): AppWidgetProviderInfo? = try {
    AppWidgetManager.getInstance(context).getAppWidgetInfo(widget.id)?.takeIf { info ->
        info.provider.flattenToString() == widget.provider &&
            context.getSystemService(android.content.pm.LauncherApps::class.java)
                .isPackageEnabled(info.provider.packageName, Process.myUserHandle())
    }
} catch (_: RuntimeException) { onFailure(); null }
