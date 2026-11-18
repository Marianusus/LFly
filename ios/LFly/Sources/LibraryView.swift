import SwiftUI

/// The library: liked songs, downloads, LFly's playlists, YouTube Music's (when signed in) and
/// what played lately. The account sits in the toolbar.
struct LibraryView: View {
    @Environment(Library.self) private var library
    @Environment(Account.self) private var account
    @State private var showAccount = false
    @State private var newName = ""
    @State private var naming = false

    var body: some View {
        List {
            Section {
                NavigationLink { LocalSongsView(title: "Brani che ti piacciono", songs: { $0.favorites }) } label: {
                    Label { Text("Brani che ti piacciono").badge(library.favorites.count) } icon: { Image(systemName: "heart.fill").foregroundStyle(.pink) }
                }
                NavigationLink { LocalSongsView(title: "Scaricati", songs: { $0.downloads }) } label: {
                    Label { Text("Scaricati").badge(library.downloads.count) } icon: { Image(systemName: "arrow.down.circle.fill") }
                }
                NavigationLink { LocalSongsView(title: "Ascoltati di recente", songs: { $0.history }) } label: {
                    Label("Ascoltati di recente", systemImage: "clock.fill")
                }
            }
            Section("Playlist") {
                Button { naming = true } label: { Label("Nuova playlist", systemImage: "plus") }
                ForEach(library.playlists) { p in
                    NavigationLink { LocalPlaylistView(playlist: p) } label: {
                        HStack(spacing: 12) {
                            Artwork(url: p.coverUrl, radius: 6).frame(width: 48, height: 48)
                            VStack(alignment: .leading) {
                                Text(p.name).lineLimit(1)
                                Text("\(p.songs.count) brani").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .onDelete { idx in idx.map { library.playlists[$0] }.forEach(library.delete) }
            }
            if !account.playlists.isEmpty {
                Section("Su YouTube Music") {
                    ForEach(Array(account.playlists.enumerated()), id: \.offset) { _, item in
                        NavigationLink(value: item) {
                            HStack(spacing: 12) {
                                Artwork(url: item.coverUrl, radius: 6).frame(width: 48, height: 48)
                                Text(item.title).lineLimit(1)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Libreria")
        .navigationDestination(for: Item.self) { ItemDestination(item: $0) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAccount = true } label: {
                    if let url = account.profile?.avatarUrl {
                        Artwork(url: url, radius: 14).frame(width: 28, height: 28)
                    } else {
                        Image(systemName: "person.crop.circle")
                    }
                }
                .accessibilityLabel("Account")
            }
        }
        .sheet(isPresented: $showAccount) { AccountView() }
        .alert("Nuova playlist", isPresented: $naming) {
            TextField("Nome", text: $newName)
            Button("Crea") { library.createPlaylist(newName); newName = "" }
            Button("Annulla", role: .cancel) { newName = "" }
        }
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
            }
        }
        .listStyle(.plain)
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
            }
            .onDelete { idx in idx.map { p.songs[$0] }.forEach { library.remove($0, from: p) } }
        }
        .listStyle(.plain)
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
            }
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
            .sheet(isPresented: $signingIn) { SignInView() }
        }
    }
}
