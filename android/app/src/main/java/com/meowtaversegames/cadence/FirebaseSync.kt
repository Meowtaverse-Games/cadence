package com.meowtaversegames.cadence

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import java.time.Instant

class CadenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BlockingRuntime.get(this)
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
    private var listener: ListenerRegistration? = null
    private var listeningUid: String? = null
    private var started = false
    private var signInAttempt = 0L
    private val authListener = FirebaseAuth.AuthStateListener { authenticationChanged() }

    fun start() {
        if (started) return
        started = true
        val app = try {
            FirebaseOptions.fromResource(context)?.let { options ->
                FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(context, options)
            }
        } catch (_: RuntimeException) { null }
        if (app == null) {
            status = session.status.copy(problem = FIREBASE_NOT_CONFIGURED)
            return
        }
        auth = FirebaseAuth.getInstance(app)
        // Android's persistent Firestore cache stays enabled (the SDK default).
        firestore = FirebaseFirestore.getInstance(app)
        configured = true
        auth?.addAuthStateListener(authListener)
    }

    private fun authenticationChanged() {
        val user = auth?.currentUser
        signedIn = user != null
        email = user?.email
        if (user?.uid == listeningUid) return
        val token = session.begin(user != null) // Invalidate before removing the old listener.
        listener?.remove()
        listener = null
        listeningUid = user?.uid
        status = session.status
        if (user == null) return
        listener = firestore!!.collection("users").document(user.uid).collection("config").document("timetable")
            .addSnapshotListener(context.mainExecutor, MetadataChanges.INCLUDE) { snapshot, error ->
                if (!session.accepts(token)) return@addSnapshotListener
                if (error != null) session.error(token, error.code.name)
                else if (snapshot != null) session.snapshot(token,
                    if (snapshot.exists()) firestoreValue(snapshot.data) else null,
                    snapshot.metadata.isFromCache, Instant.now())
                status = session.status
            }
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
        session.begin(false)
        listener?.remove()
        listener = null
        listeningUid = null
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
