package com.meowtaversegames.cadence

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.icu.text.AlphabeticIndex
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** Each activity owns a repository. Every package callback rebuilds an immutable, current-user snapshot.
 * LauncherApps queries, labels, ICU sorting, and icon rasterization all run on the worker. */
class LauncherRepository(context: Context, private val publish: (LauncherSnapshot) -> Unit) : AutoCloseable {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "cadence-apps") }
    private val user = Process.myUserHandle()
    private val iconSize = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private var closed = false
    private var generation = 0
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

    private fun changed(changedUser: UserHandle) { if (changedUser == user) refresh() }

    fun refresh() {
        if (closed) return
        val requested = ++generation
        worker.execute {
            val snapshot = try {
                val index = AlphabeticIndex<String>(Locale.JAPANESE).addLabels(Locale.ENGLISH).buildImmutableIndex()
                val icons = mutableMapOf<String, ImageBitmap>()
                val entries = launcher.getActivityList(null, user).map { app ->
                    val id = app.componentName.flattenToString()
                    val label = app.label.toString()
                    val bucket = index.getBucketIndex(label)
                    val group = index.getBucket(bucket)
                    try {
                        val drawable = app.getIcon(0)
                        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, iconSize, iconSize)
                        drawable.draw(Canvas(bitmap))
                        icons[id] = bitmap.asImageBitmap()
                    } catch (_: RuntimeException) { /* A package may disappear during its icon load. */ }
                    AppEntry(id, app.componentName.packageName, label, bucket,
                        if (group.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) group.label else "その他")
                }
                LauncherSnapshot(appSections(entries, Collator.getInstance(Locale.JAPANESE)), icons)
            } catch (_: RuntimeException) { LauncherSnapshot(failed = true) }
            handler.post { if (!closed && requested == generation) publish(snapshot) }
        }
    }

    override fun close() {
        closed = true
        launcher.unregisterCallback(callback)
        worker.shutdownNow()
    }
}

data class LauncherSnapshot(
    val sections: List<AppSection> = emptyList(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val failed: Boolean = false,
) {
    val apps: List<AppEntry> get() = sections.flatMap { it.apps }
}
