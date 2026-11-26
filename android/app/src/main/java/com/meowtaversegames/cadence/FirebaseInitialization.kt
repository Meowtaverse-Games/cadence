package com.meowtaversegames.cadence

const val FIREBASE_CONFIGURATION_INVALID = "Firebase の設定を読み込めません"

data class FirebaseInitialization<T>(val clients: T? = null, val problem: String? = null)

fun firebaseOptionsComplete(applicationId: String?, apiKey: String?, projectId: String?): Boolean =
    !applicationId.isNullOrBlank() && !apiKey.isNullOrBlank() && !projectId.isNullOrBlank()

/** Catch configuration/client construction failures only; never log exception messages or options. */
fun <T> initializeFirebase(
    hasConfiguration: () -> Boolean,
    createClients: () -> T,
    log: (String) -> Unit,
): FirebaseInitialization<T> = try {
    if (!hasConfiguration()) FirebaseInitialization(problem = FIREBASE_NOT_CONFIGURED)
    else FirebaseInitialization(clients = createClients())
} catch (error: RuntimeException) {
    log("firebase_initialization_failed ${error.javaClass.simpleName}")
    FirebaseInitialization(problem = FIREBASE_CONFIGURATION_INVALID)
}
