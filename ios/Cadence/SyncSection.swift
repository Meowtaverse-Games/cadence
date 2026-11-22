import SwiftUI

struct SyncSection: View {
    @ObservedObject var sync: FirebaseSync
    let local: LocalTimetable
    @State private var email = ""
    @State private var password = ""
    @State private var confirmSignOut = false

    var body: some View {
        Section("同期") {
            Text(sync.status)
            if sync.configured {
                if sync.signedIn {
                    Text(sync.email ?? "サインイン済み")
                    if let last = sync.lastSync {
                        Text("最終同期: \(last.formatted(date: .abbreviated, time: .standard))")
                    } else { Text("最終同期: まだ同期していません") }
                    if let remote = sync.conflict {
                        summary("この iPhone", local)
                        summary("サーバー", remote)
                        Button("この iPhone の時間割をサーバーに上げる") { sync.useLocal() }
                            .disabled(sync.initialUpload)
                        Button("サーバーの時間割を使う") { sync.useServer() }
                            .disabled(sync.initialUpload)
                    } else if sync.canOverwrite {
                        Button("この iPhone の時間割で上書きする") { sync.useLocal() }
                            .disabled(sync.initialUpload)
                    }
                    Button("サインアウト", role: .destructive) { confirmSignOut = true }
                } else {
                    TextField("メールアドレス", text: $email)
                        .keyboardType(.emailAddress).textContentType(.username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("パスワード", text: $password).textContentType(.password)
                    Button(sync.signingIn ? "サインイン中…" : "サインイン") {
                        sync.signIn(email: email, password: password)
                        password = ""
                    }.disabled(sync.signingIn || email.isEmpty || password.isEmpty)
                }
            }
            if let error = sync.error { Text(error).foregroundStyle(.red) }
        }
        .confirmationDialog("サインアウトしても、この端末の時間割とブロックはそのまま続きます。",
                            isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("サインアウト", role: .destructive) { sync.signOut() }
            Button("キャンセル", role: .cancel) {}
        }
        .onChange(of: sync.signedIn) { _ in email = ""; password = "" }
    }

    private func summary(_ title: String, _ document: LocalTimetable) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(title): \(document.patterns.count) パターン").font(.headline)
            // Index identity preserves distinct canonically equivalent IDs in the comparison UI.
            ForEach(Array(document.patterns.enumerated()), id: \.offset) { _, pattern in
                Text("\(pattern.name)・\(pattern.enabled ? "有効" : "無効")・\(pattern.start)〜\(pattern.end)・\(dayNames(pattern.weekdays))")
                    .font(.footnote)
            }
        }
    }
}
