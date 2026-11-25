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
                    Text(list.title).font(.system(size: 24, weight: .bold)).foregroundStyle(.white).multilineTextAlignment(.center)
                    if let sub = list.subtitle { Text(sub).foregroundStyle(PhoneTheme.textSecondary) }
                    PlayShuffleButtons { player.play(list.songs) } shuffle: { player.play(list.songs.shuffled()) }
                        .disabled(list.songs.isEmpty)
                }
                .frame(maxWidth: .infinity)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                ForEach(Array(list.songs.enumerated()), id: \.offset) { i, song in
                    Button { player.play(list.songs, from: i) } label: { SongRow(song: song) }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
            }
        }
        .listStyle(.plain)
        .phonePage()
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
