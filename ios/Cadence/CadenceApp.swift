import SwiftUI
import BackgroundTasks

final class AppDelegate: NSObject, UIApplicationDelegate {
    static let refreshIdentifier = "com.meowtaverse-games.cadence.refresh"
    private let queue = OperationQueue()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshIdentifier, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            Self.scheduleRefresh()
            let operation = BlockOperation {
                ScreenTimeCoordinator(category: "app").refresh(event: "BGタスク", reset: true)
            }
            task.expirationHandler = { operation.cancel() }
            operation.completionBlock = { [weak operation] in task.setTaskCompleted(success: operation?.isCancelled == false) }
            self.queue.addOperation(operation)
        }
        Self.scheduleRefresh()
        return true
    }

    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { GroupStore(category: "app").record("BGSubmitFailure reason=\(GroupStore.reason(error))") }
    }
}

@main
struct CadenceApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .task(id: phase) {
                    // Runs for the initial active scene too; no duplicate onAppear registration.
                    if phase == .active { model.scheduleRefresh("本体の再登録/起動・前面") }
                    if phase == .background { AppDelegate.scheduleRefresh() }
                }
                .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
                    model.scheduleRefresh("本体の再登録/タイムゾーン変更")
                }
        }
    }
}
