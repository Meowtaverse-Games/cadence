import XCTest
import CadenceCore

final class SelectionReadPolicyTests: XCTestCase {
    func testMissingFileClearsShieldAndMonitoring() {
        XCTAssertEqual(SelectionReadPolicy.action(for: .missing, isExtension: true), .evaluate(hasSelection: false))
    }

    func testDecodedEmptySelectionClearsShieldAndMonitoring() {
        XCTAssertEqual(SelectionReadPolicy.action(for: .empty, isExtension: true), .evaluate(hasSelection: false))
    }

    func testUnreadableFilePreservesShieldAndMonitoringInExtension() {
        XCTAssertEqual(SelectionReadPolicy.action(for: .unreadable, isExtension: true), .preserve)
    }

    func testPopulatedSelectionEvaluatesTimetable() {
        XCTAssertEqual(SelectionReadPolicy.action(for: .populated, isExtension: true), .evaluate(hasSelection: true))
    }

    func testAppRetainsExistingUnreadableSelectionBehavior() {
        XCTAssertEqual(SelectionReadPolicy.action(for: .unreadable, isExtension: false), .evaluate(hasSelection: false))
    }
}
