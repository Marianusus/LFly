import SwiftUI

/// The phone's Library: "La tua libreria" with new-playlist in the header, the collections
/// (Brani preferiti, Cronologia, Brani offline) as 56 pt rows, then LFly's playlists and, when
/// signed in, YouTube Music's.
struct LibraryView: View {
    @Environment(Library.self) private var library
    @Environment(Account.self) private var account
    @Environment(PhoneTheme.self) private var theme
    @State private var newName = ""
    @State private var naming = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("La tua libreria").font(.system(size: 34, weight: .bold)).tracking(-0.7).foregroundStyle(.white)
                    Spacer()
                    Button { naming = true } label: {
                        Image(systemName: "plus").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 40, height: 40).background(PhoneTheme.glassTint, in: .circle)
                            .overlay(Circle().strokeBorder(PhoneTheme.glassBorder, lineWidth: 1))
                    }
                    .accessibilityLabel("Nuova playlist")
                }
                .padding(.horizontal, Phone.gutter).padding(.top, 12).padding(.bottom, 10)

                NavigationLink { LocalSongsView(title: "Brani preferiti", songs: { $0.favorites }) } label: {
                    CollectionRow(icon: "heart.fill", colors: [theme.accent, theme.accent.opacity(0.45)], title: "Brani preferiti",
                                  subtitle: "I brani che ami · \(library.favorites.count)")
                }
                NavigationLink { LocalSongsView(title: "Cronologia", songs: { $0.history }) } label: {
                    CollectionRow(icon: "clock.fill", colors: [Color(red: 0.30, green: 0.49, blue: 1), Color(red: 0.16, green: 0.25, blue: 0.6)],
                                  title: "Cronologia", subtitle: "Ascoltati di recente")
                }
                NavigationLink { LocalSongsView(title: "Brani offline", songs: { $0.downloads }) } label: {
                    CollectionRow(icon: "arrow.down.circle.fill", colors: [Color(red: 0.11, green: 0.73, blue: 0.33), Color(red: 0.05, green: 0.4, blue: 0.2)],
                                  title: "Brani offline", subtitle: "Disponibili offline · \(library.downloads.count)")
                }

                PhoneSectionHeader(title: "Playlist").padding(.top, 22).padding(.bottom, 4)
                if library.playlists.isEmpty {
                    VStack(spacing: 6) {
                        Image(systemName: "music.note.list").font(.system(size: 26)).foregroundStyle(theme.accent)
                        Text("Nessuna playlist").font(.headline).foregroundStyle(.white)
                        Text("Crea una playlist o importa le tue canzoni da Spotify con 1 click.")
                            .font(.subheadline).foregroundStyle(PhoneTheme.textSecondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(16).phoneGlass(radius: 24).padding(.horizontal, Phone.gutter)
                }
                ForEach(library.playlists) { p in
                    NavigationLink { LocalPlaylistView(playlist: p) } label: {
                        PlaylistRow(cover: p.coverUrl, title: p.name, subtitle: "Playlist · \(p.songs.count) brani")
                    }
                    .contextMenu { Button("Elimina", systemImage: "trash", role: .destructive) { library.delete(p) } }
                }
                if !account.playlists.isEmpty {
                    PhoneSectionHeader(title: "Su YouTube Music").padding(.top, 22).padding(.bottom, 4)
                    ForEach(Array(account.playlists.enumerated()), id: \.offset) { _, item in
                        NavigationLink(value: item) { PlaylistRow(cover: item.coverUrl, title: item.title, subtitle: item.subtitle ?? "Playlist") }
                    }
                }
            }
            .buttonStyle(.plain)
            .padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(for: Item.self) { ItemDestination(item: $0) }
        .alert("Nuova playlist", isPresented: $naming) {
            TextField("La mia mix", text: $newName)
            Button("Crea") { library.createPlaylist(newName); newName = "" }
            Button("Annulla", role: .cancel) { newName = "" }
        } message: {
            Text("Dai un nome alla tua playlist:")
        }
    }
}

/// A collection: a 56 pt gradient tile with its symbol, title and subtitle.
private struct CollectionRow: View {
    let icon: String
    let colors: [Color]
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 22, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing), in: .rect(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                Text(subtitle).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(PhoneTheme.textTertiary)
        }
        .padding(.horizontal, Phone.gutter).padding(.vertical, 4)
        .contentShape(.rect)
    }
}

private struct PlaylistRow: View {
    let cover: String?
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 14) {
            Artwork(url: cover, radius: 6).frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                Text(subtitle).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
            }
            Spacer()
        }
        .padding(.horizontal, Phone.gutter).padding(.vertical, 4)
        .contentShape(.rect)
    }
}

/// A list of the library's songs (liked, downloaded, recent).
struct LocalSongsView: View {
    @Environment(Library.self) private var library
    @Environment(Player.self) private var player
    let title: String
    let songs: (Library) -> [Song]

    var body: some View {
        let list = songs(library)
        List {
            ForEach(Array(list.enumerated()), id: \.offset) { i, song in
                Button { player.play(list, from: i) } label: { SongRow(song: song) }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .phonePage()
        .overlay { if list.isEmpty { ContentUnavailableView(title, systemImage: "music.note.list", description: Text("Ancora niente qui.")) } }
        .navigationTitle(title)
        .toolbar {
            if !list.isEmpty {
                Button { player.play(list.shuffled()) } label: { Image(systemName: "shuffle") }.accessibilityLabel("Casuale")
            }
        }
    }
}

/// A playlist made in LFly: play, shuffle, swipe a song away.
struct LocalPlaylistView: View {
    @Environment(Library.self) private var library
    @Environment(Player.self) private var player
    let playlist: LocalPlaylist

    var body: some View {
        let p = library.playlists.first { $0.id == playlist.id } ?? playlist
        List {
            ForEach(Array(p.songs.enumerated()), id: \.offset) { i, song in
                Button { player.play(p.songs, from: i) } label: { SongRow(song: song) }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            .onDelete { idx in idx.map { p.songs[$0] }.forEach { library.remove($0, from: p) } }
        }
        .listStyle(.plain)
        .phonePage()
        .overlay { if p.songs.isEmpty { ContentUnavailableView(p.name, systemImage: "music.note.list", description: Text("Aggiungi brani dal menu di un brano: tieni premuto.")) } }
        .navigationTitle(p.name)
        .toolbar {
            if !p.songs.isEmpty {
                Button { player.play(p.songs.shuffled()) } label: { Image(systemName: "shuffle") }.accessibilityLabel("Casuale")
            }
        }
    }
}

/// The account sheet: sign in to YouTube Music, or who is signed in and sign out.
struct AccountView: View {
    @Environment(Account.self) private var account
    @Environment(\.dismiss) private var dismiss
    @State private var signingIn = false

    var body: some View {
        NavigationStack {
            List {
                if let p = account.profile {
                    Section {
                        HStack(spacing: 14) {
                            Artwork(url: p.avatarUrl, radius: 28).frame(width: 56, height: 56)
                            VStack(alignment: .leading) {
                                Text(p.name).font(.headline)
                                Text(p.email).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    } footer: {
                        Text("La Home e le playlist sono quelle del tuo account, e i brani che ti piacciono su YouTube Music sono anche qui.")
                    }
                    Section { Button("Esci", role: .destructive) { account.signOut() } }
                } else {
                    Section {
                        Button { signingIn = true } label: { Label("Accedi a YouTube Music", systemImage: "person.badge.key") }
                            .disabled(account.busy)
                    } footer: {
                        Text("Senza account, la Home e le playlist sono quelle pubbliche. Si apre la vera pagina di accesso di Google: la password non passa da LFly.")
                    }
                }
                SpotifySection()
            }
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
            .sheet(isPresented: $signingIn) { SignInView() }
        }
    }
}
