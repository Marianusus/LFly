import SwiftUI

/// YouTube Music's home shelves.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var shelves: [Shelf] = []
    @State private var failed: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    VStack(alignment: .leading, spacing: 12) {
                        Text(shelf.title).font(.title2.bold()).padding(.horizontal)
                        ScrollView(.horizontal) {
                            LazyHStack(alignment: .top, spacing: 14) {
                                ForEach(Array(shelf.items.enumerated()), id: \.offset) { i, item in
                                    ItemLink(item: item, siblings: shelf.items)
                                }
                            }
                            .padding(.horizontal)
                        }
                        .scrollIndicators(.hidden)
                    }
                }
            }
            .padding(.vertical)
        }
        .overlay {
            if shelves.isEmpty {
                if let failed {
                    ContentUnavailableView("Home non disponibile", systemImage: "wifi.exclamationmark", description: Text(failed))
                } else {
                    ProgressView()
                }
            }
        }
        .navigationTitle("Home")
        .navigationDestination(for: Item.self) { ItemDestination(item: $0) }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        do {
            shelves = try await model.core.home()
            failed = nil
        } catch {
            failed = error.localizedDescription
        }
    }
}

/// Opens what a card stands for: a song plays (with its shelf as the queue), the rest opens.
struct ItemLink: View {
    @Environment(Player.self) private var player
    let item: Item
    var siblings: [Item] = []

    var body: some View {
        switch item.kind {
        case .song:
            Button {
                let songs = siblings.compactMap(\.song)
                let start = songs.firstIndex { $0.id == item.id } ?? 0
                player.play(songs.isEmpty ? [item.song].compactMap { $0 } : songs, from: start)
            } label: { ItemCard(item: item) }
            .buttonStyle(.plain)
        case .album, .playlist, .artist:
            NavigationLink(value: item) { ItemCard(item: item) }.buttonStyle(.plain)
        }
    }
}
