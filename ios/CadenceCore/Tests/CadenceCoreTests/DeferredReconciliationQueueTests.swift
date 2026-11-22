import XCTest
import CadenceCore

final class DeferredReconciliationQueueTests: XCTestCase {
    private final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        private var results: [(Int, Int, Int)] = []
        private var active = 0
        private var maximumActive = 0
        func set(_ value: Int) { lock.lock(); defer { lock.unlock() }; self.value = value }
        func begin(_ input: Int, count: Int) {
            lock.lock(); defer { lock.unlock() }
            active += 1
            maximumActive = max(maximumActive, active)
            results.append((input, count, value))
        }
        func end() { lock.lock(); defer { lock.unlock() }; active -= 1 }
        func snapshot() -> ([(Int, Int, Int)], Int) {
            lock.lock(); defer { lock.unlock() }; return (results, maximumActive)
        }
    }

    func testBurstReturnsWithoutWaitingAndRunsOnceMoreWithLatestState() {
        let started = expectation(description: "first started")
        let finished = expectation(description: "latest batch finished")
        let gate = DispatchSemaphore(value: 0)
        let state = State()
        let worker = DeferredReconciliationQueue<Int> { @Sendable input, count in
            XCTAssertFalse(Thread.isMainThread)
            state.begin(input, count: count)
            if input == 0 {
                started.fulfill()
                XCTAssertEqual(gate.wait(timeout: .now() + 5), .success)
            }
            state.end()
            if input == 100 { finished.fulfill() }
        }
        worker.request(0)
        wait(for: [started], timeout: 5)
        for value in 1...100 {
            state.set(value)
            worker.request(value)
        }
        // All requests have returned while the first synchronous reconciliation is still blocked.
        gate.signal()
        wait(for: [finished], timeout: 5)
        let (results, maximumActive) = state.snapshot()
        XCTAssertEqual(results.map { $0.0 }, [0, 100])
        XCTAssertEqual(results.map { $0.1 }, [1, 100])
        XCTAssertEqual(results.map { $0.2 }, [0, 100])
        XCTAssertEqual(maximumActive, 1)
    }

    func testRequestsAfterCompletedWorkAreNotLost() {
        let completions = (0..<20).map { expectation(description: "work \($0)") }
        let state = State()
        let worker = DeferredReconciliationQueue<Int> { @Sendable input, count in
            state.begin(input, count: count)
            state.end()
            completions[input].fulfill()
        }
        for index in completions.indices {
            worker.request(index)
            wait(for: [completions[index]], timeout: 5)
        }
        let (results, maximumActive) = state.snapshot()
        XCTAssertEqual(results.map { $0.0 }, Array(0..<20))
        XCTAssertEqual(results.map { $0.1 }, Array(repeating: 1, count: 20))
        XCTAssertEqual(maximumActive, 1)
    }
}
