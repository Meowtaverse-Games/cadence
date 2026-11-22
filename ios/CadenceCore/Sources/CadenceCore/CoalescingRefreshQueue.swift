import Foundation

/// The first request's wall clock and monotonic clock travel together through the worker queue.
public struct RefreshBatch: Sendable {
    public let received: Date
    public let startedUptimeNanoseconds: UInt64
    public private(set) var count: Int

    public init() {
        received = Date()
        startedUptimeNanoseconds = DispatchTime.now().uptimeNanoseconds
        count = 1
    }

    mutating func includeRequest() { count += 1 }
}

/// One process-local worker, with at most one pending batch that reads state when it runs.
@MainActor
public final class CoalescingRefreshQueue<Output> {
    public private(set) var isRunning = false
    public var didChangeRunning: ((Bool) -> Void)?
    public var didRefresh: ((Output) -> Void)?
    private let queue = DispatchQueue(label: "cadence.refresh", qos: .utility)
    private let work: @Sendable (String, Bool, RefreshBatch) -> Output
    private var pendingBatch: RefreshBatch?
    private var pendingReset = false
    private var pendingEvents = Set<String>()
    private var pendingCompletions: [(Output) -> Void] = []

    public init(work: @escaping @Sendable (String, Bool, RefreshBatch) -> Output) {
        self.work = work
    }

    public func request(_ event: String, reset: Bool = false, completion: ((Output) -> Void)? = nil) {
        if pendingBatch == nil { pendingBatch = RefreshBatch() }
        else { pendingBatch?.includeRequest() }
        pendingEvents.insert(event)
        pendingReset = pendingReset || reset
        if let completion { pendingCompletions.append(completion) }
        guard !isRunning else { return }
        isRunning = true
        didChangeRunning?(true)
        runNext()
    }

    private func runNext() {
        guard let batch = pendingBatch else { return }
        pendingBatch = nil
        let event = pendingEvents.sorted().joined(separator: ",")
        let reset = pendingReset
        pendingReset = false
        let completions = pendingCompletions
        pendingEvents.removeAll()
        pendingCompletions.removeAll()
        let work = self.work
        queue.async { @Sendable in
            let output = work(event, reset, batch)
            DispatchQueue.main.async {
                self.didRefresh?(output)
                completions.forEach { $0(output) }
                if self.pendingEvents.isEmpty {
                    self.isRunning = false
                    self.didChangeRunning?(false)
                } else {
                    self.runNext()
                }
            }
        }
    }
}
