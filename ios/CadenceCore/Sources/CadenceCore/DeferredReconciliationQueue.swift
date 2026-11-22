import Foundation

/// A process-local serial worker independent of the main actor. Requests never wait for work.
/// Only the latest pending context is retained; work must reload authoritative state each time.
public final class DeferredReconciliationQueue<Input: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private let queue = DispatchQueue(label: "cadence.monitor.reconcile", qos: .utility)
    private let work: @Sendable (Input, Int) -> Void
    private var pending: Input?
    private var pendingCount = 0
    private var running = false

    public init(work: @escaping @Sendable (Input, Int) -> Void) { self.work = work }

    public func request(_ input: Input) {
        lock.lock()
        pending = input
        pendingCount += 1
        let shouldStart = !running
        running = true
        lock.unlock()
        if shouldStart {
            queue.async { @Sendable in self.drain() }
        }
    }

    private func drain() {
        while true {
            lock.lock()
            guard let input = pending else {
                running = false
                lock.unlock()
                return
            }
            let count = pendingCount
            pending = nil
            pendingCount = 0
            lock.unlock()
            work(input, count)
        }
    }
}
