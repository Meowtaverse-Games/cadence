#if DEBUG
import SwiftUI

/// Synthetic values only. This entry point does not create AppModel, FirebaseSync or GroupStore.
struct CadenceDesignPreview: View {
    static var requested: String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-CadenceDesignPreview"), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }
    let screen: String
    static var document: LocalTimetable {
        var value = LocalTimetable()
        var evening = LocalPattern()
        evening.id = "synthetic-evening"; evening.name = "平日の夜"; evening.start = "19:00"; evening.end = "22:00"
        evening.segments = [LocalSegment(kind: "free", minutes: 10), LocalSegment(kind: "block", minutes: 20)]
        var morning = LocalPattern()
        morning.id = "synthetic-morning"; morning.name = "週末の朝"; morning.weekdays = [6, 7]; morning.start = "09:00"; morning.end = "11:00"
        var disabled = LocalPattern()
        disabled.id = "synthetic-disabled"; disabled.name = "試験前"; disabled.enabled = false
        disabled.start = "13:00"; disabled.end = "18:00"; disabled.weekdays = [1,2,3,4,5,6,7]
        disabled.segments = [LocalSegment(kind: "block", minutes: 50), LocalSegment(kind: "free", minutes: 10)]
        value.patterns = [evening, morning, disabled]
        return value
    }
    var body: some View {
        switch screen {
        case "editor", "editor-large":
            PatternEditorScreen(document: Self.document, pattern: Self.document.patterns[0]) { _ in nil }
        case "first-link":
            NavigationStack {
                ScrollView {
                    FirstLinkView(local: Self.document, remote: LocalTimetable(), uploading: false, useLocal: {}, useServer: {})
                        .padding(Theme.spacing)
                }.cadenceScreen()
            }
        case "sync":
            NavigationStack {
                ScrollView { SyncPanel(state: SyncState(), signIn: { _, _ in }, signOut: {}, dismissFailure: {}, overwrite: {}).padding(Theme.spacing) }
                    .navigationTitle("同期").cadenceScreen()
            }
        case "shield":
            ShieldPreview()
        default:
            NavigationStack {
                HomeScreen(state: home, sync: {}, authorize: {}, select: {}, edit: { _ in }, delete: { _ in }, refresh: {}) { EmptyView() }
            }
        }
    }
    private var home: HomeState {
        let free = screen == "home-free"
        let now = ISO8601DateFormatter().date(from: free ? "2026-01-05T20:32:00+09:00" : "2026-01-05T20:20:48+09:00")!
        let authorized = screen != "home-idle"
        var state = HomeState(document: Self.document, authorized: authorized,
                              inactiveReason: authorized ? nil : "Screen Time が許可されていないため、ブロックしていません。",
                              activityCount: authorized ? 8 : 0, counts: "APP 4 · CAT 2 · WEB 1", now: now)
        state.reference = now; state.syncTime = now; state.syncColor = Theme.free; state.syncStatus = "同期済みです。"
        return state
    }
}

private struct ShieldPreview: View {
    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(uiImage: CadenceMark.image()).resizable().frame(width: 112, height: 112).accessibilityLabel("Cadence のアイコン")
            Text("集中の時間です").font(Theme.shieldTitle).padding(.top, 28)
            Text("20:30 まで").foregroundStyle(Theme.muted).padding(.top, 10)
            Spacer()
            Button {} label: { Text("閉じる").frame(maxWidth: .infinity).padding(.vertical, 4) }
                .buttonStyle(CadenceButtonStyle(primary: true)).padding(24)
        }.foregroundStyle(Theme.text).background(Theme.ground).preferredColorScheme(.dark)
    }
}
#endif
