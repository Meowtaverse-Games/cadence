import Foundation
import XCTest
import CadenceCore

final class TimetableSyncPolicyTests: XCTestCase {
    func testDecisionTable() {
        // Exhaust every combination; same is meaningful only for valid + local.
        for linked in [false, true] {
            for fromServer in [false, true] {
                for pending in [false, true] {
                    for remote in [SyncRemoteDocument.missing, .invalid("enabled"), .valid] {
                        for local in [false, true] {
                            for same in [false, true] {
                                let actual = TimetableSyncPolicy.decide(linked: linked, fromServer: fromServer,
                                    pendingWrites: pending, remote: remote, hasLocal: local, same: same)
                                let expected: SyncDecision
                                switch (linked, fromServer, pending, remote, local, same) {
                                case (false, false, _, _, _, _): expected = SyncDecision(.keep, notice: .waitingForServer)
                                case (_, _, true, _, _, _): expected = SyncDecision(.keep, notice: .pendingWrites)
                                case (_, false, false, .missing, _, _): expected = SyncDecision(.keep)
                                case (true, true, false, .missing, _, _): expected = SyncDecision(.keep, notice: .missing)
                                case (false, true, false, .missing, true, _): expected = SyncDecision(.upload)
                                case (false, true, false, .missing, false, _): expected = SyncDecision(.markLinked)
                                case (_, _, false, .invalid(let code), _, _): expected = SyncDecision(.keep, notice: .invalid(code))
                                case (false, true, false, .valid, true, false): expected = SyncDecision(.askUser, notice: .conflict)
                                case (false, true, false, .valid, true, true): expected = SyncDecision(.markLinked, confirmSync: true)
                                case (true, _, false, .valid, true, true): expected = SyncDecision(.keep, confirmSync: fromServer)
                                default: expected = SyncDecision(.adopt, confirmSync: fromServer)
                                }
                                XCTAssertEqual(actual, expected,
                                    "linked=\(linked) server=\(fromServer) pending=\(pending) remote=\(remote) local=\(local) same=\(same)")
                            }
                        }
                    }
                }
            }
        }
    }

    func testReconnectWithIdenticalContentNeverResets() {
        for fromServer in [false, true] {
            XCTAssertEqual(TimetableSyncPolicy.decide(linked: true, fromServer: fromServer,
                pendingWrites: false, remote: .valid, hasLocal: true, same: true).action, .keep)
        }
    }

    func testContentUsesCodePointsAndArrayOrder() throws {
        struct Item: Encodable { var id: String; var name: String }
        struct Content: Encodable { var schemaVersion = 1; var timeZone = "Asia/Tokyo"; var patterns: [Item] }
        let original = Content(patterns: [Item(id: "\u{e9}", name: "\u{e9}")])
        XCTAssertTrue(try TimetableContent.same(original, original))
        XCTAssertFalse(try TimetableContent.same(original, Content(patterns: [Item(id: "e\u{301}", name: "\u{e9}")])))
        XCTAssertFalse(try TimetableContent.same(original, Content(patterns: [Item(id: "\u{e9}", name: "e\u{301}")])))
        let first = Item(id: "a", name: "合成A"), second = Item(id: "b", name: "合成B")
        XCTAssertFalse(try TimetableContent.same(Content(patterns: [first, second]), Content(patterns: [second, first])))
        var changed = original
        changed.schemaVersion = 2
        XCTAssertFalse(try TimetableContent.same(original, changed))
        changed = original
        changed.timeZone = "UTC"
        XCTAssertFalse(try TimetableContent.same(original, changed))
    }
}
