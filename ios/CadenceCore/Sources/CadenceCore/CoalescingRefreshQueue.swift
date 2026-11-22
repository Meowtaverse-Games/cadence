import Foundation

/// One process-local worker, with at most one pending batch that reads state when it runs.
@MainActor
public final class CoalescingRefreshQueue<Output> {
    public private(set) var isRunning = false
    public var didChangeRunning: ((Bool) -> Void)?
    public var didRefresh: ((Output) -> Void)?
    private let queue = DispatchQueue(label: "cadence.refresh", qos: .utility)
    private let work: (String) -> Output
    private var pendingEvents = Set<String>()
    private var pendingCompletions: [(Output) -> Void] = []

    public init(work: @escaping (String) -> Output) {
        self.work = work
    }

    public func request(_ event: String, completion: ((Output) -> Void)? = nil) {
        pendingEvents.insert(event)
        if let completion { pendingCompletions.append(completion) }
        guard !isRunning else { return }
        isRunning = true
        didChangeRunning?(true)
        runNext()
    }

    private func runNext() {
        let event = pendingEvents.sorted().joined(separator: ",")
        let completions = pendingCompletions
        pendingEvents.removeAll()
        pendingCompletions.removeAll()
        let work = self.work
        queue.async {
            let output = work(event)
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
