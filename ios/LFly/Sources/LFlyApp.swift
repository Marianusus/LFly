import SwiftUI

@main
struct LFlyApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.player)
                .environment(model.library)
                .environment(model.account)
                .environment(model.connect)
                .environment(model.party)
                .environment(model.theme)
                .environment(model.spotify)
                .environment(model.lyricsPrefs)
        }
    }
}

/// The core and the player, shared by every screen.
@MainActor
@Observable
final class AppModel {
    let core: Core
    let library: Library
    let player: Player
    let account: Account
    let connect: ConnectModel
    let party: PartyManager
    let spotify: SpotifyModel
    let lyricsPrefs = LyricsPrefs()
    let theme = PhoneTheme()

    init() {
        // The core only fails without a network stack at all; there is nothing to show without it.
        let core = try! Core()
        let library = Library()
        self.core = core
        self.library = library
        let player = Player(core: core, library: library)
        self.player = player
        self.party = PartyManager(player: player)
        self.spotify = SpotifyModel(library: library)
        let connect = ConnectModel(library: library)
        self.connect = connect
        self.account = Account(core: core, library: library) { [weak connect] _ in
            connect?.setAccount(core.connectSecret())
        }
    }
}
