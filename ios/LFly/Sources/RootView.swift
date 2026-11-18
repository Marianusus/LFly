import SwiftUI

/// Tabs as Music has them: Home and Search (its own tab, at the trailing end), with the mini
/// player as the tab bar's accessory, on Liquid Glass.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var showPlayer = false

    var body: some View {
        TabView {
            Tab("Home", systemImage: "house.fill") {
                NavigationStack { HomeView() }
            }
            Tab(role: .search) {
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
    }
}

extension RootView {
    /// Test runs (the simulator takes no taps from a script): launch with `-autoplay "query"` to
    /// play the first song found, `-showPlayer YES` to open the full player, `-showLyrics YES`.
    private func testHooks() async {
        let d = UserDefaults.standard
        if let q = d.string(forKey: "autoplay"), let r = try? await model.core.search(query: q), !r.songs.isEmpty {
            player.play(r.songs)
        }
        if d.bool(forKey: "showPlayer") {
            try? await Task.sleep(for: .seconds(1))
            showPlayer = true
        }
    }
}
