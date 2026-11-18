import SwiftUI

/// The tab bar's accessory: what plays, play/pause and next.
struct MiniPlayer: View {
    @Environment(Player.self) private var player

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: player.current?.coverUrl, radius: 6).frame(width: 36, height: 36)
            VStack(alignment: .leading, spacing: 0) {
                Text(player.current?.title ?? "Niente in riproduzione").font(.subheadline.weight(.semibold)).lineLimit(1)
                if let artist = player.current?.artist {
                    Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if player.isLoading {
                ProgressView()
            } else {
                Button { player.toggle() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.title3)
                }
                .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                .disabled(player.current == nil)
            }
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.title3) }
                .accessibilityLabel("Brano successivo")
                .disabled(player.current == nil)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 14)
    }
}

/// The full player: cover, time, controls, and the synced lyrics under them.
struct NowPlayingView: View {
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @State private var scrubbing: Double?
    @State private var showLyrics = UserDefaults.standard.bool(forKey: "showLyrics")
    @State private var showDevices = false

    var body: some View {
        VStack(spacing: 22) {
            Capsule().fill(.secondary).frame(width: 40, height: 5).padding(.top, 8).accessibilityHidden(true)
            if showLyrics {
                LyricsView().frame(maxHeight: .infinity)
            } else {
                Artwork(url: player.current?.coverUrl, radius: 16)
                    .aspectRatio(1, contentMode: .fit)
                    .shadow(radius: 24, y: 10)
                    .scaleEffect(player.isPlaying ? 1 : 0.86)
                    .animation(.spring(duration: 0.45, bounce: 0.25), value: player.isPlaying)
                    .padding(.horizontal, 8)
                    .frame(maxHeight: .infinity)
            }
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(player.current?.title ?? "").font(.title2.bold()).lineLimit(1)
                    Text(player.current?.artist ?? "").font(.title3).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 12)
                if let song = player.current {
                    let liked = library.isFavorite(song)
                    Button { library.toggleFavorite(song) } label: {
                        Image(systemName: liked ? "heart.fill" : "heart").font(.title2)
                            .foregroundStyle(liked ? Color.pink : .primary)
                            .symbolEffect(.bounce, value: liked)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(liked ? "Togli da Mi piace" : "Mi piace")
                }
            }
            VStack(spacing: 4) {
                Slider(
                    value: Binding(get: { scrubbing ?? player.position }, set: { scrubbing = $0 }),
                    in: 0...max(player.duration, 1)
                ) { editing in
                    if !editing, let s = scrubbing {
                        player.seek(to: s)
                        scrubbing = nil
                    }
                }
                HStack {
                    Text(timeText(scrubbing ?? player.position))
                    Spacer()
                    Text("-" + timeText(max(player.duration - (scrubbing ?? player.position), 0)))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            HStack(spacing: 48) {
                Button { player.previous() } label: { Image(systemName: "backward.fill") }
                    .accessibilityLabel("Brano precedente")
                Button { player.toggle() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 44))
                        .contentTransition(.symbolEffect(.replace))
                }
                .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                Button { player.next() } label: { Image(systemName: "forward.fill") }
                    .accessibilityLabel("Brano successivo")
            }
            .font(.title)
            .buttonStyle(.plain)
            HStack {
                Button { showDevices = true } label: { Image(systemName: "hifispeaker.2") }
                    .buttonStyle(.glass)
                    .accessibilityLabel("Dispositivi")
                Spacer()
                Button { withAnimation(.smooth) { showLyrics.toggle() } } label: {
                    Image(systemName: "quote.bubble")
                        .symbolVariant(showLyrics ? .fill : .none)
                }
                .buttonStyle(.glass)
                .accessibilityLabel(showLyrics ? "Nascondi il testo" : "Mostra il testo")
                .disabled(player.lyrics == nil)
            }
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 24)
        .background {
            // The cover, blurred, behind everything (the phone's Copertina dinamica).
            Artwork(url: player.current?.coverUrl, radius: 0)
                .blur(radius: 60)
                .opacity(0.55)
                .ignoresSafeArea()
        }
        .presentationDragIndicator(.hidden)
        .sheet(isPresented: $showDevices) { DevicesView() }
    }
}

/// Synced lyrics: the sung line in front, the others dimmed; follows the song.
struct LyricsView: View {
    @Environment(Player.self) private var player

    var body: some View {
        let lines = player.lyrics?.lines ?? []
        let now = Int64(player.position * 1000)
        let active = lines.lastIndex { $0.timeMs <= now } ?? -1
        ScrollViewReader { proxy in
            ScrollView {
                if lines.isEmpty {
                    Text(player.lyrics?.plain ?? "Nessun testo").font(.title3).frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    VStack(alignment: .leading, spacing: 18) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                            Text(line.text.isEmpty ? "♪" : line.text)
                                .font(.title2.bold())
                                .foregroundStyle(i == active ? .primary : .secondary)
                                .opacity(i == active ? 1 : 0.5)
                                .id(i)
                                .onTapGesture { player.seek(to: Double(line.timeMs) / 1000) }
                        }
                    }
                    .padding(.vertical, 120)
                }
            }
            .scrollIndicators(.hidden)
            .onChange(of: active) { _, i in
                withAnimation(.smooth) { proxy.scrollTo(i, anchor: .center) }
            }
        }
    }
}
