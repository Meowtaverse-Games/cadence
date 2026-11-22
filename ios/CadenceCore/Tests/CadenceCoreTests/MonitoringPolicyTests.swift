import XCTest
import CadenceCore

final class MonitoringPolicyTests: XCTestCase {
    func testExtensionDoesNotStopForUnapprovedSnapshot() {
        XCTAssertTrue(MonitoringPolicy.permitsMonitoring(isApp: false, authorized: false))
        XCTAssertTrue(MonitoringPolicy.permitsMonitoring(isApp: false, authorized: true))
        XCTAssertFalse(MonitoringPolicy.permitsMonitoring(isApp: true, authorized: false))
        XCTAssertTrue(MonitoringPolicy.permitsMonitoring(isApp: true, authorized: true))
    }

    func testTimeZoneChangeResetsOnceAfterPersistingIdentifier() {
        var registered: String? = "Asia/Tokyo"
        let current = "America/Los_Angeles"
        XCTAssertTrue(MonitoringPolicy.needsTimeZoneReset(registered: registered, current: current))
        registered = current
        XCTAssertFalse(MonitoringPolicy.needsTimeZoneReset(registered: registered, current: current))
    }

    func testMissingRegistrationZoneRequiresReset() {
        XCTAssertTrue(MonitoringPolicy.needsTimeZoneReset(registered: nil, current: "Asia/Tokyo"))
    }

    func testDifferentIdentifiersWithSameOffsetStillReset() {
        XCTAssertTrue(MonitoringPolicy.needsTimeZoneReset(registered: "Asia/Tokyo", current: "Asia/Seoul"))
    }
}
