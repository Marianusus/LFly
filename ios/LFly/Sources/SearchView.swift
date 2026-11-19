import SwiftUI

/// The phone's Search: big title, a glass field, filter chips (Tutto, Brani, Video, Artisti,
/// Album, Playlist) and, before typing, a glass card with popular searches. Songs play with the
/// results as the queue; the rest opens.
struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(PhoneTheme.self) private var theme
    @State private var query = ""
    @State private var result: SearchResult?
    @State private var searched = ""
    @State private var searching = false
    @State private var filter: Filter = .all
    @FocusState private var focused: Bool

    enum Filter: String, CaseIterable {
        case all = "Tutto", songs = "Brani", videos = "Video", artists = "Artisti", albums = "Album", playlists = "Playlist"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Cerca").font(.system(size: 34, weight: .bold)).tracking(-0.7).foregroundStyle(.white)
                    .padding(.horizontal, Phone.gutter).padding(.top, 12)
                field
                chips
                content.padding(.top, 6)
            }
            .padding(.bottom, 16)
        }
        .scrollDismissesKeyboard(.immediately)
        .scrollIndicators(.hidden)
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: Item.self) { ItemDestination(item: $0) }
    }

    private var field: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(PhoneTheme.textSecondary)
            TextField("", text: $query, prompt: Text("Brani, artisti, podcast, album...").foregroundStyle(PhoneTheme.textTertiary))
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .foregroundStyle(.white)
                .onSubmit { Task { await search() } }
            if !query.isEmpty {
                Button { query = ""; result = nil; focused = true } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(PhoneTheme.textSecondary)
                }
                .accessibilityLabel("Cancella ricerca")
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(PhoneTheme.glassTint, in: .capsule)
        .overlay(Capsule().strokeBorder(focused ? theme.accent.opacity(0.6) : PhoneTheme.glassBorder, lineWidth: 1))
        .padding(.horizontal, Phone.gutter)
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases, id: \.self) { f in
                    let on = f == filter
                    Button { filter = f } label: {
                        Text(f.rawValue).font(.system(size: 15, weight: on ? .semibold : .medium))
                            .foregroundStyle(on ? theme.accent : .white)
                            .padding(.horizontal, 18).frame(height: 40)
                            .background(on ? theme.accent.opacity(0.17) : PhoneTheme.glassTint, in: .capsule)
                            .overlay(Capsule().strokeBorder(on ? theme.accent.opacity(0.4) : PhoneTheme.glassBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(.horizontal, Phone.gutter)
        }
        .scrollIndicators(.hidden)
        .animation(.easeOut(duration: 0.18), value: filter)
    }

    @ViewBuilder private var content: some View {
        if searching && result == nil {
            ProgressView().frame(maxWidth: .infinity).padding(.top, 60)
        } else if let r = result {
            results(r)
        } else {
            prompt
        }
    }

    /// Before a search: the phone's glass card with three popular searches.
    private var prompt: some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 34, weight: .semibold)).foregroundStyle(theme.accent)
            Text("Trova la tua musica preferita").font(.system(size: 20, weight: .bold)).foregroundStyle(.white)
            Text("Cerca brani, artisti e album con streaming lossless e testi sincronizzati.")
                .font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary).multilineTextAlignment(.center)
            Text("Ricerche popolari").font(.system(size: 14, weight: .medium)).foregroundStyle(PhoneTheme.textSecondary).padding(.top, 10)
            HStack(spacing: 8) {
                ForEach(["The Weeknd", "Daft Punk", "Billie Eilish"], id: \.self) { s in
                    Button { query = s; Task { await search() } } label: {
                        Text(s).font(.system(size: 14, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                            .padding(.horizontal, 14).frame(height: 38)
                            .background(PhoneTheme.glassTint, in: .capsule)
                            .overlay(Capsule().strokeBorder(PhoneTheme.glassBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .phoneGlass(radius: 24)
        .padding(.horizontal, Phone.gutter + 8)
        .padding(.top, 80)
    }

    @ViewBuilder private func results(_ r: SearchResult) -> some View {
        let songs = r.songs.filter { !$0.isVideo }, videos = r.songs.filter(\.isVideo)
        let empty: Bool = switch filter {
        case .all: r.songs.isEmpty && r.artists.isEmpty && r.albums.isEmpty && r.playlists.isEmpty
        case .songs: songs.isEmpty
        case .videos: videos.isEmpty
        case .artists: r.artists.isEmpty
        case .albums: r.albums.isEmpty
        case .playlists: r.playlists.isEmpty
        }
        if empty {
            VStack(spacing: 6) {
                Text("Nessun risultato").font(.headline).foregroundStyle(.white)
                Text("Nessun risultato trovato per \"\(searched)\"").font(.subheadline).foregroundStyle(PhoneTheme.textSecondary)
            }
            .frame(maxWidth: .infinity).padding(.top, 60)
        } else {
            LazyVStack(alignment: .leading, spacing: 4) {
                switch filter {
                case .all:
                    if let top = r.songs.first { topResult(top, queue: r.songs) }
                    if !r.songs.isEmpty { header("Brani"); songRows(Array(r.songs.prefix(6)), queue: r.songs) }
                    if !r.artists.isEmpty { header("Artisti"); itemRows(Array(r.artists.prefix(4)), round: true) }
                    if !r.albums.isEmpty { header("Album"); itemRows(Array(r.albums.prefix(6))) }
                    if !r.playlists.isEmpty { header("Playlist"); itemRows(Array(r.playlists.prefix(6))) }
                case .songs: songRows(songs, queue: songs)
                case .videos: songRows(videos, queue: videos)
                case .artists: itemRows(r.artists, round: true)
                case .albums: itemRows(r.albums)
                case .playlists: itemRows(r.playlists)
                }
            }
        }
    }

    private func header(_ title: String) -> some View {
        Text(title).font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
            .padding(.horizontal, Phone.gutter).padding(.top, 18).padding(.bottom, 4)
    }

    /// The phone's "Risultato principale": a glass card, big cover, play button.
    private func topResult(_ song: Song, queue: [Song]) -> some View {
        Button { player.play(queue, from: 0) } label: {
            HStack(spacing: 14) {
                Artwork(url: song.coverUrl, radius: 16).frame(width: 96, height: 96)
                VStack(alignment: .leading, spacing: 4) {
                    Text("RISULTATO PRINCIPALE").font(.system(size: 11, weight: .black)).tracking(0.7).foregroundStyle(theme.accent)
                    Text(song.title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white).lineLimit(2)
                    Text("Brano • \(song.artist)").font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "play.fill").font(.system(size: 18)).foregroundStyle(.black)
                    .frame(width: 46, height: 46).background(theme.accent, in: .circle)
            }
            .padding(12)
            .phoneGlass(radius: 24)
            .multilineTextAlignment(.leading)
        }
        .buttonStyle(PressScale(scale: 0.97))
        .padding(.horizontal, Phone.gutter)
        .songMenu(song)
    }

    private func songRows(_ list: [Song], queue: [Song]) -> some View {
        ForEach(Array(list.enumerated()), id: \.offset) { _, song in
            Button { player.play(queue, from: queue.firstIndex { $0.id == song.id } ?? 0) } label: {
                SongRow(song: song).padding(.horizontal, Phone.gutter).padding(.vertical, 4)
            }
            .buttonStyle(.plain)
        }
    }

    private func itemRows(_ items: [Item], round: Bool = false) -> some View {
        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
            NavigationLink(value: item) {
                HStack(spacing: 12) {
                    Artwork(url: item.coverUrl, radius: round ? 28 : 8).frame(width: 56, height: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                        if let s = item.subtitle {
                            Text(s).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(PhoneTheme.textTertiary)
                }
                .padding(.horizontal, Phone.gutter).padding(.vertical, 4)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        focused = false
        searching = true
        result = nil
        defer { searching = false }
        result = try? await model.core.search(query: q)
        searched = q
    }
}
