package com.meowtaversegames.cadence

import org.junit.Assert.*
import org.junit.Test

class FirebaseInitializationTest {
    @Test fun absentResourcesNeverInitializeFirebase() {
        val logs = mutableListOf<String>()
        val result = initializeFirebase({ false }, { error("must not run") }, logs::add)
        assertNull(result.clients)
        assertEquals(FIREBASE_NOT_CONFIGURED, result.problem)
        assertTrue(logs.isEmpty())
    }

    @Test fun completeConfigurationReturnsClients() {
        val clients = Any()
        val result = initializeFirebase({ true }, { clients }, {})
        assertSame(clients, result.clients)
        assertNull(result.problem)
    }

    @Test fun incompleteOptionsAreRejected() {
        val complete = listOf("synthetic-app", "dummy-key", "demo-cadence")
        assertTrue(firebaseOptionsComplete(complete[0], complete[1], complete[2]))
        for (index in complete.indices) for (missing in listOf(null, "", " ")) {
            val fields = complete.mapIndexed { i, value -> if (i == index) missing else value }
            assertFalse(firebaseOptionsComplete(fields[0], fields[1], fields[2]))
        }
    }

    @Test fun anyInitializationStageFailureIsReportedWithoutValues() {
        for (failingStage in 0..3) {
            val logs = mutableListOf<String>()
            val result = initializeFirebase({ true }, {
                // Options, FirebaseApp, FirebaseAuth, FirebaseFirestore are all in the same boundary.
                for (stage in 0..3) if (stage == failingStage) throw IllegalArgumentException("synthetic private value")
                Any()
            }, logs::add)
            assertNull(result.clients)
            assertEquals(FIREBASE_CONFIGURATION_INVALID, result.problem)
            assertEquals(listOf("firebase_initialization_failed IllegalArgumentException"), logs)
        }
    }

    @Test fun resourceLookupFailureIsDifferentFromMissingConfiguration() {
        val result = initializeFirebase({ throw IllegalStateException("synthetic") }, { Any() }, {})
        assertEquals(FIREBASE_CONFIGURATION_INVALID, result.problem)
        assertNull(result.clients)
    }
}
