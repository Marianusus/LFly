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

    init() {
        // The core only fails without a network stack at all; there is nothing to show without it.
        let core = try! Core()
        let library = Library()
        self.core = core
        self.library = library
        self.player = Player(core: core, library: library)
        self.account = Account(core: core, library: library)
    }
}
