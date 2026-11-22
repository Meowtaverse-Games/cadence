import Foundation
import Combine
import CadenceCore
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore

/// All session transitions, local saves and listener effects are serialized on the main actor.
@MainActor
final class FirebaseSync: ObservableObject {
    @Published private(set) var configured = false
    @Published private(set) var email: String?
    @Published private(set) var signedIn = false
    @Published private(set) var signingIn = false
    @Published private var progressStatus = "このビルドには Firebase の設定がありません。時間割はこの端末にだけ保存されます。"
    @Published private(set) var error: String?
    @Published private(set) var lastSync: Date?
    @Published private(set) var conflict: LocalTimetable?
    @Published private(set) var canOverwrite = false
    @Published private(set) var initialUpload = false

    @Published private var recovery = SyncWriteRecovery()
    private var snapshotLogGate = SyncSnapshotLogGate()

    var status: String {
        guard let failure = recovery.failure else { return progressStatus }
        return failure.restoredServer
            ? "サーバーに保存できなかったため、サーバーの時間割に戻しました（\(failure.reason)）"
            : "サーバーに保存できませんでした（\(failure.reason)）"
    }
    var hasWriteFailure: Bool { recovery.failure != nil }
    var canDismissWriteFailure: Bool { recovery.failure?.linking == false }
    var retryingInitialUpload: Bool { !linked && recovery.initialUploadFailed }
    func dismissWriteFailure() { recovery.dismissFailure() }

    var editingBlocked: Bool { conflict != nil || initialUpload }
    var editingReason: String {
        initialUpload ? "サーバーへの保存を確認するまで、時間割を編集できません。" : "使う時間割を選ぶまで、時間割を編集できません。ブロックはこの iPhone の時間割で続きます。"
    }

    private let files = GroupStore(category: "app")
    private let defaults = UserDefaults.standard
    private var uid: String?
    private var generation = UUID()
    private var linked = false
    private var invalidServerDocument = false
    private var pendingUploads = 0
    private var writeSequence = 0
    private var auth: Auth?
    private var authHandle: AuthStateDidChangeListenerHandle?
    private var listener: ListenerRegistration?
    private var reference: DocumentReference?
    private var deferredSnapshot: DocumentSnapshot?
    private var readLocal: () -> LocalTimetable? = { nil }
    private var adoptLocal: (LocalTimetable) throws -> Void = { _ in }

    func start(read: @escaping () -> LocalTimetable?, adopt: @escaping (LocalTimetable) throws -> Void) {
        readLocal = read
        adoptLocal = adopt
        lastSync = files.lastSyncTime()
        guard Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else { return }
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        configured = true
        progressStatus = "サインインしていないため、時間割は同期されません。"
        let auth = Auth.auth()
        self.auth = auth
        authHandle = auth.addStateDidChangeListener { [weak self] _, user in
            Task { @MainActor [weak self] in self?.authenticationChanged(user) }
        }
    }

    func signIn(email: String, password: String) {
        guard let auth, !signingIn else { return }
        signingIn = true
        error = nil
        auth.signIn(withEmail: email, password: password) { [weak self] _, failure in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.signingIn = false
                if let failure { self.error = Self.signInMessage(failure) }
            }
        }
    }

    func signOut() {
        guard let auth else { return }
        do {
            try auth.signOut()
            authenticationChanged(nil)
        } catch { self.error = "サインアウトできませんでした（\(GroupStore.reason(error))）" }
    }

    private func authenticationChanged(_ user: User?) {
        // Auth notifications are asynchronous too: ignore a captured user from before sign-out.
        guard auth?.currentUser?.uid == user?.uid, uid != user?.uid else { return }
        if let uid { defaults.removeObject(forKey: linkageKey(uid)) }
        generation = UUID() // Late listener/write callbacks from an old session have no effects.
        recovery = SyncWriteRecovery()
        snapshotLogGate = SyncSnapshotLogGate()
        listener?.remove()
        listener = nil
        reference = nil
        deferredSnapshot = nil
        pendingUploads = 0
        initialUpload = false
        conflict = nil
        canOverwrite = false
        invalidServerDocument = false
        error = nil
        uid = user?.uid
        email = user?.email
        signedIn = user != nil
        linked = uid.map { defaults.bool(forKey: linkageKey($0)) } ?? false
        guard let uid else {
            progressStatus = "サインインしていないため、時間割は同期されません。"
            return
        }
        progressStatus = "サーバーの時間割を確認しています。"
        let session = generation
        // The default persistent cache is intentionally retained. Metadata events are required
        // to distinguish server confirmation from cached data and pending local writes.
        let reference = Firestore.firestore().document("users/\(uid)/config/timetable")
        self.reference = reference
        listener = reference.addSnapshotListener(includeMetadataChanges: true) { [weak self] snapshot, failure in
            Task { @MainActor [weak self] in
                guard let self, self.generation == session else { return }
                if let failure {
                    self.error = Self.listenerMessage(failure)
                    self.files.record("sync listenerFailure action=keep reason=\(GroupStore.reason(failure))")
                } else if let snapshot { self.receive(snapshot) }
            }
        }
    }

    private func linkageKey(_ uid: String) -> String { "timetable-linked." + uid }

    private func markLinked() {
        guard let uid else { return }
        linked = true
        defaults.set(true, forKey: linkageKey(uid))
        conflict = nil
        canOverwrite = false
    }

    private func confirmed() {
        let now = Date()
        do {
            try files.saveLastSyncTime(now)
            lastSync = now
        } catch {
            self.error = "最終同期時刻を保存できませんでした（\(GroupStore.reason(error))）"
        }
        progressStatus = pendingUploads > 0 ? "サーバーへの保存を待っています。" : "同期済みです。"
    }

    private func receive(_ snapshot: DocumentSnapshot) {
        let fromServer = !snapshot.metadata.isFromCache
        let pending = snapshot.metadata.hasPendingWrites
        var remote: LocalTimetable?
        let kind: SyncRemoteDocument
        if !snapshot.exists {
            kind = .missing
        } else {
            do {
                remote = try LocalTimetable.parse(object: snapshot.data() ?? [:])
                kind = .valid
            } catch let invalid as SyncTimetable.InvalidDocument {
                kind = .invalid(invalid.code)
            } catch {
                kind = .invalid("invalid_document")
            }
        }
        let local = readLocal()
        let same = local.flatMap { local in remote.map { (try? TimetableContent.same(local, $0)) == true } } ?? false
        let type: String
        switch kind {
        case .missing: type = "missing"
        case .invalid(let code): type = "invalid code=\(code)"
        case .valid: type = same ? "valid same" : "valid changed"
        }
        let description = "sync snapshot fromCache=\(!fromServer) pendingWrites=\(pending) \(type)"
        func recordSnapshot(_ action: SyncAction, suffix: String = "") {
            if snapshotLogGate.shouldRecord(fromServer: fromServer, pendingWrites: pending,
                                            remote: kind, same: same, action: action) {
                files.record(description + " action=\(action)" + suffix)
            }
        }
        if initialUpload {
            deferredSnapshot = snapshot
            recordSnapshot(.keep, suffix: " awaitingUpload=true")
            return
        }
        let decision = TimetableSyncPolicy.decide(linked: linked, fromServer: fromServer,
            pendingWrites: pending, remote: kind, hasLocal: local != nil, same: same,
            initialUploadFailed: recovery.initialUploadFailed)
        recordSnapshot(decision.action)
        // Cached or pending snapshots must not dismiss an unresolved server conflict.
        if fromServer && !pending {
            conflict = nil
            canOverwrite = !linked && recovery.initialUploadFailed && local != nil
            if case .invalid = kind { invalidServerDocument = true } else { invalidServerDocument = false }
        }
        switch decision.notice {
        case .waitingForServer: progressStatus = "サーバーの時間割を確認しています。接続を待っています。"
        case .pendingWrites: progressStatus = "サーバーへの保存を待っています。"
        case .missing: progressStatus = "サーバーに時間割がありません"
        case .invalid(let code):
            progressStatus = "サーバーの時間割を読み込めません（\(code)）"
            canOverwrite = !linked && local != nil && (fromServer || recovery.initialUploadFailed)
        case .conflict: progressStatus = "使う時間割を選んでください。"
        case nil: break
        }
        switch decision.action {
        case .keep: break
        case .markLinked: markLinked()
        case .upload:
            if let local { upload(local, linking: true) }
        case .askUser: conflict = remote
        case .adopt:
            guard let remote, adopt(remote) else { return }
            if !linked { markLinked() }
            if !fromServer { progressStatus = "キャッシュの時間割を使っています。サーバーの確認を待っています。" }
        }
        if decision.action == .markLinked && !decision.confirmSync {
            progressStatus = "ひも付け済みです。時間割の保存を待っています。"
        }
        if decision.confirmSync { error = nil; confirmed() }
    }

    private func adopt(_ document: LocalTimetable) -> Bool {
        do {
            try adoptLocal(document)
            recovery.adoptedServer()
            files.record("sync action=adopt")
            return true
        } catch {
            self.error = "サーバーの時間割を端末に保存できませんでした（\(GroupStore.reason(error))）"
            files.record("sync action=keep localSaveFailure reason=\(GroupStore.reason(error))")
            return false
        }
    }

    func useServer() {
        guard !initialUpload, let conflict else { return }
        if adopt(conflict) {
            recovery.choseServer()
            markLinked()
            error = nil
            confirmed()
        }
    }

    func useLocal() {
        guard !linked, !initialUpload, conflict != nil || canOverwrite, let local = readLocal() else { return }
        upload(local, linking: true)
    }

    func localSaved(_ document: LocalTimetable) {
        if linked && signedIn {
            upload(document, linking: false)
        } else if signedIn && (invalidServerDocument || recovery.initialUploadFailed) {
            // A valid local timetable may have been created after the invalid server snapshot.
            canOverwrite = true
        }
    }

    private func upload(_ document: LocalTimetable, linking: Bool) {
        guard let reference else { return }
        let validation = TimetableParser.parse(object: document.fields)
        guard validation.valid else { return }
        var fields = document.fields
        fields["updatedAt"] = FieldValue.serverTimestamp()
        let session = generation
        writeSequence += 1
        let sequence = writeSequence
        recovery.started(sequence: sequence, linking: linking)
        pendingUploads += 1
        initialUpload = linking
        deferredSnapshot = nil
        error = nil
        progressStatus = "サーバーへの保存を待っています。"
        files.record("sync action=upload")
        reference.setData(fields) { [weak self] failure in
            Task { @MainActor [weak self] in
                guard let self, self.generation == session else { return }
                self.pendingUploads -= 1
                if linking { self.initialUpload = false }
                if let failure {
                    self.recovery.failed(sequence: sequence, reason: GroupStore.reason(failure))
                    self.files.record("sync writeFailure action=keep reason=\(GroupStore.reason(failure))")
                    if sequence == self.writeSequence {
                        self.progressStatus = "端末の時間割を使い続けています。"
                        self.canOverwrite = linking && self.readLocal() != nil
                    }
                } else {
                    self.recovery.succeeded(sequence: sequence)
                    self.files.record("sync writeSuccess")
                    if linking { self.markLinked() }
                    if sequence == self.writeSequence { self.error = nil }
                    self.confirmed()
                    if linking, let snapshot = self.deferredSnapshot,
                       !snapshot.metadata.isFromCache, !snapshot.metadata.hasPendingWrites {
                        self.receive(snapshot)
                    }
                }
                if linking { self.deferredSnapshot = nil }
            }
        }
    }

    private static func signInMessage(_ error: Error) -> String {
        let nsError = error as NSError
        guard nsError.domain == AuthErrors.domain else { return "サインインできませんでした（\(GroupStore.reason(error))）" }
        switch AuthErrorCode(rawValue: nsError.code) {
        case .invalidCredential, .wrongPassword, .userNotFound, .invalidEmail:
            return "メールアドレスかパスワードが違います。"
        case .networkError: return "通信できません。接続を確認してください。"
        case .tooManyRequests: return "試行回数が多すぎます。しばらく待ってからお試しください。"
        case .userDisabled: return "このアカウントは無効になっています。"
        case .userTokenExpired, .invalidUserToken: return "認証の有効期限が切れました。サインインが必要です。"
        default: return "サインインできませんでした（\(GroupStore.reason(error))）"
        }
    }

    private static func listenerMessage(_ error: Error) -> String {
        let nsError = error as NSError
        let authCode = nsError.domain == AuthErrors.domain ? AuthErrorCode(rawValue: nsError.code) : nil
        if authCode == .userTokenExpired || authCode == .invalidUserToken
            || (nsError.domain == FirestoreErrorDomain && nsError.code == FirestoreErrorCode.unauthenticated.rawValue) {
            return "サインインが必要です。一度サインアウトしてからサインインしてください（\(GroupStore.reason(error))）"
        }
        return "時間割を同期できません。端末の時間割を使い続けます（\(GroupStore.reason(error))）"
    }
}
