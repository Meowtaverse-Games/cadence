package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.TimetableValue
import java.time.Instant

data class ListenerFailure(val retry: Boolean, val message: String)

fun listenerFailure(code: String): ListenerFailure = when (code.removePrefix("ERROR_")) {
    "UNAUTHENTICATED", "USER_DISABLED", "USER_NOT_FOUND", "USER_TOKEN_EXPIRED", "INVALID_USER_TOKEN" ->
        ListenerFailure(false, SIGN_IN_REQUIRED)
    "PERMISSION_DENIED" -> ListenerFailure(true, "時間割を読む権限がありません")
    else -> ListenerFailure(true, "同期できません")
}

/** Bounded even after a process has been alive for days. */
fun listenerRetryDelayMillis(failures: Int): Long = when {
    failures <= 0 -> 30_000L
    failures == 1 -> 60_000L
    else -> 300_000L
}

class SnapshotCallbacks(
    val snapshot: (TimetableValue?, Boolean, Instant) -> Unit,
    val error: (String) -> Unit,
)

/** SDK-free, main-thread lifecycle. Each listener and retry captures an invalidatable generation. */
class SyncListenerController(
    private val session: SyncSession,
    private val currentAccount: () -> String?,
    private val listen: (String, SnapshotCallbacks) -> (() -> Unit),
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val publish: () -> Unit,
) {
    private var account: String? = null
    private var removeListener: (() -> Unit)? = null
    private var listeningUid: String? = null
    private var cancelRetry: (() -> Unit)? = null
    private var retryGeneration = 0L
    private var failures = 0
    private var requiresSignIn = false
    private var closed = false

    fun accountChanged(next: String?) {
        if (closed || next == account) return
        stopListening()
        clearRetry()
        account = next
        failures = 0
        requiresSignIn = false
        session.begin(next != null)
        publish()
        attach()
    }

    /** Used both on foreground entry and by the timer; recheck the live authentication state. */
    fun resume() {
        if (closed) return
        accountChanged(currentAccount())
        attach()
    }

    private fun attach() {
        val uid = account ?: return
        if (closed || requiresSignIn || listeningUid != null) return
        clearRetry()
        val token = session.restart()
        listeningUid = uid
        publish()
        val remove = listen(uid, SnapshotCallbacks(
            snapshot = { value, fromCache, now ->
                if (session.accepts(token)) {
                    failures = 0
                    session.snapshot(token, value, fromCache, now)
                    publish()
                }
            },
            error = { code ->
                if (session.accepts(token)) {
                    session.error(token, code)
                    stopListening()
                    requiresSignIn = !listenerFailure(code).retry
                    publish()
                    if (!requiresSignIn) retry(uid)
                }
            },
        ))
        // Also safe for a test adapter that delivers an error synchronously during registration.
        if (session.accepts(token)) removeListener = remove else remove()
    }

    private fun retry(uid: String) {
        clearRetry()
        val ticket = retryGeneration
        val delay = listenerRetryDelayMillis(failures)
        failures = (failures + 1).coerceAtMost(2)
        cancelRetry = schedule(delay) {
            if (!closed && ticket == retryGeneration && account == uid) resume()
        }
    }

    private fun clearRetry() {
        retryGeneration++
        cancelRetry?.invoke()
        cancelRetry = null
    }

    private fun stopListening() {
        session.invalidate()
        removeListener?.invoke()
        removeListener = null
        listeningUid = null
    }

    fun close() {
        closed = true
        stopListening()
        clearRetry()
    }
}
