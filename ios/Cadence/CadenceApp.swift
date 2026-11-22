import SwiftUI
import BackgroundTasks

final class AppDelegate: NSObject, UIApplicationDelegate {
    static let refreshIdentifier = "com.meowtaverse-games.cadence.refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        #if DEBUG
        if CadenceDesignPreview.requested != nil { return true }
        #endif
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshIdentifier, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            Self.scheduleRefresh()
            let completion = BackgroundRefreshCompletion { task.setTaskCompleted(success: $0) }
            task.expirationHandler = { completion.finish(success: false) }
            Task { @MainActor in
                AppRefreshService.shared.request("BGタスク") { completion.finish(success: true) }
            }
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
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if let preview = CadenceDesignPreview.requested {
                CadenceDesignPreview(screen: preview).preferredColorScheme(.dark)
            } else { LiveRoot() }
            #else
            LiveRoot()
            #endif
        }
    }
}

/// Kept lazy: the preview branch never instantiates AppModel or any of its services.
private struct LiveRoot: View {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase
    var body: some View {
        ContentView(model: model)
            .preferredColorScheme(.dark)
            .task(id: phase) {
                if phase == .active { model.scheduleRefresh("本体の再登録/起動・前面") }
                if phase == .background { AppDelegate.scheduleRefresh() }
            }
            .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
                model.scheduleRefresh("本体の再登録/タイムゾーン変更", reset: true)
            }
    }
}
