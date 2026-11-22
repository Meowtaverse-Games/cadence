import SwiftUI

struct SyncSection: View {
    @ObservedObject var sync: FirebaseSync
    let local: LocalTimetable
    @State private var confirmSignOut = false

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacing) {
            SyncPanel(state: SyncState(sync: sync), signIn: { sync.signIn(email: $0, password: $1) },
                      signOut: { confirmSignOut = true }, dismissFailure: { sync.dismissWriteFailure() },
                      overwrite: { sync.useLocal() })
            if let remote = sync.conflict {
                FirstLinkView(local: local, remote: remote, uploading: sync.initialUpload,
                              useLocal: { sync.useLocal() }, useServer: { sync.useServer() })
            }
        }
        .confirmationDialog("サインアウトしても、この端末の時間割とブロックはそのまま続きます。",
                            isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("サインアウト", role: .destructive) { sync.signOut() }
            Button("キャンセル", role: .cancel) {}
        }
    }
}

struct SyncState {
    var configured = true
    var signedIn = false
    var signingIn = false
    var status = "サインインしていないため、時間割は同期されません。"
    var email: String?
    var lastSync: Date?
    var hasWriteFailure = false
    var canDismissWriteFailure = false
    var canOverwrite = false
    var retryingInitialUpload = false
    var initialUpload = false
    var error: String?
    init() {}
    @MainActor init(sync: FirebaseSync) {
        configured = sync.configured; signedIn = sync.signedIn; signingIn = sync.signingIn
        status = sync.status; email = sync.email; lastSync = sync.lastSync
        hasWriteFailure = sync.hasWriteFailure; canDismissWriteFailure = sync.canDismissWriteFailure
        canOverwrite = sync.canOverwrite; retryingInitialUpload = sync.retryingInitialUpload
        initialUpload = sync.initialUpload; error = sync.error
    }
}

struct SyncPanel: View {
    let state: SyncState
    let signIn: (String, String) -> Void
    let signOut: () -> Void
    let dismissFailure: () -> Void
    let overwrite: () -> Void
    @State private var email = ""
    @State private var password = ""
    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacing) {
            StateTag(text: "SYNC", color: Theme.free)
            Text("時間割の同期").font(Theme.title)
            Text(state.status).font(Theme.body).foregroundStyle(state.hasWriteFailure ? Theme.alert : Theme.muted)
            if state.canDismissWriteFailure {
                Button("保存エラーの案内を閉じる", action: dismissFailure).buttonStyle(CadenceButtonStyle())
            }
            if state.configured {
                if state.signedIn {
                    Text(state.email ?? "サインイン済み")
                    if let last = state.lastSync {
                        Text("最終同期: \(last.formatted(date: .abbreviated, time: .standard))").font(Theme.mono).foregroundStyle(Theme.muted)
                    } else { Text("最終同期: まだ同期していません").font(Theme.footnote).foregroundStyle(Theme.muted) }
                    if state.canOverwrite {
                        Button(state.retryingInitialUpload ? "この iPhone の時間割をサーバーに上げる" : "この iPhone の時間割で上書きする", action: overwrite)
                            .buttonStyle(CadenceButtonStyle(primary: true)).disabled(state.initialUpload)
                    }
                    Button("サインアウト", role: .destructive, action: signOut).buttonStyle(CadenceButtonStyle())
                } else {
                    TextField("メールアドレス", text: $email)
                        .keyboardType(.emailAddress).textContentType(.username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().cadenceField()
                    SecureField("パスワード", text: $password).textContentType(.password).cadenceField()
                    Button(state.signingIn ? "サインイン中…" : "サインイン") {
                        signIn(email, password); password = ""
                    }.buttonStyle(CadenceButtonStyle(primary: true)).disabled(state.signingIn || email.isEmpty || password.isEmpty)
                }
            }
            if let error = state.error { Text(error).font(Theme.footnote).foregroundStyle(Theme.alert) }
        }.cadenceCard().onChange(of: state.signedIn) { _ in email = ""; password = "" }
    }
}

struct FirstLinkView: View {
    let local: LocalTimetable
    let remote: LocalTimetable
    let uploading: Bool
    let useLocal: () -> Void
    let useServer: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacing) {
            StateTag(text: "SYNC · FIRST LINK", color: Theme.free)
            Text("使う時間割を選んでください").font(Theme.title)
            Text("この iPhone とサーバーの時間割が違います。選ぶまでは、この iPhone の時間割でブロックを続けます。")
                .font(Theme.body).foregroundStyle(Theme.muted)
            summary("この iPhone", tag: "LOCAL", color: Theme.focus, document: local)
            summary("サーバー", tag: "CLOUD", color: Theme.free, document: remote)
            Button(action: useLocal) { Text("この iPhone の時間割をサーバーに上げる").frame(maxWidth: .infinity) }
                .buttonStyle(CadenceButtonStyle(primary: true)).disabled(uploading)
            Button(action: useServer) { Text("サーバーの時間割を使う").frame(maxWidth: .infinity) }
                .buttonStyle(CadenceButtonStyle(outlined: true)).disabled(uploading)
            Text("サーバーの時間割を使うと、この iPhone の時間割は置き換わります。")
                .font(Theme.caption).foregroundStyle(Theme.muted)
        }.cadenceCard()
    }
    private func summary(_ title: String, tag: String, color: Color, document: LocalTimetable) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack { badge(tag, color); Text(title).font(Theme.headline); Spacer(); Text("\(document.patterns.count) パターン").font(Theme.mono) }
                VStack(alignment: .leading) { badge(tag, color); Text(title).font(Theme.headline); Text("\(document.patterns.count) パターン").font(Theme.mono) }
            }
            if document.patterns.isEmpty { Text("パターンがありません").font(Theme.footnote).foregroundStyle(Theme.muted) }
            ForEach(Array(document.patterns.enumerated()), id: \.offset) { _, pattern in
                VStack(alignment: .leading, spacing: 4) {
                    Text(pattern.name + (pattern.enabled ? "" : "（無効）")).font(Theme.body)
                    Text("\(dayNames(pattern.weekdays)) \(pattern.start)–\(pattern.end)").font(Theme.mono).foregroundStyle(Theme.muted)
                }
            }
        }.cadenceCard()
    }
    private func badge(_ tag: String, _ color: Color) -> some View {
        StateTag(text: tag, color: color).padding(6)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(color, lineWidth: 1))
    }
}
