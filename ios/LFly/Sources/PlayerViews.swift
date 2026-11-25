import AVKit
import SwiftUI

/// The glass bar's accessory, as the phone's GlassNowPlaying: a progress hairline, cover, title
/// and artist, heart, play/pause and next; when the bar shrinks on scroll (inline), the phone's
/// GlassInlineNowPlaying: small cover, title, play/pause.
struct MiniPlayer: View {
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @Environment(PhoneTheme.self) private var theme
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        let inline = placement == .inline
        HStack(spacing: inline ? 8 : 10) {
            Artwork(url: player.current?.coverUrl, radius: inline ? 6 : 8)
                .frame(width: inline ? 28 : 40, height: inline ? 28 : 40)
            VStack(alignment: .leading, spacing: 0) {
                Text(player.current?.title ?? "Niente in riproduzione").font(.system(size: inline ? 13 : 14, weight: .semibold)).lineLimit(1)
                if !inline, let artist = player.current?.artist {
                    Text(artist).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if !inline, let song = player.current {
                let fav = library.isFavorite(song)
                Button { library.toggleFavorite(song) } label: {
                    Image(systemName: fav ? "heart.fill" : "heart").font(.system(size: 17))
                        .foregroundStyle(fav ? AnyShapeStyle(theme.accent) : AnyShapeStyle(.secondary))
                        .scaleEffect(fav ? 1.2 : 1)
                        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: fav)
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel(fav ? "Togli dai preferiti" : "Aggiungi ai preferiti")
            }
            if player.isLoading {
                ProgressView().frame(width: 32)
            } else {
                Button { player.toggle() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: inline ? 17 : 22))
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                .disabled(player.current == nil)
            }
            if !inline {
                Button { player.next() } label: { Image(systemName: "forward.end.fill").font(.system(size: 18)).frame(width: 32, height: 32) }
                    .accessibilityLabel("Brano successivo")
                    .disabled(player.current == nil)
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, inline ? 10 : 14)
        .overlay(alignment: .top) {
            if !inline {
                TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                    GeometryReader { g in
                        Capsule().fill(theme.accent)
                            .frame(width: g.size.width * (player.duration > 0 ? min(max(player.position / player.duration, 0), 1) : 0), height: 2)
                    }
                    .frame(height: 2)
                    .padding(.horizontal, 18)
                }
            }
        }
        .gesture(
            DragGesture(minimumDistance: 20).onEnded { v in
                guard abs(v.translation.width) > 72, abs(v.translation.width) > abs(v.translation.height) else { return }
                if v.translation.width < 0 { player.next() } else { player.previous() }
            }
        )
    }
}

/// The phone's PlayerScreenV2, rebuilt for iPhone. The cover fills the width and the top 56% of
/// the screen and fades at its bottom into the same cover blurred over a gradient of its own
/// colours. Under it: title, artists, heart, more; at the bottom the line being sung, a thin
/// seek bar, previous/play/next, and a row with Lyrics, a pill (where it plays, Devices) and
/// Queue. Opening the queue shrinks the cover into a 48 pt thumbnail at the top, the title rides
/// up beside it, and the pill slides left with shuffle, repeat and autoplay. Lyrics cover all.
struct NowPlayingView: View {
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var gradient = ArtworkGradient.fallback
    @State private var queueOpen = UserDefaults.standard.bool(forKey: "showQueue")
    @State private var lyricsOpen = UserDefaults.standard.bool(forKey: "showLyrics")
    @State private var menuOpen = false
    @State private var devicesOpen = false
    @State private var dismissDrag: CGFloat = 0
    /// The bottom deck's height: the queue ends above it.
    @State private var deckHeight: CGFloat = 260

    /// How long the cover, backdrop and title take to dissolve into the next song (TRACK_CHANGE_MS).
    static let trackChange = 0.9

    private let ink = Color.white
    private var inkSecondary: Color { Color(uiColor: UIColor(Color.white).mixed(with: UIColor(gradient.top), 0.32)).opacity(0.8) }
    private var accent: Color { .lfly }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom
            let top = geo.safeAreaInsets.top
            let coverH = min(max(h * 0.56, 240), h - 360)
            let p: CGFloat = queueOpen ? 1 : 0
            ZStack(alignment: .top) {
                backdrop(coverFraction: coverH / h)
                HeroCover(width: w, height: coverH, gradient: gradient) {
                    dismiss()
                }
                // Into a 48 pt thumbnail at (56, status bar + 8) while the queue is open.
                .scaleEffect(1 + (48 / w - 1) * p, anchor: .topLeading)
                .offset(x: 56 * p, y: (top + 8) * p)
                scrims(coverFraction: coverH / h)
                    .allowsHitTesting(false)
                chrome(width: w, coverH: coverH, top: top, p: p, bottomInset: geo.safeAreaInsets.bottom)
                    .opacity(lyricsOpen ? 0 : 1)
                    .animation(.easeInOut(duration: reduceMotion ? 0 : 0.22), value: lyricsOpen)
                queueHeader(top: top)
                    .opacity(max(0, (p - 0.48) / 0.52))
                    .allowsHitTesting(queueOpen)
                if queueOpen {
                    QueueList(ink: ink, inkSecondary: inkSecondary, accent: accent)
                        .padding(.top, top + 64)
                        .padding(.bottom, deckHeight)
                        .transition(.opacity.combined(with: .offset(y: 14)))
                }
                // The way back: a chevron at the top left, over the cover.
                if !queueOpen && !lyricsOpen {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel("Chiudi il player")
                    .padding(.leading, 16)
                    .padding(.top, top + 6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .transition(.opacity)
                }
                if lyricsOpen {
                    LyricsScreen(insets: EdgeInsets(top: top, leading: 0, bottom: geo.safeAreaInsets.bottom, trailing: 0)) { lyricsOpen = false }
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                        .zIndex(6)
                }
            }
            .frame(width: w, height: h)
            .ignoresSafeArea()
        }
        .offset(y: dismissDrag)
        .animation(reduceMotion ? nil : .timingCurve(0.4, 0, 0.2, 1, duration: 0.42), value: queueOpen)
        .animation(reduceMotion ? nil : .spring(duration: 0.32), value: lyricsOpen)
        .preferredColorScheme(.dark)
        .task(id: player.current?.coverUrl) {
            let g = await ArtworkCache.shared.gradient(player.current?.coverUrl)
            withAnimation(.easeInOut(duration: reduceMotion ? 0 : Self.trackChange)) { gradient = g }
        }
        .sheet(isPresented: $menuOpen) {
            if let song = player.current { TrackActionsSheet(song: song) }
        }
        .sheet(isPresented: $devicesOpen) { DevicesView() }
    }

    // MARK: Layers

    /// Gradient of the cover's colours, the cover blurred over all of it (crossfading on a change),
    /// as the phone's backdrop.
    private func backdrop(coverFraction: CGFloat) -> some View {
        ZStack {
            LinearGradient(stops: [.init(color: gradient.top, location: 0), .init(color: gradient.bottom, location: coverFraction), .init(color: gradient.bottom, location: 1)],
                           startPoint: .top, endPoint: .bottom)
            Artwork(url: player.current?.coverUrl, radius: 0)
                .blur(radius: 64)
                .id(player.current?.coverUrl)
                .transition(.opacity)
                .animation(.easeInOut(duration: reduceMotion ? 0 : Self.trackChange), value: player.current?.coverUrl)
        }
        .ignoresSafeArea()
    }

    /// One continuous scrim over cover and blur keeps the lower text legible; another keeps the
    /// status bar readable over bright covers.
    private func scrims(coverFraction f: CGFloat) -> some View {
        ZStack(alignment: .top) {
            LinearGradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: .clear, location: max(f - 0.18, 0)),
                .init(color: .black.opacity(0.36), location: f),
                .init(color: .black.opacity(0.64), location: 0.66),
                .init(color: .black.opacity(0.84), location: 0.86),
                .init(color: .black.opacity(0.94), location: 1),
            ], startPoint: .top, endPoint: .bottom)
            LinearGradient(stops: [
                .init(color: .black.opacity(0.38), location: 0),
                .init(color: .black.opacity(0.12), location: 0.55),
                .init(color: .clear, location: 1),
            ], startPoint: .top, endPoint: .bottom)
            .frame(height: 120)
        }
    }

    private func chrome(width w: CGFloat, coverH: CGFloat, top: CGFloat, p: CGFloat, bottomInset: CGFloat) -> some View {
        // One title, two places: scaled about its top-left corner, it rides from under the cover
        // into the compact header beside the thumbnail.
        let compactStart: CGFloat = 56 + 48 + 12
        let scale = min(max((w - compactStart - 12) / (w - 44), 0.70), 0.85)
        let tx = compactStart - 22 * scale
        let ty = (top + 8) - coverH - 18 * scale
        return VStack(spacing: 0) {
            Color.clear.frame(height: coverH).allowsHitTesting(false)
            TrackMetaRow(ink: ink, inkSecondary: inkSecondary, accent: accent, controlsAlpha: max(0, 1 - p * 1.35)) { menuOpen = true }
                .padding(.init(top: 18, leading: 22, bottom: 8, trailing: 22))
                .scaleEffect(1 + (scale - 1) * p, anchor: .topLeading)
                .offset(x: tx * p, y: ty * p)
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                CurrentLyricStrip(ink: ink, inkSecondary: inkSecondary, accent: accent) { lyricsOpen = true }
                Spacer().frame(height: 12)
                SeekSection(ink: ink, inkSecondary: inkSecondary, accent: accent)
                Spacer().frame(height: 14)
                TransportRow(ink: ink, accent: accent)
                Spacer().frame(height: 20)
                ActionRow(queueOpen: $queueOpen, lyricsOpen: $lyricsOpen, devicesOpen: $devicesOpen, ink: ink, inkSecondary: inkSecondary, accent: accent)
                Spacer().frame(height: 24 + bottomInset)
            }
            .padding(.horizontal, 22)
            .padding(.vertical, 8)
            .background(LinearGradient(colors: [.clear, gradient.bottom.opacity(0.2)], startPoint: .top, endPoint: .bottom))
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { deckHeight = $0 }
        }
    }

    /// The header the queue shows: close on the left, the song's actions on the right.
    private func queueHeader(top: CGFloat) -> some View {
        HStack {
            Button { queueOpen = false } label: {
                Image(systemName: "chevron.down").font(.title3.weight(.semibold)).foregroundStyle(.white.opacity(0.9)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Chiudi la coda")
            Spacer()
            Button { menuOpen = true } label: {
                Image(systemName: "ellipsis").font(.title3).foregroundStyle(.white.opacity(0.75)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Opzioni del brano")
        }
        .padding(.leading, 8).padding(.trailing, 12)
        .frame(height: 64)
        .padding(.top, top)
        .frame(maxHeight: .infinity, alignment: .top)
    }
}

/// Buttons that shrink a little under the finger (the phone's pressScale).
struct PressScale: ButtonStyle {
    var scale: CGFloat = 0.88

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(.spring(duration: 0.25, bounce: 0.4), value: configuration.isPressed)
    }
}

/// The cover, full width with its bottom fading out. Swipe it sideways: the next or previous
/// cover shows through as it moves, and past a quarter of the width the song changes. A new song
/// dissolves in over 0.9 s. Pulling it down closes the player.
private struct HeroCover: View {
    @Environment(Player.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let width: CGFloat
    let height: CGFloat
    let gradient: ArtworkGradient
    let onClose: () -> Void
    @State private var offset: CGFloat = 0
    @State private var vertical: CGFloat = 0
    @State private var axis: Axis?

    var body: some View {
        let progress = min(abs(offset) / max(width, 1), 1)
        let peek: Song? = offset > 0 ? player.queue[safe: player.index - 1] : offset < 0 ? player.queue[safe: player.index + 1] : nil
        ZStack {
            if let peek, progress > 0 {
                Artwork(url: peek.coverUrl, radius: 0).opacity(progress)
            }
            ZStack {
                gradient.top
                Artwork(url: player.current?.coverUrl, radius: 0)
                    .id(player.current?.coverUrl)
                    .transition(.opacity)
                    .animation(.easeInOut(duration: reduceMotion ? 0 : NowPlayingView.trackChange), value: player.current?.coverUrl)
            }
            .offset(x: offset)
            .scaleEffect(1 + 0.02 * progress)
        }
        .frame(width: width, height: height)
        .clipped()
        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.76), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
        .offset(y: max(vertical, 0))
        .contentShape(.rect)
        .gesture(
            DragGesture(minimumDistance: 10)
                .onChanged { g in
                    if axis == nil { axis = abs(g.translation.width) > abs(g.translation.height) ? .horizontal : .vertical }
                    if axis == .horizontal {
                        let goingNext = g.translation.width < 0
                        let can = goingNext ? player.index + 1 < player.queue.count || player.autoplay : player.index > 0
                        if can { offset = max(-width, min(width, g.translation.width)) }
                    } else {
                        vertical = g.translation.height
                    }
                }
                .onEnded { g in
                    defer { axis = nil }
                    if axis == .vertical {
                        if g.translation.height > 120 { onClose() }
                        withAnimation(.spring(duration: 0.3)) { vertical = 0 }
                        return
                    }
                    if abs(offset) > width * 0.24 {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        let forward = offset < 0
                        withAnimation(.linear(duration: 0.18)) { offset = forward ? -width : width }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                            if forward { player.next() } else { player.previous() }
                            offset = 0
                        }
                    } else {
                        withAnimation(.easeOut(duration: 0.18)) { offset = 0 }
                    }
                }
        )
        .accessibilityLabel(player.current.map { "Copertina di \($0.title)" } ?? "Copertina")
        .accessibilityHint("Scorri per cambiare brano, trascina in giù per chiudere")
    }
}

/// Title (20 pt semibold), artists (19 pt), the heart with a bouncy spring, and more.
private struct TrackMetaRow: View {
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    let ink: Color
    let inkSecondary: Color
    let accent: Color
    let controlsAlpha: CGFloat
    let onMore: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(player.current?.title ?? "")
                    .font(.system(size: 20, weight: .semibold)).tracking(-0.45)
                    .foregroundStyle(ink).lineLimit(1)
                Text(player.current?.artist ?? "")
                    .font(.system(size: 19)).tracking(-0.38)
                    .foregroundStyle(inkSecondary).lineLimit(1)
            }
            .id(player.current?.id)
            .transition(.opacity)
            .animation(.easeInOut(duration: NowPlayingView.trackChange / 2), value: player.current?.id)
            .frame(maxWidth: .infinity, alignment: .leading)
            if let song = player.current {
                let liked = library.isFavorite(song)
                Button { library.toggleFavorite(song) } label: {
                    Image(systemName: liked ? "heart.fill" : "heart")
                        .font(.system(size: 24))
                        .foregroundStyle(liked ? accent : inkSecondary)
                        .scaleEffect(liked ? 1.10 : 1)
                        .animation(.spring(response: 0.35, dampingFraction: 0.5), value: liked)
                        .frame(width: 40, height: 40)
                }
                .accessibilityLabel(liked ? "Togli da Mi piace" : "Mi piace")
                .opacity(controlsAlpha)
                .disabled(controlsAlpha < 0.2)
            }
            Button(action: onMore) {
                Image(systemName: "ellipsis").font(.system(size: 22, weight: .semibold)).foregroundStyle(inkSecondary).frame(width: 40, height: 40)
            }
            .accessibilityLabel("Altre azioni")
            .opacity(controlsAlpha)
            .disabled(controlsAlpha < 0.2)
        }
        .buttonStyle(.plain)
    }
}

/// The line being sung, 16 pt semibold; the accent sweeps over it as it is sung, with a glow.
/// A new line slides up and in; a tap opens the lyrics.
private struct CurrentLyricStrip: View {
    @Environment(Player.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let ink: Color
    let inkSecondary: Color
    let accent: Color
    let onTap: () -> Void

    var body: some View {
        let lines = player.lyrics?.lines ?? []
        let now = Int64(player.position * 1000)
        let i = lines.lastIndex { $0.timeMs <= now }
        let line = i.map { lines[$0] }
        let timed = line.map { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty } ?? false
        let text: String = timed ? line!.text : (player.lyrics?.plain?.split(separator: "\n").first.map(String.init) ?? (player.lyrics == nil && player.isLoading ? "Carico il testo…" : "Testo non trovato"))
        let sweep: CGFloat = {
            guard timed, let i, let line else { return 0 }
            let end = lines[safe: i + 1]?.timeMs ?? (line.timeMs + 4000)
            return CGFloat(min(max(Double(now - line.timeMs) / Double(max(end - line.timeMs, 1)), 0), 1))
        }()
        let style = Font.system(size: 16, weight: .semibold)
        ZStack(alignment: .leading) {
            ZStack(alignment: .leading) {
                Text(text).font(style).foregroundStyle(timed ? ink.opacity(0.64) : inkSecondary).lineLimit(1)
                if timed {
                    Text(text).font(style).foregroundStyle(accent).lineLimit(1)
                        .shadow(color: accent.opacity(0.55), radius: 7)
                        .mask(alignment: .leading) {
                            GeometryReader { g in Rectangle().frame(width: g.size.width * sweep) }
                        }
                        .animation(.linear(duration: 0.16), value: sweep)
                }
            }
            .id(i ?? -1)
            .transition(.asymmetric(
                insertion: .offset(y: 15).combined(with: .opacity).combined(with: .scale(scale: 0.97, anchor: .leading)),
                removal: .offset(y: -10).combined(with: .opacity).combined(with: .scale(scale: 1.01, anchor: .leading))
            ))
        }
        .animation(reduceMotion ? nil : .timingCurve(0.4, 0, 0.2, 1, duration: 0.3), value: i)
        .frame(maxWidth: .infinity, minHeight: 30, maxHeight: 30, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture(perform: onTap)
        .accessibilityLabel("Testo: \(text)")
        .accessibilityAddTraits(.isButton)
    }
}

/// A 6 pt bar with no thumb: the ink fills what played; drag anywhere on it to seek. Times at
/// its ends and, between them, where the song plays from.
private struct SeekSection: View {
    @Environment(Player.self) private var player
    let ink: Color
    let inkSecondary: Color
    let accent: Color
    @State private var seeking: Double?

    var body: some View {
        let duration = max(player.duration, 1)
        let fraction = min(max((seeking ?? player.position) / duration, 0), 1)
        VStack(spacing: 2) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(ink.opacity(0.18))
                    Capsule().fill(ink).frame(width: g.size.width * fraction)
                }
                .frame(height: seeking == nil ? 6 : 9)
                .frame(maxHeight: .infinity)
                .contentShape(.rect)
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { v in seeking = min(max(v.location.x / g.size.width, 0), 1) * duration }
                        .onEnded { _ in
                            if let s = seeking { player.seek(to: s) }
                            seeking = nil
                        }
                )
                .animation(.spring(duration: 0.25), value: seeking == nil)
            }
            .frame(height: 30)
            HStack {
                Text(timeText(seeking ?? player.position))
                Spacer()
                if let provider = player.provider {
                    Text(provider).font(.system(size: 10)).foregroundStyle(accent.opacity(0.9)).lineLimit(1).padding(.horizontal, 8)
                }
                Spacer()
                Text(timeText(player.duration))
            }
            .font(.system(size: 11).monospacedDigit())
            .foregroundStyle(inkSecondary)
            .padding(.horizontal, 2)
        }
        .accessibilityElement()
        .accessibilityLabel("Posizione")
        .accessibilityValue("\(timeText(player.position)) di \(timeText(player.duration))")
        .accessibilityAdjustableAction { dir in
            player.seek(to: max(0, player.position + (dir == .increment ? 10 : -10)))
        }
    }
}

/// Previous (38), play/pause (46 in a 72 box; a spinner while loading), next: evenly spread.
private struct TransportRow: View {
    @Environment(Player.self) private var player
    let ink: Color
    let accent: Color

    var body: some View {
        HStack {
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 34)).frame(width: 62, height: 62) }
                .accessibilityLabel("Brano precedente")
            Spacer()
            ZStack {
                if player.isLoading {
                    ProgressView().tint(accent).controlSize(.large)
                } else {
                    Button { player.toggle() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 44))
                            .contentTransition(.symbolEffect(.replace))
                            .frame(width: 72, height: 72)
                    }
                    .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                }
            }
            .frame(width: 76, height: 76)
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 34)).frame(width: 62, height: 62) }
                .accessibilityLabel("Brano successivo")
            Spacer()
        }
        .foregroundStyle(ink)
        .buttonStyle(PressScale())
    }
}

/// Lyrics on the left, Queue on the right, a pill between: where the music plays and Devices; with
/// the queue open the pill slides to the left edge and holds shuffle, repeat and autoplay.
private struct ActionRow: View {
    @Environment(Player.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var queueOpen: Bool
    @Binding var lyricsOpen: Bool
    @Binding var devicesOpen: Bool
    let ink: Color
    let inkSecondary: Color
    let accent: Color

    var body: some View {
        ZStack {
            HStack {
                glyph("quote.bubble", "Testo", on: lyricsOpen) { lyricsOpen.toggle() }
                    .opacity(queueOpen ? 0 : 1)
                    .disabled(queueOpen)
                Spacer()
                glyph("list.bullet", "Coda", on: queueOpen) { queueOpen.toggle() }
            }
            Group {
                if queueOpen {
                    pill {
                        segment("shuffle", "Casuale", on: player.shuffle) { player.toggleShuffle() }
                        divider
                        segment(player.repeatMode == .one ? "repeat.1" : "repeat", "Ripeti", on: player.repeatMode != .off) { player.cycleRepeat() }
                        divider
                        segment("infinity", "Autoplay", on: player.autoplay) { player.toggleAutoplay() }
                    }
                } else {
                    pill {
                        RoutePicker(tint: UIColor(inkSecondary), active: UIColor(accent))
                            .frame(width: 56, height: 44)
                            .accessibilityLabel("Dove si ascolta")
                        divider
                        segment("hifispeaker.2", "Dispositivi", on: false) { devicesOpen = true }
                    }
                }
            }
            .transition(.opacity)
            .frame(maxWidth: .infinity, alignment: queueOpen ? .leading : .center)
            .animation(reduceMotion ? nil : .timingCurve(0.4, 0, 0.2, 1, duration: 0.24), value: queueOpen)
        }
        .buttonStyle(.plain)
    }

    private func glyph(_ icon: String, _ label: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(on ? accent : inkSecondary)
                .frame(width: 44, height: 44)
                .background(Circle().fill(ink.opacity(0.14)))
        }
        .accessibilityLabel(label)
    }

    private func pill<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        HStack(spacing: 0) { content() }
            .frame(height: 44)
            .background(Color.white.opacity(0.12))
            .clipShape(Capsule())
    }

    private var divider: some View { Rectangle().fill(ink.opacity(0.18)).frame(width: 1, height: 44) }

    private func segment(_ icon: String, _ label: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 19)).foregroundStyle(on ? accent : inkSecondary)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 56, height: 44)
                .background(on ? Color.white.opacity(0.08) : .clear)
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The system's picker of where the music plays (AirPlay, Bluetooth, the speaker).
private struct RoutePicker: UIViewRepresentable {
    let tint: UIColor
    let active: UIColor

    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = tint
        v.activeTintColor = active
        v.prioritizesVideoDevices = false
        return v
    }

    func updateUIView(_ v: AVRoutePickerView, context: Context) {
        v.tintColor = tint
        v.activeTintColor = active
    }
}

/// The queue under the compact header: the playing song marked; tap to play, swipe to remove,
/// drag to move.
struct QueueList: View {
    @Environment(Player.self) private var player
    var ink: Color = .primary
    var inkSecondary: Color = .secondary
    var accent: Color = .lfly

    var body: some View {
        List {
            ForEach(Array(player.queue.enumerated()), id: \.offset) { i, song in
                Button { player.jump(to: i) } label: {
                    HStack(spacing: 12) {
                        Artwork(url: song.coverUrl, radius: 6).frame(width: 46, height: 46)
                            .opacity(i < player.index ? 0.5 : 1)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title).font(.system(size: 16, weight: .medium)).lineLimit(1)
                                .foregroundStyle(i == player.index ? accent : ink)
                            Text(song.artist).font(.system(size: 14)).foregroundStyle(inkSecondary).lineLimit(1)
                        }
                        Spacer()
                        if i == player.index {
                            Image(systemName: "waveform").foregroundStyle(accent)
                                .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                        }
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            .onDelete { player.removeFromQueue(at: $0) }
            .onMove { player.moveInQueue(from: $0, to: $1) }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.inactive))
    }
}

/// The song's actions (the phone's TrackActionsSheet): like, play next, add to the queue or a
/// playlist, download, share, the artist, speed and the sleep timer.
struct TrackActionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @Environment(\.dismiss) private var dismiss
    let song: Song
    @State private var artist: Item?

    var body: some View {
        NavigationStack {
            List {
                HStack(spacing: 14) {
                    Artwork(url: song.coverUrl, radius: 8).frame(width: 60, height: 60)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.title).font(.headline).lineLimit(2)
                        Text(song.artist).foregroundStyle(.secondary).lineLimit(1)
                        if let album = song.album { Text(album).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    }
                }
                Section {
                    Button { library.toggleFavorite(song) } label: {
                        library.isFavorite(song) ? Label("Togli da Mi piace", systemImage: "heart.slash") : Label("Mi piace", systemImage: "heart")
                    }
                    Button { player.playNext(song); dismiss() } label: { Label("Riproduci dopo", systemImage: "text.line.first.and.arrowtriangle.forward") }
                    Button { player.addToQueue(song); dismiss() } label: { Label("Aggiungi alla coda", systemImage: "text.line.last.and.arrowtriangle.forward") }
                    Menu {
                        Button { library.createPlaylist(song.title, songs: [song]) } label: { Label("Nuova playlist", systemImage: "plus") }
                        ForEach(library.playlists) { p in Button(p.name) { library.add(song, to: p) } }
                    } label: { Label("Aggiungi a una playlist", systemImage: "text.badge.plus") }
                    if library.isDownloaded(song) {
                        Button(role: .destructive) { library.removeDownload(song) } label: { Label("Rimuovi download", systemImage: "trash") }
                    } else if library.downloading[song.id] != nil {
                        Label("Download in corso…", systemImage: "arrow.down.circle")
                    } else {
                        Button { library.download(song, core: model.core) } label: { Label("Scarica", systemImage: "arrow.down.circle") }
                    }
                    if song.id.hasPrefix("yt_"), let url = URL(string: "https://music.youtube.com/watch?v=\(song.id.dropFirst(3))") {
                        ShareLink(item: url, subject: Text(song.title), message: Text("\(song.title) – \(song.artist)")) {
                            Label("Condividi", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button { Task { await openArtist() } } label: { Label("Vai all'artista", systemImage: "person.crop.circle") }
                }
                Section("Riproduzione") {
                    Picker(selection: Binding(get: { player.rate }, set: { player.setRate($0) })) {
                        ForEach([Float(0.75), 1, 1.25, 1.5], id: \.self) { Text(String(format: "%.2g×", $0)).tag($0) }
                    } label: { Label("Velocità", systemImage: "gauge.with.dots.needle.67percent") }
                    Menu {
                        Button("Spento") { player.sleep(minutes: 0) }
                        ForEach([15, 30, 45, 60], id: \.self) { m in Button("\(m) minuti") { player.sleep(minutes: m) } }
                        Button("Alla fine del brano") { player.sleep(minutes: nil) }
                    } label: {
                        Label(player.sleepAt.map { "Timer fino alle \($0.formatted(date: .omitted, time: .shortened))" } ?? (player.sleepAtEndOfSong ? "Timer: fine del brano" : "Timer"), systemImage: "moon.zzz")
                    }
                }
            }
            .navigationDestination(item: $artist) { ArtistView(item: $0).navigationDestination(for: Item.self) { ItemDestination(item: $0) } }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func openArtist() async {
        let name = song.artist.split(separator: ",").first.map(String.init) ?? song.artist
        if let r = try? await model.core.search(query: name), let a = r.artists.first { artist = a }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

extension UIColor {
    /// This colour mixed toward [other] by [t] (Compose's lerp).
    func mixed(with other: UIColor, _ t: CGFloat) -> UIColor {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        other.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t, alpha: a1 + (a2 - a1) * t)
    }
}
