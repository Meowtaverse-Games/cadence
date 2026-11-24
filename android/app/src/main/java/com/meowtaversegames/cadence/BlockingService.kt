package com.meowtaversegames.cadence

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

class BlockingService : Service() {
    private lateinit var runtime: BlockingRuntime
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = ForegroundState()
    private var previousRead: Long? = null
    private var launch = LaunchStatus()
    private var hadUsage = false
    private var unknownLogged = false
    private val tick = object : Runnable {
        override fun run() {
            val now = Instant.now()
            if (runtime.decision?.nextBoundary?.let { now >= it } == true) {
                runtime.reconcile(ReconcileSource.SERVICE)
            }
            runtime.refreshPermissions()
            if (runtime.permissionState.usage && !hadUsage) {
                foreground = ForegroundState()
                previousRead = null
            }
            hadUsage = runtime.permissionState.usage
            if (runtime.decision?.runService != false && hadUsage &&
                getSystemService(PowerManager::class.java).isInteractive) detect(now.toEpochMilli())
            refreshNotification()
            handler.postDelayed(this, 1_000)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) windowChanged()
            // No query is made while isInteractive is false (including SCREEN_OFF).
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = BlockingRuntime.get(this)
        runtime.service = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Fulfil the FGS contract even on a sticky restart with an unreadable cache.
        val notification = BlockingNotifications.build(this, runtime)
        if (Build.VERSION.SDK_INT >= 34) startForeground(BlockingNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(BlockingNotifications.ID, notification)
        runtime.reconcile(ReconcileSource.SERVICE)
        windowChanged()
        return START_STICKY
    }

    fun refreshNotification() = BlockingNotifications.show(this, runtime)

    fun windowChanged() {
        // Apply the lifecycle acknowledgement synchronously, even if Home is pressed
        // before the next query or the display turns off immediately after onResume.
        foreground = reduceForeground(foreground, emptyList(), System.currentTimeMillis(),
            runtime.targets, runtime.blockWindow).state
        launch = updateLaunchStatus(launch, SystemClock.elapsedRealtime(), runtime.blockWindow.resumed, false)
        runtime.setLaunchFailed(launch.failed)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun detect(now: Long) {
        val boot = now - SystemClock.elapsedRealtime()
        val from = usageQueryStart(now, boot, previousRead)
        val events = try {
            getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        } catch (_: SecurityException) {
            runtime.refreshPermissions()
            return
        } ?: return // The user may still be locked after boot.
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
        previousRead = now
        val result = reduceForeground(foreground, input, now, runtime.targets, runtime.blockWindow)
        foreground = result.state
        if (result.packages.isEmpty() && !unknownLogged) runtime.store.log("foreground_unknown")
        unknownLogged = result.packages.isEmpty()
        val blocked = result.packages.intersect(runtime.targets)
        val shouldLaunch = blocked.isNotEmpty() && !runtime.blockWindow.resumed
        launch = updateLaunchStatus(launch, SystemClock.elapsedRealtime(), runtime.blockWindow.resumed, shouldLaunch)
        runtime.setLaunchFailed(launch.failed)
        if (shouldLaunch) {
            runtime.store.log("block_launch packages=${blocked.sorted().joinToString(",")}")
            try {
                startActivity(Intent(this, BlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {
                // OEMs may throw, but most BAL denials are silent. The 3-second check handles both.
                runtime.store.log("block_launch_exception")
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(screenReceiver)
        runtime.service = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
