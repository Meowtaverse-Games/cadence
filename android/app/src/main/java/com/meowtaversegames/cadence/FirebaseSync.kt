package com.meowtaversegames.cadence

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import java.time.Instant

class CadenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BlockingRuntime.get(this)
    }

    override fun onTerminate() {
        BlockingRuntime.get(this).sync.close()
        super.onTerminate()
    }
}

/** Owned by BlockingRuntime for the process lifetime. All callbacks/effects run on the main thread. */
class FirebaseSync(private val context: Context, store: LocalStore, changed: () -> Unit) {
    private val log = store::log
    private val session = SyncSession(store.readLastSync(), { store.readTimetable().value?.content },
        store::saveTimetable, changed, store::saveLastSync, log)
    var status by mutableStateOf(session.status)
        private set
    var configured by mutableStateOf(false)
        private set
    var signedIn by mutableStateOf(false)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var signInProblem by mutableStateOf<String?>(null)
        private set
    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null
    private val handler = Handler(Looper.getMainLooper())
    private val controller by lazy {
        SyncListenerController(session, { auth?.currentUser?.uid },
            listen = { uid, callbacks ->
                val registration = firestore!!.collection("users").document(uid).collection("config").document("timetable")
                    .addSnapshotListener(context.mainExecutor, MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null) callbacks.error(listenerErrorCode(error))
                        else if (snapshot != null) callbacks.snapshot(
                            if (snapshot.exists()) firestoreValue(snapshot.data) else null,
                            snapshot.metadata.isFromCache, Instant.now())
                    }
                val remove: () -> Unit = { registration.remove() }
                remove
            },
            schedule = { delay, callback ->
                val action = Runnable { callback() }
                handler.postDelayed(action, delay)
                val cancel: () -> Unit = { handler.removeCallbacks(action) }
                cancel
            },
            publish = { status = session.status },
        )
    }
    private var started = false
    private var signInAttempt = 0L
    private val authListener = FirebaseAuth.AuthStateListener { authenticationChanged() }

    fun start() {
        if (started) return
        started = true
        val initialized = initializeFirebase(
            hasConfiguration = { context.resources.getIdentifier("google_app_id", "string", context.packageName) != 0 },
            createClients = {
                val options = requireNotNull(FirebaseOptions.fromResource(context))
                require(firebaseOptionsComplete(options.applicationId, options.apiKey, options.projectId))
                val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(context, options)
                // Android's persistent Firestore cache stays enabled (the SDK default).
                FirebaseAuth.getInstance(app) to FirebaseFirestore.getInstance(app)
            },
            log = log,
        )
        val clients = initialized.clients
        if (clients == null) {
            status = session.status.copy(problem = initialized.problem)
            return
        }
        auth = clients.first
        firestore = clients.second
        configured = true
        auth?.addAuthStateListener(authListener)
    }

    private fun authenticationChanged() {
        val user = auth?.currentUser
        signedIn = user != null
        email = user?.email
        controller.accountChanged(user?.uid)
    }

    fun foreground() {
        if (!configured) return
        authenticationChanged()
        controller.resume()
    }

    /** Process death removes Handler work; explicit shutdown also invalidates queued callbacks. */
    fun close() {
        ++signInAttempt
        controller.close()
        auth?.removeAuthStateListener(authListener)
    }

    fun signIn(address: String, password: String) {
        val authentication = auth ?: return
        if (signingIn || signedIn) return
        val inputError = when {
            address.isBlank() -> "ERROR_INVALID_EMAIL"
            password.isEmpty() -> "ERROR_MISSING_PASSWORD"
            else -> null
        }
        if (inputError != null) {
            signInProblem = signInErrorMessage(inputError)
            log("sign_in_failed")
            return
        }
        val attempt = ++signInAttempt
        signingIn = true
        signInProblem = null
        try {
            authentication.signInWithEmailAndPassword(address.trim(), password).addOnCompleteListener { task ->
                if (attempt != signInAttempt) return@addOnCompleteListener
                signingIn = false
                if (task.isSuccessful) {
                    log("sign_in_succeeded")
                    authenticationChanged()
                } else {
                    log("sign_in_failed")
                    signInProblem = signInErrorMessage(signInErrorCode(task.exception))
                }
            }
        } catch (error: RuntimeException) {
            signingIn = false
            log("sign_in_failed")
            signInProblem = signInErrorMessage(signInErrorCode(error))
        }
    }

    fun signOut() {
        ++signInAttempt
        controller.accountChanged(null)
        signedIn = false
        email = null
        signingIn = false
        signInProblem = null
        status = session.status
        auth?.signOut()
    }
}

internal fun signInErrorCode(error: Exception?): String? = when (error) {
    is FirebaseTooManyRequestsException -> "ERROR_TOO_MANY_REQUESTS"
    is FirebaseAuthException -> error.errorCode
    else -> null
}

/** A disabled/deleted user's token failure can be wrapped by a Firestore exception. */
internal fun listenerErrorCode(error: FirebaseFirestoreException): String {
    val authFailure = generateSequence<Throwable>(error) { it.cause }
        .filterIsInstance<FirebaseAuthException>().firstOrNull()
    if (authFailure is FirebaseAuthInvalidUserException) return "UNAUTHENTICATED"
    if (authFailure != null && !listenerFailure(authFailure.errorCode).retry) return "UNAUTHENTICATED"
    return error.code.name
}
