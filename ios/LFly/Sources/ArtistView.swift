import SwiftUI

/// What a card opens: an album or playlist page, or an artist's.
struct ItemDestination: View {
    let item: Item

    var body: some View {
        Group {
            switch item.kind {
            case .artist: ArtistView(item: item)
            default: SongListView(item: item)
            }
        }
        .phoneCanvas()
    }
}

/// An artist: the picture, popular songs (a tap plays them in order), albums, singles, playlists.
struct ArtistView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    let item: Item
    @State private var artist: Artist?
    @State private var failed: String?

    var body: some View {
        ScrollView {
            if let a = artist {
                VStack(alignment: .leading, spacing: 24) {
                    ZStack(alignment: .bottomLeading) {
                        Artwork(url: a.coverUrl ?? item.coverUrl, radius: 0)
                            .frame(height: 300)
                            .overlay(LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .center, endPoint: .bottom))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(a.name).font(.largeTitle.bold()).foregroundStyle(.white)
                            if let sub = a.subtitle { Text(sub).font(.subheadline).foregroundStyle(.white.opacity(0.8)) }
                        }
                        .padding()
                    }
                    if !a.songs.isEmpty {
                        PlayShuffleButtons { player.play(a.songs) } shuffle: { player.play(a.songs.shuffled()) }
                            .padding(.horizontal, Phone.gutter)
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Brani popolari").font(.system(size: 22, weight: .bold))
                            ForEach(Array(a.songs.prefix(10).enumerated()), id: \.offset) { i, song in
                                Button { player.play(a.songs, from: i) } label: { SongRow(song: song) }.buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal)
                    }
                    shelf("Album", a.albums)
                    shelf("Singoli ed EP", a.singles)
                    shelf("Playlist", a.playlists)
                    if let d = a.description {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Informazioni").font(.title2.bold())
                            Text(d).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .ignoresSafeArea(edges: .top)
        .overlay {
            if artist == nil {
                if let failed {
                    ContentUnavailableView("Non si apre", systemImage: "exclamationmark.triangle", description: Text(failed))
                } else {
                    ProgressView()
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { artist = try await model.core.artist(browseId: item.id) } catch { failed = error.localizedDescription }
        }
    }

    @ViewBuilder
    private func shelf(_ title: String, _ items: [Item]) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                PhoneSectionHeader(title: title)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                            NavigationLink(value: it) { ItemCard(item: it) }.buttonStyle(PressScale(scale: 0.96))
                        }
                    }
                    .padding(.horizontal, Phone.gutter)
                }
                .scrollIndicators(.hidden)
            }
        }
    }
}

/// The long-press menu of a song: like, play next, queue, add to a playlist, download.
struct SongMenu: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(Library.self) private var library
    @Environment(Player.self) private var player
    let song: Song

    func body(content: Content) -> some View {
        content.contextMenu {
            Button { library.toggleFavorite(song) } label: {
                library.isFavorite(song) ? Label("Togli da Mi piace", systemImage: "heart.slash") : Label("Mi piace", systemImage: "heart")
            }
            Button { player.playNext(song) } label: { Label("Riproduci dopo", systemImage: "text.line.first.and.arrowtriangle.forward") }
            Button { player.addToQueue(song) } label: { Label("Aggiungi alla coda", systemImage: "text.line.last.and.arrowtriangle.forward") }
            Menu {
                Button { library.createPlaylist(song.title, songs: [song]) } label: { Label("Nuova playlist", systemImage: "plus") }
                ForEach(library.playlists) { p in
                    Button(p.name) { library.add(song, to: p) }
                }
            } label: { Label("Aggiungi a una playlist", systemImage: "text.badge.plus") }
            if library.isDownloaded(song) {
                Button(role: .destructive) { library.removeDownload(song) } label: { Label("Rimuovi download", systemImage: "trash") }
            } else {
                Button { library.download(song, core: model.core) } label: { Label("Scarica", systemImage: "arrow.down.circle") }
            }
        }
    }
}

extension View {
    func songMenu(_ song: Song) -> some View { modifier(SongMenu(song: song)) }
}
