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

/// The full player, as the phone's PlayerScreenV2: a drifting glow from the cover, the cover
/// (swipe it to change song; it fades into the next), the lyric being sung under it, the heart,
/// the seek bar, shuffle/previous/play/next/repeat, and a glass pill for Lyrics, Queue and
/// Devices. With a panel open the cover moves up into a small header.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scrubbing: Double?
    @State private var panel: Panel = UserDefaults.standard.bool(forKey: "showLyrics") ? .lyrics : UserDefaults.standard.bool(forKey: "showQueue") ? .queue : .none
    @State private var showDevices = false
    @State private var dragX: CGFloat = 0
    @Namespace private var cover

    enum Panel { case none, lyrics, queue }

    var body: some View {
        VStack(spacing: 18) {
            header
            if panel == .none {
                VStack(spacing: 14) {
                    heroCover
                    CurrentLyricStrip()
                        .frame(height: 30)
                }
                .frame(maxHeight: .infinity)
            } else {
                compactHeader
                Group {
                    if panel == .lyrics { LyricsView() } else { QueueList() }
                }
                .frame(maxHeight: .infinity)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            if panel == .none { meta }
            seekBar
            transport
            pill
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .background { AmbientGlow(url: player.current?.coverUrl).ignoresSafeArea() }
        .animation(reduceMotion ? nil : .spring(duration: 0.42, bounce: 0.18), value: panel)
        .presentationDragIndicator(.hidden)
        .sheet(isPresented: $showDevices) { DevicesView() }
    }

    // MARK: Parts

    private var header: some View {
        HStack {
            Capsule().fill(.secondary).frame(width: 40, height: 5).accessibilityHidden(true)
                .frame(maxWidth: .infinity)
                .overlay(alignment: .trailing) { moreMenu }
        }
        .padding(.top, 10)
    }

    private var heroCover: some View {
        let song = player.current
        return Artwork(url: song?.coverUrl, radius: 18)
            .aspectRatio(1, contentMode: .fit)
            .matchedGeometryEffect(id: "cover", in: cover)
            .shadow(color: .black.opacity(0.3), radius: 24, y: 12)
            // Pauses smaller, plays full size (the phone's hero scale).
            .scaleEffect(player.isPlaying ? 1 : 0.86)
            .offset(x: dragX)
            .rotationEffect(.degrees(Double(dragX) / 40))
            .id(song?.id)
            .transition(.asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.94)), removal: .opacity))
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.45), value: song?.id)
            .animation(reduceMotion ? nil : .spring(duration: 0.5, bounce: 0.28), value: player.isPlaying)
            .gesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { dragX = $0.translation.width }
                    .onEnded { g in
                        let w = g.translation.width
                        if w < -90 { player.next() } else if w > 90 { player.previous() }
                        withAnimation(.spring(duration: 0.35, bounce: 0.3)) { dragX = 0 }
                    }
            )
            .accessibilityLabel(song.map { "Copertina di \($0.title)" } ?? "Copertina")
            .accessibilityHint("Scorri a sinistra per il brano successivo, a destra per il precedente")
    }

    private var compactHeader: some View {
        HStack(spacing: 12) {
            Artwork(url: player.current?.coverUrl, radius: 8)
                .frame(width: 56, height: 56)
                .matchedGeometryEffect(id: "cover", in: cover)
            VStack(alignment: .leading, spacing: 2) {
                Text(player.current?.title ?? "").font(.headline).lineLimit(1)
                Text(player.current?.artist ?? "").font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            heart
        }
    }

    private var meta: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text(player.current?.title ?? "").font(.title2.bold()).lineLimit(1)
                    .contentTransition(.opacity)
                Text(player.current?.artist ?? "").font(.title3).foregroundStyle(.secondary).lineLimit(1)
            }
            .animation(.easeInOut(duration: 0.3), value: player.current?.id)
            Spacer(minLength: 12)
            heart
        }
    }

    @ViewBuilder
    private var heart: some View {
        if let song = player.current {
            let liked = library.isFavorite(song)
            Button { library.toggleFavorite(song) } label: {
                Image(systemName: liked ? "heart.fill" : "heart").font(.title2)
                    .foregroundStyle(liked ? Color.pink : .primary)
                    .scaleEffect(liked ? 1.12 : 1)
                    .animation(.spring(duration: 0.35, bounce: 0.6), value: liked)
                    .symbolEffect(.bounce, value: liked)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(liked ? "Togli da Mi piace" : "Mi piace")
        }
    }

    private var seekBar: some View {
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
                if player.rate != 1 { Text(String(format: "%.2g×", player.rate)) }
                Spacer()
                Text("-" + timeText(max(player.duration - (scrubbing ?? player.position), 0)))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }

    private var transport: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                Image(systemName: "shuffle").foregroundStyle(player.shuffle ? Color.accentColor : .secondary)
            }
            .accessibilityLabel(player.shuffle ? "Casuale attivo" : "Casuale")
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.title) }
                .accessibilityLabel("Brano precedente")
            Spacer()
            Button { player.toggle() } label: {
                ZStack {
                    if player.isLoading {
                        ProgressView().controlSize(.large)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 46))
                            .contentTransition(.symbolEffect(.replace))
                    }
                }
                .frame(width: 64, height: 64)
            }
            .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.title) }
                .accessibilityLabel("Brano successivo")
            Spacer()
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat")
                    .foregroundStyle(player.repeatMode == .off ? .secondary : Color.accentColor)
                    .contentTransition(.symbolEffect(.replace))
            }
            .accessibilityLabel(player.repeatMode == .off ? "Ripeti" : player.repeatMode == .all ? "Ripeti tutto" : "Ripeti questo brano")
        }
        .font(.title3)
        .buttonStyle(.plain)
    }

    /// The phone's bottom pill: Lyrics, Queue, Devices.
    private var pill: some View {
        HStack(spacing: 0) {
            segment("Testo", "quote.bubble", on: panel == .lyrics) { panel = panel == .lyrics ? .none : .lyrics }
                .disabled(player.lyrics == nil)
            Divider().frame(height: 22)
            segment("Coda", "list.bullet", on: panel == .queue) { panel = panel == .queue ? .none : .queue }
            Divider().frame(height: 22)
            segment("Dispositivi", "hifispeaker.2", on: false) { showDevices = true }
        }
        .padding(4)
        .glassEffect(.regular.interactive(), in: .capsule)
    }

    private func segment(_ title: String, _ icon: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .symbolVariant(on ? .fill : .none)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background { if on { Capsule().fill(.primary.opacity(0.12)) } }
        }
        .buttonStyle(.plain)
    }

    /// Download, add to a playlist, share, speed, sleep timer.
    private var moreMenu: some View {
        Menu {
            if let song = player.current {
                Button { player.playNext(song) } label: { Label("Riproduci di nuovo dopo", systemImage: "text.line.first.and.arrowtriangle.forward") }
                Menu {
                    Button { library.createPlaylist(song.title, songs: [song]) } label: { Label("Nuova playlist", systemImage: "plus") }
                    ForEach(library.playlists) { p in Button(p.name) { library.add(song, to: p) } }
                } label: { Label("Aggiungi a una playlist", systemImage: "text.badge.plus") }
                if library.isDownloaded(song) {
                    Button(role: .destructive) { library.removeDownload(song) } label: { Label("Rimuovi download", systemImage: "trash") }
                } else {
                    Button { library.download(song, core: model.core) } label: { Label("Scarica", systemImage: "arrow.down.circle") }
                }
                if let url = shareURL(song) {
                    ShareLink(item: url, subject: Text(song.title), message: Text("\(song.title) – \(song.artist)")) {
                        Label("Condividi", systemImage: "square.and.arrow.up")
                    }
                }
            }
            Menu {
                ForEach([0.75, 1.0, 1.25, 1.5], id: \.self) { r in
                    Button { player.setRate(Float(r)) } label: {
                        if Float(r) == player.rate { Label(String(format: "%.2g×", r), systemImage: "checkmark") } else { Text(String(format: "%.2g×", r)) }
                    }
                }
            } label: { Label("Velocità", systemImage: "gauge.with.dots.needle.67percent") }
            Menu {
                Button("Spento") { player.sleep(minutes: 0) }
                ForEach([15, 30, 45, 60], id: \.self) { m in Button("\(m) minuti") { player.sleep(minutes: m) } }
                Button("Alla fine del brano") { player.sleep(minutes: nil) }
            } label: {
                Label(player.sleepAt.map { "Timer: fino alle \($0.formatted(date: .omitted, time: .shortened))" } ?? (player.sleepAtEndOfSong ? "Timer: fine del brano" : "Timer"), systemImage: "moon.zzz")
            }
        } label: {
            Image(systemName: "ellipsis").font(.title3).frame(width: 40, height: 32)
        }
        .buttonStyle(.glass)
        .accessibilityLabel("Altre azioni")
    }

    private func shareURL(_ song: Song) -> URL? {
        guard song.id.hasPrefix("yt_") else { return nil }
        return URL(string: "https://music.youtube.com/watch?v=\(song.id.dropFirst(3))")
    }
}

/// The phone's AmbientGlow: the cover, blurred, drifting slowly behind everything.
struct AmbientGlow: View {
    let url: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drift = false

    var body: some View {
        ZStack {
            Color(.systemBackground)
            Artwork(url: url, radius: 0)
                .scaleEffect(1.6)
                .offset(x: drift ? 40 : -40, y: drift ? -30 : 30)
                .rotationEffect(.degrees(drift ? 8 : -8))
                .blur(radius: 70)
                .opacity(0.6)
                .animation(.easeInOut(duration: 0.9), value: url)
        }
        .clipped()
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 16).repeatForever(autoreverses: true)) { drift = true }
        }
    }
}

/// The line being sung, under the cover; each new line slides up into place.
struct CurrentLyricStrip: View {
    @Environment(Player.self) private var player

    var body: some View {
        let lines = player.lyrics?.lines ?? []
        let now = Int64(player.position * 1000)
        let i = lines.lastIndex { $0.timeMs <= now }
        let text = i.map { lines[$0].text } ?? ""
        ZStack {
            if !text.isEmpty {
                Text(text)
                    .font(.headline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .id(i)
                    .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .move(edge: .top).combined(with: .opacity)))
            }
        }
        .animation(.easeOut(duration: 0.32), value: i)
        .clipped()
        .accessibilityHidden(true)
    }
}

/// The queue: tap to play, swipe to remove, drag to move.
struct QueueList: View {
    @Environment(Player.self) private var player

    var body: some View {
        List {
            ForEach(Array(player.queue.enumerated()), id: \.offset) { i, song in
                Button { player.jump(to: i) } label: {
                    HStack(spacing: 12) {
                        Artwork(url: song.coverUrl, radius: 6).frame(width: 44, height: 44)
                        VStack(alignment: .leading) {
                            Text(song.title).lineLimit(1).foregroundStyle(i == player.index ? Color.accentColor : .primary)
                            Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        if i == player.index { Image(systemName: "waveform").symbolEffect(.variableColor.iterative, isActive: player.isPlaying).foregroundStyle(.tint) }
                    }
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
            }
            .onDelete { player.removeFromQueue(at: $0) }
            .onMove { player.moveInQueue(from: $0, to: $1) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
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
