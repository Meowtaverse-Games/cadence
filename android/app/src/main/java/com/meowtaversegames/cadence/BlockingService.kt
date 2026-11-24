package com.meowtaversegames.cadence

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.time.Instant
import java.util.concurrent.Executors

class BlockingService : Service() {
    private lateinit var runtime: BlockingRuntime
    private val handler = Handler(Looper.getMainLooper())
    private val queries = Executors.newSingleThreadExecutor { task -> Thread(task, "cadence-usage") }
    // Everything below is owned by the main thread. Workers return only immutable event batches.
    private var active = false
    private var destroyed = false
    private var queryInFlight = false
    private var queryAcknowledgedTargets = emptySet<String>()
    private var historyGeneration = 0L
    private var foreground = ForegroundState()
    private var previousRead: Long? = null
    private var launch = LaunchStatus()
    private var hadUsage = false
    private var unknownLogged = false
    private var queryFailed = false
    private var lastBlocked = emptySet<String>()
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = Instant.now()
            if (runtime.decision?.nextBoundary?.let { now >= it } == true) {
                runtime.reconcile(ReconcileSource.SERVICE)
            }
            // reconcile may have requested STOP and removed the notification in this very tick.
            if (!runtime.serviceRunning) return
            runtime.refreshPermissions(force = false)
            if (runtime.permissionState.usage != hadUsage) {
                foreground = ForegroundState()
                previousRead = null
                historyGeneration++
                hadUsage = runtime.permissionState.usage
            }
            if (runtime.decision?.runService != false && hadUsage && isInteractive()) query(now.toEpochMilli())
            refreshNotification()
            handler.postDelayed(this, 1_000)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) {
                runtime.refreshPermissions()
                windowChanged()
            }
            // Neither new queries nor launches are made while the display is off.
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = BlockingRuntime.get(this)
        runtime.serviceCreated(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promotion can itself be denied, including sticky restarts that bypass startForegroundService.
        try {
            val notification = BlockingNotifications.build(this, runtime)
            if (Build.VERSION.SDK_INT >= 34) startForeground(BlockingNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(BlockingNotifications.ID, notification)
            BlockingNotifications.foregroundPublished(runtime)
        } catch (error: RuntimeException) {
            if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException) {
                active = false
                runtime.recordStartDenied()
                stopSelf()
                runtime.retryDeniedStart(ReconcileSource.SERVICE)
                return START_NOT_STICKY
            }
            throw error
        }
        active = true
        runtime.servicePromoted()
        // This is the first point where stopping a pending FGS request is safe.
        runtime.reconcile(ReconcileSource.SERVICE)
        windowChanged()
        return START_STICKY
    }

    fun refreshNotification() {
        if (runtime.shouldShowNotification) BlockingNotifications.show(this, runtime)
    }

    fun windowChanged() {
        // Use the last completed read, not wall time: after screen-off the next query
        // still overlaps that read, so its deduplication records must remain available.
        if (queryInFlight && runtime.blockWindow.resumed && !runtime.blockWindow.multiWindow) {
            queryAcknowledgedTargets = queryAcknowledgedTargets + runtime.targets
        }
        foreground = acknowledgeBlockWindow(foreground, previousRead, runtime.targets, runtime.blockWindow)
        launch = updateLaunchStatus(launch, SystemClock.elapsedRealtime(), runtime.blockWindow.resumed, false)
        runtime.updateLaunchFailure(launch.failed)
        handler.removeCallbacks(tick)
        if (active) handler.post(tick)
    }

    private fun isInteractive() = getSystemService(PowerManager::class.java).isInteractive

    private fun query(now: Long) {
        if (queryInFlight) return
        queryInFlight = true
        queryAcknowledgedTargets = if (runtime.blockWindow.resumed && !runtime.blockWindow.multiWindow) runtime.targets else emptySet()
        val generation = historyGeneration
        val from = usageQueryStart(now, now - SystemClock.elapsedRealtime(), previousRead)
        val usage = getSystemService(UsageStatsManager::class.java)
        queries.execute {
            val batch = try { readEvents(usage, from, now) } catch (_: RuntimeException) {
                // Includes SecurityException. Never allow a platform query failure to kill the home process.
                null
            }
            handler.post {
                queryInFlight = false
                if (destroyed || !active) return@post
                val failed = batch == null
                if (failed && !queryFailed) runtime.store.log("usage_query_failed")
                queryFailed = failed
                if (batch == null) {
                    runtime.refreshPermissions(force = false)
                    return@post
                }
                // Permission changes invalidate in-flight history. Use current targets/window
                // instead of stale snapshots, and never launch after stop or screen-off.
                if (generation != historyGeneration || !runtime.permissionState.usage) return@post
                previousRead = now
                val result = completeForegroundQuery(foreground, batch, now, runtime.targets,
                    runtime.blockWindow, queryAcknowledgedTargets)
                foreground = result.state
                if (runtime.decision?.runService != false && isInteractive()) applyForeground(result.packages)
            }
        }
    }

    private fun applyForeground(packages: Set<String>) {
        if (packages.isEmpty() && !unknownLogged) runtime.store.log("foreground_unknown")
        unknownLogged = packages.isEmpty()
        val blocked = packages.intersect(runtime.targets)
        val shouldLaunch = blocked.isNotEmpty() && !runtime.blockWindow.resumed
        if (blocked != lastBlocked) {
            if (blocked.isNotEmpty()) runtime.store.log("block_target_foreground packages=${blocked.sorted().joinToString(",")}")
            lastBlocked = blocked
        }
        launch = updateLaunchStatus(launch, SystemClock.elapsedRealtime(), runtime.blockWindow.resumed, shouldLaunch)
        runtime.updateLaunchFailure(launch.failed)
        if (shouldLaunch) {
            try {
                startActivity(Intent(this, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {
                // The same three-second state transition reports thrown and silent BAL denials.
            }
        }
        refreshNotification()
    }

    override fun onDestroy() {
        destroyed = true
        active = false
        handler.removeCallbacks(tick)
        queries.shutdownNow()
        unregisterReceiver(screenReceiver)
        runtime.serviceDestroyed(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        BlockingNotifications.foregroundRemoved()
        // Removing the FGS notification must not remove the startup-failure warning.
        if (runtime.startFailed) BlockingNotifications.show(this, runtime)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Only called on the query executor. No shared mutable service or Compose state is accessed. */
private fun readEvents(usage: UsageStatsManager, from: Long, to: Long): List<ActivityEvent>? {
    val events = usage.queryEvents(from, to) ?: return null
    val input = mutableListOf<ActivityEvent>()
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val type = when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> ActivityEventType.RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> ActivityEventType.PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> ActivityEventType.STOPPED
            else -> continue
        }
        val packageName = event.packageName ?: continue
        val className = event.className ?: continue
        input += ActivityEvent(event.timeStamp, ActivityKey(packageName, className), type)
    }
    return input.toList()
}
