package com.meowtaversegames.cadence

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meowtaversegames.cadence.core.TimetableExpander
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun clockText(time: Instant): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault()).format(time)

enum class ReconcileSource { ALARM, SYSTEM_BROADCAST, VISIBLE_ACTIVITY, DATA_CHANGED, SERVICE }

/** All entry points run on the process main thread; the service never recursively starts itself. */
class BlockingRuntime private constructor(private val context: Context) {
    val store = LocalStore(context)
    var decision by mutableStateOf<BlockingDecision?>(null)
        private set
    var targets by mutableStateOf<Set<String>>(emptySet())
        private set
    var permissionState by mutableStateOf(Permissions.read(context))
        private set
    var readFailed by mutableStateOf(false)
        private set
    var startFailed by mutableStateOf(false)
        private set
    var launchFailed by mutableStateOf(false)
        private set
    var blockWindow by mutableStateOf(BlockWindow())
        private set
    var service: BlockingService? = null
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun reconcile(source: ReconcileSource) {
        refreshPermissions()
        val cached = store.readTimetable()
        val selected = store.readTargets()
        readFailed = cached.failed || selected.failed
        store.log("reconcile source=$source readFailed=$readFailed")
        // No usable previous value: leave both service and reservations untouched.
        if (cached.value == null || selected.value == null) return
        targets = selected.value - Permissions.protectedPackages(context)
        val now = Instant.now()
        val intervals = cached.value.timetable?.let {
            TimetableExpander.expand(it, now, now.plusSeconds(14 * 86_400L))
        }.orEmpty()
        val next = decideBlocking(now, intervals, targets)
        decision = next
        var retry = false
        if (!next.runService) {
            context.stopService(Intent(context, BlockingService::class.java))
            startFailed = false
            updateLaunchFailure(false)
            cancel(IMMEDIATE)
            BlockingNotifications.cancel(context)
        } else if (service != null) {
            startFailed = false
            cancel(IMMEDIATE)
            service?.refreshNotification()
        } else if (source == ReconcileSource.DATA_CHANGED) {
            retry = true
        } else if (source != ReconcileSource.SERVICE) {
            try {
                context.startForegroundService(Intent(context, BlockingService::class.java))
                startFailed = false
                cancel(IMMEDIATE)
            } catch (error: RuntimeException) {
                if (Build.VERSION.SDK_INT >= 31 && error is ForegroundServiceStartNotAllowedException) {
                    startFailed = true
                    store.log("service_start_denied")
                    BlockingNotifications.show(context, this)
                    retry = source != ReconcileSource.ALARM
                } else throw error
            }
        }
        next.nextBoundary?.let { schedule(BOUNDARY, it) } ?: cancel(BOUNDARY)
        // Separate identity from the single boundary alarm, so it cannot overwrite that alarm.
        if (retry) schedule(IMMEDIATE, now)
    }

    fun refreshPermissions() {
        val next = Permissions.read(context)
        if (permissionState != next) store.log("permissions $next")
        permissionState = next
    }

    fun windowChanged(window: BlockWindow) {
        blockWindow = window
        if (window.resumed) updateLaunchFailure(false)
        service?.windowChanged()
    }

    fun updateLaunchFailure(failed: Boolean) {
        if (launchFailed != failed) {
            launchFailed = failed
            if (failed) store.log("block_launch_failed")
        }
    }

    fun changed() = reconcile(ReconcileSource.DATA_CHANGED)

    fun statusText(): String {
        val state = decision ?: return if (readFailed) "設定を読み込めません" else "時間割がありません"
        return when {
            state.end != null -> "集中の時間 · ${clockText(state.end)} まで"
            state.nextBoundary != null -> "自由時間 · 次の集中 ${clockText(state.nextBoundary)}"
            else -> "時間割がありません"
        }
    }

    private fun pending(code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, BlockingReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(code: Int) = alarms.cancel(pending(code))

    private fun schedule(code: Int, whenAt: Instant) {
        val intent = pending(code)
        var exact = Permissions.exact(context)
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt.toEpochMilli(), intent)
            } catch (_: SecurityException) {
                exact = false // Permission can be revoked between checking and scheduling.
            }
        }
        if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt.toEpochMilli(), intent)
        permissionState = permissionState.copy(exact = exact)
        store.log("alarm code=$code at=$whenAt exact=$exact")
    }

    companion object {
        const val ALARM_ACTION = "com.meowtaversegames.cadence.RECONCILE"
        private const val BOUNDARY = 1
        private const val IMMEDIATE = 2
        private var instance: BlockingRuntime? = null
        fun get(context: Context): BlockingRuntime = instance ?: BlockingRuntime(context.applicationContext).also {
            instance = it
            it.store.log("permissions ${it.permissionState}")
        }
    }
}

class BlockingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = when (intent.action) {
            BlockingRuntime.ALARM_ACTION -> ReconcileSource.ALARM
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ReconcileSource.SYSTEM_BROADCAST
            else -> return
        }
        BlockingRuntime.get(context).reconcile(source)
    }
}
