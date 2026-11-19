import SwiftUI

/// The phone's shell: its dynamic canvas behind four tabs (Home, Cerca, Libreria, Impostazioni)
/// and, floating at the bottom, the mini player pill over the glass tab bar.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(ConnectModel.self) private var connect
    @Environment(PartyManager.self) private var party
    @Environment(PhoneTheme.self) private var theme
    @State private var showPlayer = false
    @State private var tab = PhoneTab(rawValue: UserDefaults.standard.string(forKey: "tab") ?? "") ?? .home
    @State private var testArtist: Item?
    @State private var showDevices = false
    @State private var showParty = false

    var body: some View {
        let inset = Phone.bottomInset(miniPlayer: player.current != nil)
        ZStack(alignment: .bottom) {
            PhoneBackground()
            ZStack {
                page(.home) { HomeView() }
                page(.search) { SearchView() }
                page(.library) { LibraryView() }
                page(.settings) { SettingsView() }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: inset) }
            VStack(spacing: Phone.stackGap) {
                PhoneMiniPlayer { showPlayer = true }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                PhoneTabBar(tab: $tab)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, Phone.edge)
            .animation(.spring(duration: 0.35), value: player.current == nil)
        }
        .preferredColorScheme(.dark)
        .tint(theme.accent)
        .task(id: player.current?.coverUrl) { await theme.follow(cover: player.current?.coverUrl) }
        .fullScreenCover(isPresented: $showPlayer) {
            NowPlayingView()
        }
        .task { await testHooks() }
        .sheet(isPresented: $showDevices) { DevicesView() }
        .sheet(isPresented: $showParty) { NavigationStack { PartyView() } }
        .sheet(item: $testArtist) { a in NavigationStack { ArtistView(item: a).navigationDestination(for: Item.self) { ItemDestination(item: $0) } } }
    }

    /// Every tab keeps its own navigation while hidden, as the phone keeps each tab's back stack.
    private func page(_ t: PhoneTab, @ViewBuilder _ content: () -> some View) -> some View {
        NavigationStack { content().containerBackground(.clear, for: .navigation) }
            .opacity(tab == t ? 1 : 0)
            .allowsHitTesting(tab == t)
            .accessibilityHidden(tab != t)
    }
}

extension RootView {
    /// Test runs (the simulator takes no taps from a script): launch with `-autoplay "query"` to
    /// play the first song found, `-showPlayer YES` to open the full player, `-showLyrics YES`,
    /// `-devices YES` to open LFly Connect, `-follow \"name\"` to follow a device, `-like YES` to like it, `-download YES` to download it, `-tab library|search`, `-artist "query"` to open an artist, `-party host|find|CODE` for LFly Party, `-skipAfter N` to go to the next song after N s.
    private func testHooks() async {
        let d = UserDefaults.standard
        if let q = d.string(forKey: "autoplay"), let r = try? await model.core.search(query: q), !r.songs.isEmpty {
            player.play(r.songs)
            if d.bool(forKey: "like"), !model.library.isFavorite(r.songs[0]) { model.library.toggleFavorite(r.songs[0]) }
            if d.bool(forKey: "download") { model.library.download(r.songs[0], core: model.core) }
        }
        if let q = d.string(forKey: "artist"), let r = try? await model.core.search(query: q), let a = r.artists.first {
            testArtist = a
        }
        if d.bool(forKey: "devices") { showDevices = true }
        if let p = d.string(forKey: "party") {
            if p == "host" { party.startHosting() } else if p == "find" { party.startDiscovery() } else { party.join(code: p) }
            showParty = true
        }
        if let name = d.string(forKey: "follow") {
            for _ in 0..<20 where connect.following == nil {
                if let p = connect.peers.first(where: { $0.name.localizedCaseInsensitiveContains(name) }) {
                    connect.follow(p)
                    break
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
        let skip = d.integer(forKey: "skipAfter")
        if skip > 0 {
            try? await Task.sleep(for: .seconds(skip))
            print("LFLYTEST next at \(Date.now.timeIntervalSince1970)")
            player.next()
        }
        if d.bool(forKey: "showPlayer") {
            try? await Task.sleep(for: .seconds(1))
            showPlayer = true
        }
    }
}
