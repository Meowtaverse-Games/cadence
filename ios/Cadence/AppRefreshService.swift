import Foundation
import Combine
import DeviceActivity
import CadenceCore

struct AppRefreshResult {
    let activityCount: Int
    let errors: [String]
}

/// UI triggers and BG tasks share this instance; no Screen Time work runs on the main thread.
@MainActor
final class AppRefreshService: ObservableObject {
    static let shared = AppRefreshService()
    @Published private(set) var isRefreshing = false
    @Published private(set) var result: AppRefreshResult?
    private let worker = CoalescingRefreshQueue<AppRefreshResult> { event in
        ScreenTimeCoordinator(category: "app").refresh(event: event, reset: true)
        return AppRefreshResult(
            activityCount: DeviceActivityCenter().activities.count,
            errors: GroupStore(category: "app").registrationErrors())
    }

    private init() {
        worker.didChangeRunning = { [weak self] in self?.isRefreshing = $0 }
        worker.didRefresh = { [weak self] in self?.result = $0 }
    }

    func request(_ event: String, completion: (() -> Void)? = nil) {
        worker.request(event, completion: completion.map { callback in { _ in callback() } })
    }
}

/// Expiration cannot interrupt a synchronous framework call, but it must finish the BG task once.
final class BackgroundRefreshCompletion {
    private let lock = NSLock()
    private var completion: ((Bool) -> Void)?

    init(_ completion: @escaping (Bool) -> Void) { self.completion = completion }

    func finish(success: Bool) {
        lock.lock()
        let callback = completion
        completion = nil
        lock.unlock()
        callback?(success)
    }
}
