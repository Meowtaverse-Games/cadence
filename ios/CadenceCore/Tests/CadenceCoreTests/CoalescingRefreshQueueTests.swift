import XCTest
import CadenceCore

final class CoalescingRefreshQueueTests: XCTestCase {
    private final class Input {
        private let lock = NSLock()
        private var value = 0
        func set(_ newValue: Int) { lock.lock(); defer { lock.unlock() }; value = newValue }
        func read() -> Int { lock.lock(); defer { lock.unlock() }; return value }
    }

    @MainActor
    func testRequestsDuringWorkRunOnlyOneMoreTimeWithLatestState() async {
        let started = expectation(description: "first work started")
        let finished = expectation(description: "both batches completed")
        let gate = DispatchSemaphore(value: 0)
        let input = Input()
        var calls = 0 // Only accessed on the serial worker.
        let worker = CoalescingRefreshQueue<(Int, String, Int)> { event in
            XCTAssertFalse(Thread.isMainThread)
            calls += 1
            let value = input.read()
            if calls == 1 {
                started.fulfill()
                XCTAssertEqual(gate.wait(timeout: .now() + 5), .success)
            }
            return (calls, event, value)
        }
        var results: [(Int, String, Int)] = []
        var running: [Bool] = []
        var completions = 0
        worker.didRefresh = {
            XCTAssertTrue(Thread.isMainThread)
            results.append($0)
        }
        worker.didChangeRunning = {
            running.append($0)
            if !$0 { finished.fulfill() }
        }
        worker.request("launch") { _ in completions += 1 }
        await fulfillment(of: [started], timeout: 5)
        // The main actor stays responsive while the synchronous worker is blocked.
        for value in 1...100 {
            input.set(value)
            worker.request(value.isMultiple(of: 2) ? "save" : "BG") { _ in completions += 1 }
        }
        XCTAssertTrue(worker.isRunning)
        gate.signal()
        await fulfillment(of: [finished], timeout: 5)
        XCTAssertEqual(results.count, 2)
        XCTAssertEqual(results.map { $0.0 }, [1, 2])
        XCTAssertEqual(results.map { $0.1 }, ["launch", "BG,save"])
        XCTAssertEqual(results.map { $0.2 }, [0, 100])
        XCTAssertEqual(completions, 101)
        XCTAssertEqual(running, [true, false])
        XCTAssertFalse(worker.isRunning)
    }

    @MainActor
    func testRequestAfterIdleStartsNewBatch() async {
        let first = expectation(description: "first idle")
        let second = expectation(description: "second idle")
        let worker = CoalescingRefreshQueue<String> { $0 }
        var results: [String] = []
        worker.didRefresh = { results.append($0) }
        worker.didChangeRunning = { if !$0 { first.fulfill() } }
        worker.request("first")
        await fulfillment(of: [first], timeout: 5)
        worker.didChangeRunning = { if !$0 { second.fulfill() } }
        worker.request("second")
        await fulfillment(of: [second], timeout: 5)
        XCTAssertEqual(results, ["first", "second"])
    }
}
