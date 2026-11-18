import SwiftUI

@main
struct LFlyApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.player)
        }
    }
}

/// The core and the player, shared by every screen.
@MainActor
@Observable
final class AppModel {
    let core: Core
    let player: Player

    init() {
        // The core only fails without a network stack at all; there is nothing to show without it.
        let core = try! Core()
        self.core = core
        self.player = Player(core: core)
    }
}
