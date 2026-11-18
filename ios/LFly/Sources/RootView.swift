import SwiftUI

/// Tabs as Music has them: Home and Search (its own tab, at the trailing end), with the mini
/// player as the tab bar's accessory, on Liquid Glass.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var showPlayer = false
    @State private var tab = UserDefaults.standard.string(forKey: "tab") ?? "home"
    @State private var testArtist: Item?

    var body: some View {
        TabView(selection: $tab) {
            Tab("Home", systemImage: "house.fill", value: "home") {
                NavigationStack { HomeView() }
            }
            Tab("Libreria", systemImage: "music.note.square.stack.fill", value: "library") {
                NavigationStack { LibraryView() }
            }
            Tab(value: "search", role: .search) {
                NavigationStack { SearchView() }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .tabViewBottomAccessory {
            MiniPlayer()
                .contentShape(.rect)
                .onTapGesture { if player.current != nil { showPlayer = true } }
        }
        .sheet(isPresented: $showPlayer) {
            NowPlayingView()
        }
        .task { await testHooks() }
        .sheet(item: $testArtist) { a in NavigationStack { ArtistView(item: a).navigationDestination(for: Item.self) { ItemDestination(item: $0) } } }
    }
}

extension RootView {
    /// Test runs (the simulator takes no taps from a script): launch with `-autoplay "query"` to
    /// play the first song found, `-showPlayer YES` to open the full player, `-showLyrics YES`,
    /// `-like YES` to like it, `-download YES` to download it, `-tab library|search`, `-artist "query"` to open an artist.
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
        if d.bool(forKey: "showPlayer") {
            try? await Task.sleep(for: .seconds(1))
            showPlayer = true
        }
    }
}
