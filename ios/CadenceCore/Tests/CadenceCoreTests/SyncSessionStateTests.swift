import XCTest
import CadenceCore

final class SyncSessionStateTests: XCTestCase {
    private let rejected = "SyntheticFirestore(7)"

    func testInitialRejectionStopsAutomaticUploadsUntilSessionChanges() {
        var state = SyncWriteRecovery()
        func decision(_ state: SyncWriteRecovery) -> SyncDecision {
            TimetableSyncPolicy.decide(linked: false, fromServer: true, pendingWrites: false,
                remote: .missing, hasLocal: true, same: false, initialUploadFailed: state.initialUploadFailed)
        }
        XCTAssertEqual(decision(state).action, .upload)
        state.started(sequence: 1, linking: true)
        state.failed(sequence: 1, reason: rejected)
        for _ in 0..<3 { XCTAssertEqual(decision(state).action, .keep) }
        state.dismissFailure()
        XCTAssertNotNil(state.failure, "Initial failure must stay visible beside manual retry")
        // Only an explicit UI action starts another write after the policy returned keep.
        state.started(sequence: 2, linking: true)
        XCTAssertNotNil(state.failure, "Starting a retry must not clear the warning")
        state.failed(sequence: 2, reason: rejected)
        XCTAssertEqual(decision(state).action, .keep)
        state.started(sequence: 3, linking: true)
        state.succeeded(sequence: 3)
        XCTAssertNil(state.failure)
        XCTAssertTrue(state.initialUploadFailed, "Suppression lasts for the whole session")
        state = SyncWriteRecovery() // Sign-out or a different uid starts a fresh state.
        XCTAssertFalse(state.initialUploadFailed)
        XCTAssertNil(state.failure)
        XCTAssertEqual(decision(state).action, .upload)
    }

    func testSnapshotsCannotResolveRejectedInitialUploadWithoutUserChoice() {
        for fromServer in [false, true] {
            for pending in [false, true] {
                for remote in [SyncRemoteDocument.missing, .invalid("enabled"), .valid] {
                    for hasLocal in [false, true] {
                        for same in [false, true] {
                            let decision = TimetableSyncPolicy.decide(linked: false, fromServer: fromServer,
                                pendingWrites: pending, remote: remote, hasLocal: hasLocal, same: same,
                                initialUploadFailed: true)
                            XCTAssertEqual(decision.action,
                                fromServer && !pending && remote == .valid ? .askUser : .keep)
                            XCTAssertFalse(decision.confirmSync)
                            let linked = TimetableSyncPolicy.decide(linked: true, fromServer: fromServer,
                                pendingWrites: pending, remote: remote, hasLocal: hasLocal, same: same,
                                initialUploadFailed: true)
                            XCTAssertEqual(linked, TimetableSyncPolicy.decide(linked: true, fromServer: fromServer,
                                pendingWrites: pending, remote: remote, hasLocal: hasLocal, same: same))
                        }
                    }
                }
            }
        }
    }

    func testExplicitServerChoiceResolvesInitialWarningButDoesNotResetSuppression() {
        var state = SyncWriteRecovery()
        state.started(sequence: 1, linking: true)
        state.failed(sequence: 1, reason: rejected)
        state.choseServer()
        XCTAssertNil(state.failure)
        XCTAssertTrue(state.initialUploadFailed)
    }

    func testRollbackBeforeOrAfterRejectionKeepsWarningUntilSuccessfulSave() {
        for snapshotFirst in [false, true] {
            var state = SyncWriteRecovery()
            state.started(sequence: 1, linking: false)
            if snapshotFirst { state.adoptedServer() }
            state.failed(sequence: 1, reason: rejected)
            if !snapshotFirst { state.adoptedServer() }
            XCTAssertEqual(state.failure?.reason, rejected)
            XCTAssertEqual(state.failure?.restoredServer, true)
            for _ in 0..<3 { state.adoptedServer() }
            XCTAssertEqual(state.failure?.restoredServer, true)
            state.started(sequence: 2, linking: false)
            XCTAssertNotNil(state.failure)
            state.succeeded(sequence: 2)
            XCTAssertNil(state.failure)
        }
    }

    func testDismissedRollbackWarningDoesNotReturnOnSnapshots() {
        var state = SyncWriteRecovery()
        state.started(sequence: 1, linking: false)
        state.failed(sequence: 1, reason: rejected)
        XCTAssertEqual(state.failure?.restoredServer, false)
        state.adoptedServer()
        state.dismissFailure()
        state.adoptedServer()
        XCTAssertNil(state.failure)
    }

    func testOlderCompletionsCannotEraseOrRestoreNewerWarning() {
        var state = SyncWriteRecovery()
        state.started(sequence: 1, linking: false)
        state.started(sequence: 2, linking: false)
        state.failed(sequence: 2, reason: rejected)
        state.succeeded(sequence: 1)
        XCTAssertEqual(state.failure?.sequence, 2)
        state.started(sequence: 3, linking: false)
        state.started(sequence: 4, linking: false)
        state.succeeded(sequence: 4)
        state.failed(sequence: 3, reason: rejected)
        XCTAssertNil(state.failure, "An already superseded failure must not reappear")
    }

    func testSessionResetDropsInFlightRollbackAndWarning() {
        var state = SyncWriteRecovery()
        state.started(sequence: 1, linking: false)
        state.adoptedServer()
        state = SyncWriteRecovery()
        state.failed(sequence: 1, reason: rejected)
        XCTAssertNil(state.failure)
    }

    func testAdjacentKeepSnapshotsAreDeduplicatedByKindAndAction() {
        var gate = SyncSnapshotLogGate()
        func record(_ remote: SyncRemoteDocument = .valid, server: Bool = true,
                    pending: Bool = false, same: Bool = true, action: SyncAction = .keep) -> Bool {
            gate.shouldRecord(fromServer: server, pendingWrites: pending, remote: remote, same: same, action: action)
        }
        XCTAssertTrue(record())
        XCTAssertFalse(record())
        XCTAssertTrue(record(server: false))
        XCTAssertFalse(record(server: false))
        XCTAssertTrue(record(pending: true))
        XCTAssertFalse(record(pending: true))
        XCTAssertTrue(record(same: false))
        XCTAssertFalse(record(same: false))
        XCTAssertTrue(record(.missing))
        XCTAssertFalse(record(.missing, same: false), "same/changed is irrelevant for missing documents")
        XCTAssertTrue(record(.invalid("enabled")))
        XCTAssertFalse(record(.invalid("enabled")))
        XCTAssertTrue(record(.invalid("patterns")))
        XCTAssertTrue(record(), "Only adjacent duplicates are suppressed")
        gate = SyncSnapshotLogGate()
        XCTAssertTrue(record(), "The first snapshot of a new session is always recorded")
    }

    func testMeaningfulSnapshotActionsAreAlwaysRecorded() {
        var gate = SyncSnapshotLogGate()
        for action in [SyncAction.adopt, .upload, .askUser, .markLinked] {
            for _ in 0..<3 {
                XCTAssertTrue(gate.shouldRecord(fromServer: true, pendingWrites: false,
                    remote: .valid, same: true, action: action))
            }
        }
        XCTAssertTrue(gate.shouldRecord(fromServer: true, pendingWrites: false,
            remote: .valid, same: true, action: .keep), "Changing only the action must be recorded")
        XCTAssertFalse(gate.shouldRecord(fromServer: true, pendingWrites: false,
            remote: .valid, same: true, action: .keep))
    }
}
