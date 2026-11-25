import SwiftUI

/// The phone's Home: greeting and tagline, the "Riprendi" hero (the last song, when nothing
/// plays), "Scelte rapide" as pages of 2×3 glass cards from what played lately, then YouTube
/// Music's shelves as rails of 160 pt cards.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @State private var shelves: [Shelf] = []
    @State private var failed: String?

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5...11: "Buongiorno"
        case 12...17: "Buon pomeriggio"
        default: "Buonasera"
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Phone.sectionGap) {
                VStack(alignment: .leading, spacing: 16) {
                    PhoneTitle(title: greeting, subtitle: "Continua ad ascoltare, continua a migliorare -Miau")
                    if player.current == nil, let last = library.history.first {
                        HomeHero(song: last) { player.play(library.history, from: 0) }
                    }
                }
                if !library.history.isEmpty {
                    QuickPicks(songs: Array(library.history.prefix(18)))
                }
                ForEach(Array(shelves.enumerated()), id: \.offset) { _, shelf in
                    HomeRail(shelf: shelf)
                }
                if shelves.isEmpty {
                    Group {
                        if let failed {
                            VStack(spacing: 8) {
                                Image(systemName: "wifi.exclamationmark").font(.title)
                                Text("Home non disponibile").font(.headline)
                                Text(failed).font(.footnote).foregroundStyle(PhoneTheme.textSecondary).multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(16)
                            .phoneGlass(radius: 24)
                            .padding(.horizontal, Phone.gutter)
                        } else {
                            ProgressView().frame(maxWidth: .infinity).padding(.top, 20)
                        }
                    }
                }
            }
            .padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: Item.self) { ItemDestination(item: $0) }
        .task { if shelves.isEmpty { await load() } }
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

/// The phone's hero: a glass card with the cover, a dot and "RIPRENDI", title and artist, and play.
private struct HomeHero: View {
    @Environment(PhoneTheme.self) private var theme
    let song: Song
    let play: () -> Void

    var body: some View {
        Button(action: play) {
            HStack(spacing: 14) {
                Artwork(url: song.coverUrl, radius: Phone.cardRadius).frame(width: 104, height: 104)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Circle().fill(theme.accent).frame(width: 6, height: 6)
                        Text("RIPRENDI").font(.system(size: 11, weight: .black)).tracking(0.7).foregroundStyle(theme.accent)
                    }
                    Text(song.title).font(.system(size: 21, weight: .bold)).foregroundStyle(.white).lineLimit(2)
                    Text(song.artist).font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "play.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(.black)
                    .frame(width: 48, height: 48)
                    .background(theme.accent, in: .circle)
            }
            .padding(12)
            .phoneGlass(radius: 24)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale(scale: 0.96))
        .padding(.horizontal, Phone.gutter)
        .accessibilityLabel("Riprendi \(song.title) di \(song.artist)")
    }
}

/// "Scelte rapide": pages of six glass cards, two by three, with dots under them.
private struct QuickPicks: View {
    @Environment(Player.self) private var player
    @Environment(PhoneTheme.self) private var theme
    let songs: [Song]
    @State private var page: Int? = 0

    var body: some View {
        let pages = stride(from: 0, to: songs.count, by: 6).map { Array(songs[$0..<min($0 + 6, songs.count)]) }
        VStack(alignment: .leading, spacing: 12) {
            PhoneSectionHeader(title: "Scelte rapide", subtitle: "In base ai tuoi ascolti recenti")
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 0) {
                    ForEach(Array(pages.enumerated()), id: \.offset) { p, items in
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                            ForEach(Array(items.enumerated()), id: \.offset) { i, song in
                                ShelfCard(song: song, current: player.current?.id == song.id) {
                                    player.play(songs, from: p * 6 + i)
                                }
                            }
                        }
                        .padding(.horizontal, Phone.gutter)
                        .containerRelativeFrame(.horizontal)
                        .id(p)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $page)
            .scrollIndicators(.hidden)
            if pages.count > 1 {
                HStack(spacing: 6) {
                    ForEach(0..<pages.count, id: \.self) { i in
                        Capsule()
                            .fill(i == (page ?? 0) ? Color.white : PhoneTheme.textTertiary)
                            .frame(width: i == (page ?? 0) ? 16 : 6, height: 6)
                    }
                }
                .frame(maxWidth: .infinity)
                .animation(.easeOut(duration: 0.22), value: page)
            }
        }
    }
}

/// A shelf card: 64 pt cover, two lines of title, the artist; the playing one has an accent ring.
private struct ShelfCard: View {
    @Environment(PhoneTheme.self) private var theme
    let song: Song
    let current: Bool
    let play: () -> Void

    var body: some View {
        Button(action: play) {
            HStack(spacing: 10) {
                Artwork(url: song.coverUrl, radius: 14).frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Text(song.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(2)
                        if current { Image(systemName: "waveform").font(.system(size: 13)).foregroundStyle(theme.accent) }
                    }
                    Text(song.artist).font(.system(size: 12)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(8)
            .phoneGlass()
            .overlay { if current { RoundedRectangle(cornerRadius: Phone.cardRadius).strokeBorder(theme.accent, lineWidth: 1.5) } }
            .multilineTextAlignment(.leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale(scale: 0.96))
        .songMenu(song)
    }
}

/// A YouTube Music shelf as the phone's rail: header, then 160 pt cards.
private struct HomeRail: View {
    @Environment(Player.self) private var player
    let shelf: Shelf

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            let songs = shelf.items.compactMap(\.song)
            PhoneSectionHeader(title: shelf.title, action: songs.count > 1 ? ("Riproduci tutto", { player.play(songs) }) : nil)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(Array(shelf.items.enumerated()), id: \.offset) { _, item in
                        ItemLink(item: item, siblings: shelf.items)
                    }
                }
                .padding(.horizontal, Phone.gutter)
            }
            .scrollIndicators(.hidden)
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
            .buttonStyle(PressScale(scale: 0.96))
        case .album, .playlist, .artist:
            NavigationLink(value: item) { ItemCard(item: item) }.buttonStyle(PressScale(scale: 0.96))
        }
    }
}
