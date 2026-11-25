import Foundation
import Observation
import Security
import SwiftUI
import WebKit

/// The YouTube Music account: signed in on Google's own page (the password never passes through
/// LFly), its session kept in the Keychain, used by the core for every request.
@MainActor
@Observable
final class Account {
    private(set) var profile: Profile?
    private(set) var playlists: [Item] = []
    private(set) var busy = false

    private let core: Core
    private let library: Library
    /// Told after every sign-in or sign-out (LFly Connect takes the account's secret).
    private let onChange: (Profile?) -> Void
    private static let keychainKey = "org.sonora.lfly.ytmusic-session"

    init(core: Core, library: Library, onChange: @escaping (Profile?) -> Void) {
        self.core = core
        self.library = library
        self.onChange = onChange
        if let saved = Self.load() {
            busy = true
            Task {
                defer { busy = false }
                // A session YouTube Music no longer takes is forgotten.
                if let p = try? await core.setSession(session: saved) {
                    profile = p
                    onChange(p)
                    await refresh()
                } else {
                    Self.store(nil)
                }
            }
        }
    }

    /// Uses the session the sign-in page left; the liked songs join the favourites.
    func signedIn(_ session: Session) async -> Bool {
        busy = true
        defer { busy = false }
        guard let p = try? await core.setSession(session: session) else { return false }
        profile = p
        onChange(p)
        Self.store(session)
        await refresh()
        if let liked = try? await core.likedSongs() { library.addFavorites(liked) }
        return true
    }

    func signOut() {
        Task {
            _ = try? await core.setSession(session: nil)
            onChange(nil)
        }
        Self.store(nil)
        profile = nil
        playlists = []
        WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }

    func refresh() async {
        playlists = (try? await core.accountPlaylists()) ?? []
    }

    // MARK: Keychain

    private static func store(_ session: Session?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keychainKey]
        SecItemDelete(base as CFDictionary)
        guard let s = session else { return }
        let o: [String: Any?] = ["cookie": s.cookie, "dataSyncId": s.dataSyncId, "pageId": s.pageId, "authUser": s.authUser, "visitorData": s.visitorData]
        guard let data = try? JSONSerialization.data(withJSONObject: o.compactMapValues { $0 }) else { return }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func load() -> Session? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keychainKey,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: String], let cookie = o["cookie"] else { return nil }
        return Session(cookie: cookie, dataSyncId: o["dataSyncId"], pageId: o["pageId"], authUser: o["authUser"], visitorData: o["visitorData"])
    }
}

/// Google's sign-in for YouTube Music. Once YouTube Music shows a signed-in account, its cookies
/// and the page's ids are read and the sheet closes by itself.
struct SignInView: View {
    @Environment(Account.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var failed = false

    var body: some View {
        NavigationStack {
            SignInWebView { session in
                Task {
                    if await account.signedIn(session) { dismiss() } else { failed = true }
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("YouTube Music")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla", role: .cancel) { dismiss() } }
            }
            .alert("YouTube Music non accetta l'accesso", isPresented: $failed) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Riprova, scegliendo l'account con cui usi YouTube Music.")
            }
        }
    }
}

private struct SignInWebView: UIViewRepresentable {
    let onSession: (Session) -> Void

    static let loginURL = URL(string: "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F")!
    static let probe = """
    (function () {
      try {
        if (!window.ytcfg || !window.ytcfg.get) return null;
        var get = function (key) { var v = window.ytcfg.get(key); return (v === undefined || v === null || v === '') ? null : String(v); };
        return JSON.stringify({ loggedIn: String(!!window.ytcfg.get('LOGGED_IN')), pageId: get('DELEGATED_SESSION_ID'),
          dataSyncId: get('DATASYNC_ID'), authUser: get('SESSION_INDEX'), visitorData: get('VISITOR_DATA') });
      } catch (e) { return null; }
    })()
    """

    func makeCoordinator() -> Coordinator { Coordinator(onSession: onSession) }

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: Self.loginURL))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let onSession: (Session) -> Void
        private var done = false

        init(onSession: @escaping (Session) -> Void) { self.onSession = onSession }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !done, webView.url?.host == "music.youtube.com" else { return }
            Task { await check(webView) }
        }

        private func check(_ webView: WKWebView) async {
            let cookies = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
                .filter { "music.youtube.com".hasSuffix($0.domain.trimmingCharacters(in: CharacterSet(charactersIn: "."))) }
            guard cookies.contains(where: { $0.name == "SAPISID" || $0.name == "__Secure-3PAPISID" }) else { return }
            guard let raw = try? await webView.evaluateJavaScript(SignInWebView.probe) as? String,
                  let p = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
                  p["loggedIn"] as? String == "true" else { return }
            done = true
            let header = cookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
            onSession(Session(
                cookie: header,
                dataSyncId: p["dataSyncId"] as? String,
                pageId: p["pageId"] as? String,
                authUser: p["authUser"] as? String,
                visitorData: p["visitorData"] as? String
            ))
        }
    }
}
