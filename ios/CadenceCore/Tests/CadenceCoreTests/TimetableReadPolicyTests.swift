import XCTest
import CadenceCore

final class TimetableReadPolicyTests: XCTestCase {
    func testEveryStateInAppAndExtension() {
        let cases: [(TimetableReadState, Bool, TimetableReadAction)] = [
            (.missing, false, .evaluate(hasTimetable: false)),
            (.populated, false, .evaluate(hasTimetable: true)),
            (.unreadable, false, .evaluate(hasTimetable: false)),
            (.missing, true, .evaluate(hasTimetable: false)),
            (.populated, true, .evaluate(hasTimetable: true)),
            (.unreadable, true, .preserve),
        ]
        for (state, isExtension, expected) in cases {
            XCTAssertEqual(TimetableReadPolicy.action(for: state, isExtension: isExtension), expected,
                           "state=\(state) isExtension=\(isExtension)")
        }
    }
}
