import XCTest
import CadenceCore

final class SyncIndicatorTests: XCTestCase {
    func testActiveSessionShowsProgressWithoutDisplayText() {
        for progress in [SyncIndicator.synced, .pending, .attention] {
            XCTAssertEqual(SyncIndicator.resolve(configured: true, signedIn: true,
                hasError: false, requiresChoice: false, progress: progress), progress)
        }
    }

    func testInactiveSessionDoesNotReusePreviousConfirmation() {
        for session in [(false, false), (true, false), (false, true)] {
            XCTAssertEqual(SyncIndicator.resolve(configured: session.0, signedIn: session.1,
                hasError: false, requiresChoice: false, progress: .synced), .inactive)
        }
    }

    func testErrorsOverrideConfirmationAndPendingProgressEvenBeforeSignIn() {
        for signedIn in [false, true] {
            for progress in [SyncIndicator.synced, .pending, .attention, .inactive] {
                XCTAssertEqual(SyncIndicator.resolve(configured: true, signedIn: signedIn,
                    hasError: true, requiresChoice: false, progress: progress), .attention)
            }
        }
    }

    func testUnresolvedChoiceStaysVisibleWhileWaitingForServer() {
        for progress in [SyncIndicator.synced, .pending, .attention] {
            XCTAssertEqual(SyncIndicator.resolve(configured: true, signedIn: true,
                hasError: false, requiresChoice: true, progress: progress), .attention)
        }
    }
}
