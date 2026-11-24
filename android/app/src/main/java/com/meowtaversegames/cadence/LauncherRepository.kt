package com.meowtaversegames.cadence

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.icu.text.AlphabeticIndex
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Callbacks rebuild labels/order. Resume checks only component/version metadata when unchanged.
 * Queries, labels, ICU sorting and icon rasterization always run on the worker. */
class LauncherRepository(
    context: Context,
    private val loadIcons: Boolean = true,
    private val publish: (LauncherSnapshot) -> Unit,
) : AutoCloseable {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val packages = context.packageManager
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "cadence-apps") }
    private val user = Process.myUserHandle()
    private val iconSize = (36 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val configuration = context.resources.configuration.toString()
    @Volatile private var closed = false
    private val generation = AtomicInteger()
    private val revision = AtomicInteger()
    // Only the worker reads/writes these. A forced callback revision survives a later resume request.
    private var previousMetadata: List<AppMetadata>? = null
    private var previousRevision = -1
    private var previousSnapshot: LauncherSnapshot? = null
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = changed(user)
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = changed(user)
    }

    init { launcher.registerCallback(callback, handler) }

    private fun changed(changedUser: UserHandle) {
        if (changedUser == user) {
            revision.incrementAndGet()
            refresh()
        }
    }

    /** Lightweight on resume: unchanged inventory reuses labels, sorted sections, and icons. */
    fun refresh() {
        if (closed) return
        val requested = generation.incrementAndGet()
        val requestedRevision = revision.get()
        worker.execute {
            if (closed || requested != generation.get()) return@execute
            val snapshot = try {
                val activities = launcher.getActivityList(null, user)
                val updates = activities.map { it.componentName.packageName }.distinct().associateWith {
                    packages.getPackageInfo(it, 0).lastUpdateTime
                }
                val metadata = activities.map { app ->
                    AppMetadata(app.componentName.flattenToString(), updates.getValue(app.componentName.packageName),
                        app.applicationInfo.flags, app.applicationInfo.enabled)
                }.sortedBy { it.id }
                if (metadata == previousMetadata && requestedRevision == previousRevision && previousSnapshot != null) {
                    requireNotNull(previousSnapshot)
                } else {
                    val index = AlphabeticIndex<String>(Locale.JAPANESE).addLabels(Locale.ENGLISH).buildImmutableIndex()
                    val icons = mutableMapOf<String, ImageBitmap>()
                    val entries = activities.map { app ->
                        // Do not rasterize an obsolete batch when callbacks arrive during a long read.
                        if (closed || requested != generation.get()) return@execute
                        val id = app.componentName.flattenToString()
                        val label = app.label.toString()
                        val bucket = index.getBucketIndex(label)
                        val group = index.getBucket(bucket)
                        if (loadIcons) {
                            val key = IconKey(id, updates.getValue(app.componentName.packageName), iconSize, configuration)
                            val cached = iconCache.get(key)
                            if (cached != null) icons[id] = cached else try {
                                val drawable = app.getIcon(0)
                                val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                                drawable.setBounds(0, 0, iconSize, iconSize)
                                drawable.draw(Canvas(bitmap))
                                icons[id] = bitmap.asImageBitmap().also { iconCache.put(key, it) }
                            } catch (_: RuntimeException) { /* A package may disappear during icon loading. */ }
                        }
                        val flags = app.applicationInfo.flags
                        AppEntry(id, app.componentName.packageName, label, bucket,
                            when (group.labelType) {
                                AlphabeticIndex.Bucket.LabelType.NORMAL -> group.label
                                AlphabeticIndex.Bucket.LabelType.UNDERFLOW -> "#"
                                else -> "その他"
                            }, canUninstall = flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    }
                    val collator = Collator.getInstance(Locale.JAPANESE)
                    LauncherSnapshot(appSections(entries, Comparator { a, b -> collator.compare(a, b) }), icons,
                        loaded = true).also {
                        previousMetadata = metadata
                        previousRevision = requestedRevision
                        previousSnapshot = it
                    }
                }
            } catch (_: Exception) { LauncherSnapshot(failed = true) }
            handler.post { if (!closed && requested == generation.get()) publish(snapshot) }
        }
    }

    override fun close() {
        closed = true
        launcher.unregisterCallback(callback)
        worker.shutdownNow()
    }

    private data class AppMetadata(val id: String, val updated: Long, val flags: Int, val enabled: Boolean)
    private data class IconKey(val component: String, val updated: Long, val pixels: Int, val configuration: String)

    companion object {
        // Process-wide and bounded; recreation can reuse icons. Settings never requests them.
        private val iconCache = object : LruCache<IconKey, ImageBitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: IconKey, value: ImageBitmap): Int = value.width * value.height * 4
        }
    }
}

data class LauncherSnapshot(
    val sections: List<AppSection> = emptyList(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val failed: Boolean = false,
    val loaded: Boolean = false,
) {
    val apps: List<AppEntry> get() = sections.flatMap { it.apps }
    val installedIds: Set<String>? get() = if (loaded) apps.mapTo(mutableSetOf()) { it.id } else null
}
