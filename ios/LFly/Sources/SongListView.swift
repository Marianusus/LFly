import SwiftUI

/// An album or a playlist: its cover and name, Play and Shuffle, then the songs.
struct SongListView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let item: Item
    @State private var list: SongList?
    @State private var failed: String?

    var body: some View {
        List {
            if let list {
                VStack(spacing: 12) {
                    Artwork(url: list.coverUrl ?? item.coverUrl, radius: 12)
                        .frame(width: 240, height: 240)
                        .shadow(radius: 12, y: 6)
                    Text(list.title).font(.title2.bold()).multilineTextAlignment(.center)
                    if let sub = list.subtitle { Text(sub).foregroundStyle(.secondary) }
                    HStack(spacing: 12) {
                        Button { player.play(list.songs) } label: {
                            Label("Riproduci", systemImage: "play.fill").frame(maxWidth: .infinity)
                        }
                        Button { player.play(list.songs.shuffled()) } label: {
                            Label("Casuale", systemImage: "shuffle").frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .disabled(list.songs.isEmpty)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
                ForEach(Array(list.songs.enumerated()), id: \.offset) { i, song in
                    Button { player.play(list.songs, from: i) } label: { SongRow(song: song) }
                        .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if list == nil {
                if let failed {
                    ContentUnavailableView("Non si apre", systemImage: "exclamationmark.triangle", description: Text(failed))
                } else {
                    ProgressView()
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                list = item.kind == .album ? try await model.core.album(browseId: item.id) : try await model.core.playlist(id: item.id)
            } catch {
                failed = error.localizedDescription
            }
        }
    }
}
