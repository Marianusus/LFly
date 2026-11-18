import SwiftUI

/// Search: songs first (a tap plays them, the results are the queue), then albums and playlists.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @State private var query = ""
    @State private var result: SearchResult?
    @State private var searching = false

    var body: some View {
        List {
            if let r = result {
                if !r.songs.isEmpty {
                    Section("Brani") {
                        ForEach(Array(r.songs.enumerated()), id: \.offset) { i, song in
                            Button { player.play(r.songs, from: i) } label: { SongRow(song: song) }
                                .buttonStyle(.plain)
                        }
                    }
                }
                let lists = r.albums + r.playlists
                if !lists.isEmpty {
                    Section("Album e playlist") {
                        ForEach(Array(lists.enumerated()), id: \.offset) { _, item in
                            NavigationLink(value: item) {
                                HStack(spacing: 12) {
                                    Artwork(url: item.coverUrl, radius: 6).frame(width: 48, height: 48)
                                    VStack(alignment: .leading) {
                                        Text(item.title).lineLimit(1)
                                        Text(item.subtitle ?? "").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if searching && result == nil {
                ProgressView()
            } else if result == nil {
                ContentUnavailableView("Cosa vuoi ascoltare?", systemImage: "magnifyingglass", description: Text("Brani, album, artisti e playlist di YouTube Music."))
            }
        }
        .navigationTitle("Cerca")
        .navigationDestination(for: Item.self) { SongListView(item: $0) }
        .searchable(text: $query, prompt: "Brani, artisti, album")
        .onSubmit(of: .search) { Task { await search() } }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searching = true
        defer { searching = false }
        result = try? await model.core.search(query: q)
    }
}
