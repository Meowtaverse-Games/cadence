import XCTest
import CadenceCore

final class MonitoringReconcilerTests: XCTestCase {
    private final class Client: MonitoringClient {
        var names: [String]
        var reads = 0
        var calls: [MonitoringReconciler.Operation] = []
        init(_ names: [String]) { self.names = names }
        var activities: [String] { reads += 1; return names }
        func stopAll() { calls.append(.stopAll); names = [] }
        func stop(_ names: [String]) { calls.append(.stop(names)); self.names.removeAll { names.contains($0) } }
        func start(_ name: String) { calls.append(.start(name)); names.append(name) }
    }

    func testEqualSetsDoNotStartOrStop() {
        let client = Client(["block", "watchdog"])
        MonitoringReconciler.reconcile(client, desired: ["watchdog", "block"], reset: false)
        XCTAssertEqual(client.calls, [])
        XCTAssertEqual(client.reads, 1)
    }

    func testOnlyMissingNamesAreStartedFromOneSnapshot() {
        let client = Client(["watchdog"])
        MonitoringReconciler.reconcile(client, desired: ["watchdog", "first", "second"], reset: false)
        XCTAssertEqual(client.calls, [.start("first"), .start("second")])
        XCTAssertEqual(client.reads, 1)
    }

    func testOnlyObsoleteNamesAreStopped() {
        let client = Client(["watchdog", "expired"])
        MonitoringReconciler.reconcile(client, desired: ["watchdog"], reset: false)
        XCTAssertEqual(client.calls, [.stop(["expired"])])
        XCTAssertEqual(client.reads, 1)
    }

    func testResetStopsAllBeforeRegisteringEvenExistingNames() {
        let client = Client(["watchdog", "old"])
        MonitoringReconciler.reconcile(client, desired: ["watchdog", "new"], reset: true)
        XCTAssertEqual(client.calls, [.stopAll, .start("watchdog"), .start("new")])
        XCTAssertEqual(client.names, ["watchdog", "new"])
        XCTAssertEqual(client.reads, 1)
    }

    func testReplacementFreesCapacityBeforeStarting() {
        let client = Client(["watchdog", "expired"])
        MonitoringReconciler.reconcile(client, desired: ["watchdog", "next"], reset: false)
        XCTAssertEqual(client.calls, [.stop(["expired"]), .start("next")])
    }

    func testEmptyDesiredStopsExistingActivitiesWithoutFullReset() {
        let client = Client(["watchdog", "block"])
        MonitoringReconciler.reconcile(client, desired: [], reset: false)
        XCTAssertEqual(client.calls, [.stop(["block", "watchdog"])])
        XCTAssertEqual(client.reads, 1)
    }
}
