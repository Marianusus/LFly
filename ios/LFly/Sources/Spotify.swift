import Foundation
import Observation
import Security
import SwiftUI
import WebKit

/// The Spotify account, only to bring the listener's own library in, as the phone does
/// (SpotifyAuth.kt, SpotifyLibrarySync.kt): the web session cookie from Spotify's real sign-in
/// page, then the web player's own token, read from the page it mints it for in a hidden web
/// view; the core (lfly-core's spotify_account) reads the library and writes it into LFly's.
@MainActor
@Observable
final class SpotifyModel {
    private(set) var connected = false
    private(set) var busy = false
    private(set) var last: SpotifySummary?
    var failed: String?

    private let library: Library
    private static let keychainKey = "org.sonora.lfly.spotify-sp_dc"

    init(library: Library) {
        self.library = library
        connected = Self.cookie() != nil
    }

    /// The sign-in page left a session: keep it and import at once.
    func signedIn(_ spDc: String) {
        Self.storeCookie(spDc)
        connected = true
        Task { await importNow() }
    }

    func importNow() async {
        guard let cookie = Self.cookie(), !busy else { return }
        busy = true
        defer { busy = false }
        guard let token = await TokenHarvester.harvest(cookie: cookie) else {
            failed = "Spotify non ha dato l'accesso: collegalo di nuovo."
            return
        }
        let config = URL.applicationSupportDirectory
        do {
            let s = try await spotifyImport(configDir: config.path, libraryDir: library.folder.path, spDc: cookie, token: token)
            last = s
            library.reload()
            library.onEdit?()
        } catch {
            failed = error.localizedDescription
        }
    }

    /// Forgets the session; imported playlists stay.
    func disconnect() {
        Self.storeCookie(nil)
        connected = false
        last = nil
    }

    // MARK: Keychain

    private static func storeCookie(_ value: String?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keychainKey]
        SecItemDelete(base as CFDictionary)
        guard let v = value else { return }
        var add = base
        add[kSecValueData as String] = Data(v.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func cookie() -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keychainKey,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}

/// Spotify's sign-in page; closes once the session cookie is there.
struct SpotifySignInView: View {
    @Environment(SpotifyModel.self) private var spotify
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SpotifyWebView { cookie in
                spotify.signedIn(cookie)
                dismiss()
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Spotify")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Annulla", role: .cancel) { dismiss() } } }
        }
    }
}

private struct SpotifyWebView: UIViewRepresentable {
    let onCookie: (String) -> Void
    static let loginURL = URL(string: "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")!

    func makeCoordinator() -> Coordinator { Coordinator(onCookie: onCookie) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.load(URLRequest(url: Self.loginURL))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let onCookie: (String) -> Void
        private var done = false
        init(onCookie: @escaping (String) -> Void) { self.onCookie = onCookie }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task {
                guard !done else { return }
                let all = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
                if let c = all.first(where: { $0.name == "sp_dc" && !$0.value.isEmpty }) {
                    done = true
                    onCookie(c.value)
                }
            }
        }
    }
}

/// Loads the web player with the cookie in a hidden web view and reports the signed-in token the
/// page asks for itself (`/api/token`), as the phone's SpotifyAuth.harvest.
@MainActor
final class TokenHarvester: NSObject, WKScriptMessageHandler {
    private static let hook = """
    (function () {
      if (window.__lflyTokenHook) return;
      window.__lflyTokenHook = true;
      var report = function (body) {
        try { window.webkit.messageHandlers.lfly.postMessage(JSON.stringify({ ua: navigator.userAgent, body: body })); } catch (e) {}
      };
      var isToken = function (u) { try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; } };
      var originalFetch = window.fetch;
      if (originalFetch) {
        window.fetch = function (input, init) {
          var url = (input && input.url) ? input.url : input;
          var result = originalFetch.apply(this, arguments);
          if (isToken(url)) {
            try { result.then(function (r) { r.clone().text().then(report).catch(function () {}); }).catch(function () {}); } catch (e) {}
          }
          return result;
        };
      }
      var originalOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (method, url) { this.__lflyUrl = url; return originalOpen.apply(this, arguments); };
      var originalSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send = function () {
        var xhr = this;
        try { xhr.addEventListener('load', function () { if (isToken(xhr.__lflyUrl)) report(xhr.responseText); }); } catch (e) {}
        return originalSend.apply(this, arguments);
      };
    })();
    """

    private var continuation: CheckedContinuation<SpotifyToken?, Never>?
    private var view: WKWebView?

    static func harvest(cookie: String) async -> SpotifyToken? {
        let h = TokenHarvester()
        return await h.run(cookie: cookie)
    }

    private func run(cookie: String) async -> SpotifyToken? {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.userContentController.addUserScript(WKUserScript(source: Self.hook, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        config.userContentController.add(self, name: "lfly")
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: config)
        // Out of sight but in a window, so the page runs as it would on screen.
        view.alpha = 0.01
        view.isUserInteractionEnabled = false
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        window?.addSubview(view)
        self.view = view
        defer {
            view.configuration.userContentController.removeScriptMessageHandler(forName: "lfly")
            view.removeFromSuperview()
            self.view = nil
        }
        let props: [HTTPCookiePropertyKey: Any] = [
            .name: "sp_dc", .value: cookie, .domain: ".spotify.com", .path: "/", .secure: true,
        ]
        if let c = HTTPCookie(properties: props) {
            await config.websiteDataStore.httpCookieStore.setCookie(c)
        }
        return await withCheckedContinuation { (k: CheckedContinuation<SpotifyToken?, Never>) in
            continuation = k
            view.load(URLRequest(url: URL(string: "https://open.spotify.com/")!))
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(35))
                self?.finish(nil)
            }
        }
    }

    private func finish(_ token: SpotifyToken?) {
        continuation?.resume(returning: token)
        continuation = nil
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let text = message.body as? String,
              let outer = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let bodyText = outer["body"] as? String,
              let body = try? JSONSerialization.jsonObject(with: Data(bodyText.utf8)) as? [String: Any],
              let token = body["accessToken"] as? String, !token.isEmpty else { return }
        // An anonymous token comes first, before the cookie counts: wait for the signed-in one.
        if body["isAnonymous"] as? Bool == true { return }
        finish(SpotifyToken(
            accessToken: token,
            expiresAtMs: (body["accessTokenExpirationTimestampMs"] as? NSNumber)?.int64Value,
            clientId: body["clientId"] as? String,
            userAgent: outer["ua"] as? String ?? ""
        ))
    }
}

/// The Spotify rows of the account sheet.
struct SpotifySection: View {
    @Environment(SpotifyModel.self) private var spotify
    @State private var signingIn = false

    var body: some View {
        Section {
            if spotify.connected {
                Button { Task { await spotify.importNow() } } label: {
                    HStack {
                        Label("Importa la libreria ora", systemImage: "square.and.arrow.down")
                        Spacer()
                        if spotify.busy { ProgressView() }
                    }
                }
                .disabled(spotify.busy)
                if let s = spotify.last {
                    Text("Importate \(s.playlists) playlist e \(s.liked) brani che ti piacciono (\(s.newFavorites) nuovi).")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Button("Scollega Spotify", role: .destructive) { spotify.disconnect() }
            } else {
                Button { signingIn = true } label: { Label("Collega Spotify", systemImage: "link") }
            }
        } header: {
            Text("Spotify")
        } footer: {
            Text("Porta in LFly le tue playlist e i brani che ti piacciono su Spotify. Si apre la pagina di accesso di Spotify; LFly tiene solo la sessione, su questo iPhone. Ogni brano viene cercato su YouTube Music quando lo ascolti.")
        }
        .sheet(isPresented: $signingIn) { SpotifySignInView() }
        .alert("Spotify", isPresented: Binding(get: { spotify.failed != nil }, set: { if !$0 { spotify.failed = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(spotify.failed ?? "")
        }
    }
}
