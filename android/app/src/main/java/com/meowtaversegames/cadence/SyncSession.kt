package com.meowtaversegames.cadence

import com.meowtaversegames.cadence.core.TimetableParser
import com.meowtaversegames.cadence.core.TimetableValue
import java.time.Instant

const val FIREBASE_NOT_CONFIGURED = "このビルドには Firebase の設定がありません"
const val SIGN_IN_REQUIRED = "サインインが必要です"

data class SyncStatus(val problem: String? = SIGN_IN_REQUIRED, val lastSync: Instant? = null, val fromCache: Boolean = false) {
    val text: String get() = problem ?: if (fromCache) "キャッシュの時間割を使用しています" else "同期済みです"
}

/** Main-thread serialized session. Effects are injected so stale callbacks and storage failures are testable. */
class SyncSession(
    lastSync: Instant?,
    private val readContent: () -> TimetableValue?,
    private val saveContent: (TimetableValue) -> Unit,
    private val changed: () -> Unit,
    private val saveLastSync: (Instant) -> Unit,
    private val log: (String) -> Unit,
) {
    var status = SyncStatus(lastSync = lastSync)
        private set
    private var generation = 0L
    private var active = false

    fun begin(signedIn: Boolean): Long {
        generation++
        active = signedIn
        status = status.copy(problem = if (signedIn) "同期しています" else SIGN_IN_REQUIRED, fromCache = false)
        return generation
    }

    fun accepts(token: Long): Boolean = active && token == generation

    fun snapshot(token: Long, value: TimetableValue?, fromCache: Boolean, now: Instant) {
        if (!accepts(token)) return
        if (value == null) {
            log("sync_snapshot fromCache=$fromCache missing")
            status = status.copy(problem = "サーバーに時間割がありません", fromCache = fromCache)
            return
        }
        val parsed = TimetableParser.parse(value)
        if (!parsed.valid) {
            log("sync_snapshot fromCache=$fromCache invalid ${parsed.errorCode}")
            status = status.copy(problem = "時間割を読み込めません: ${parsed.errorCode}", fromCache = fromCache)
            return
        }
        try {
            val previous = readContent()
            val same = previous != null && sameTimetableContent(previous, value)
            log("sync_snapshot fromCache=$fromCache valid ${if (same) "same" else "changed"}")
            if (!same) {
                saveContent(timetableContent(value))
                changed()
            }
            if (!fromCache) saveLastSync(now)
            status = SyncStatus(problem = null, lastSync = if (fromCache) status.lastSync else now, fromCache = fromCache)
        } catch (_: Exception) {
            status = status.copy(problem = "ローカルファイルを保存・読み込みできません", fromCache = fromCache)
        }
    }

    fun error(token: Long, code: String) {
        if (!accepts(token)) return
        // The adapter supplies only SDK enum names, never exception messages.
        log("sync_listener_error $code")
        status = status.copy(problem = syncErrorMessage(code))
    }
}

fun syncErrorMessage(code: String): String = when (code) {
    "PERMISSION_DENIED" -> "時間割を読む権限がありません"
    "UNAUTHENTICATED" -> SIGN_IN_REQUIRED
    else -> "同期できません"
}

fun signInErrorMessage(code: String?): String = when (code?.removePrefix("ERROR_")) {
    "INVALID_CREDENTIAL", "INVALID_LOGIN_CREDENTIALS", "USER_NOT_FOUND", "EMAIL_NOT_FOUND", "WRONG_PASSWORD", "INVALID_PASSWORD" ->
        "メールアドレスまたはパスワードが違います。"
    "INVALID_EMAIL" -> "メールアドレスの形式を確認してください。"
    "MISSING_PASSWORD" -> "パスワードを入力してください。"
    "TOO_MANY_REQUESTS", "TOO_MANY_ATTEMPTS_TRY_LATER" -> "試行回数が多すぎます。しばらく待ってください。"
    "USER_DISABLED" -> "このアカウントは無効です。"
    "OPERATION_NOT_ALLOWED" -> "メールとパスワードでの認証が許可されていません。"
    else -> "サインインできません。接続や設定を確認してください。"
}
