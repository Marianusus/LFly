import NaturalLanguage
import SwiftUI
import Translation

/// The listener's lyrics choices (the phone keeps them in its settings): how lines light up,
/// whether they are translated and into which language.
@MainActor
@Observable
final class LyricsPrefs {
    enum Highlight: String, CaseIterable { case auto, word, line }

    var highlight: Highlight = Highlight(rawValue: UserDefaults.standard.string(forKey: "lyricsHighlight") ?? "") ?? .auto {
        didSet { UserDefaults.standard.set(highlight.rawValue, forKey: "lyricsHighlight") }
    }
    var translate: Bool = UserDefaults.standard.bool(forKey: "lyricsTranslate") {
        didSet { UserDefaults.standard.set(translate, forKey: "lyricsTranslate") }
    }
    var targetLang: String = UserDefaults.standard.string(forKey: "lyricsTargetLang") ?? (Locale.current.language.languageCode?.identifier ?? "it") {
        didSet { UserDefaults.standard.set(targetLang, forKey: "lyricsTargetLang") }
    }

    static func label(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code)?.capitalized ?? code.uppercased()
    }
}

/// The phone's LyricsView: the song's cover blurred under a dark scrim; a header (close, "IN
/// RIPRODUZIONE", artist — title); the synced lines with the active one at 42% of the height,
/// word by word karaoke (the sung word lifts, glows and shines), background vocals and
/// translations under each line; and a footer with a thin seek bar, shuffle, previous, play,
/// next and the options (translation, language, source, highlighting).
struct LyricsScreen: View {
    @Environment(Player.self) private var player
    @Environment(LyricsPrefs.self) private var prefs
    /// The status bar and home indicator, which the full player draws under.
    var insets = EdgeInsets()
    let onClose: () -> Void
    @State private var translations: [Int: String] = [:]
    @State private var translationConfig: TranslationSession.Configuration?
    @State private var sourcesOpen = false
    @State private var languagesOpen = false

    var body: some View {
        ZStack {
            Artwork(url: player.current?.coverUrl, radius: 0).blur(radius: 80).ignoresSafeArea()
            LinearGradient(stops: [.init(color: .black.opacity(0.58), location: 0), .init(color: .black.opacity(0.72), location: 0.45), .init(color: .black.opacity(0.88), location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 0) {
                header
                content.frame(maxHeight: .infinity)
                LyricsFooter(sourcesOpen: $sourcesOpen, languagesOpen: $languagesOpen)
            }
            .padding(.top, insets.top)
            .padding(.bottom, insets.bottom)
        }
        .foregroundStyle(.white)
        .contentShape(.rect)
        .sheet(isPresented: $sourcesOpen) { LyricsSourceSheet() }
        .sheet(isPresented: $languagesOpen) { LanguageSheet() }
        .translationTask(translationConfig) { session in await translate(session) }
        .task(id: translationKey) { prepareTranslation() }
    }

    private var header: some View {
        HStack {
            Button(action: onClose) {
                Image(systemName: "chevron.down").font(.system(size: 22, weight: .semibold)).frame(width: 42, height: 42)
            }
            .accessibilityLabel("Chiudi testi")
            VStack(spacing: 2) {
                Text("IN RIPRODUZIONE").font(.caption2.bold()).tracking(1.4).foregroundStyle(.white.opacity(0.6))
                Text(player.current.map { "\($0.artist) — \($0.title)" } ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 12)
            Color.clear.frame(width: 42, height: 42)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var content: some View {
        if player.lyricsLoading && player.lyrics == nil {
            VStack(spacing: 16) {
                ProgressView().tint(.lfly).controlSize(.large)
                Text("Caricamento testi sincronizzati...").foregroundStyle(.white.opacity(0.7))
            }
        } else if let l = player.lyrics, !l.lines.isEmpty {
            SyncedLyricsList(lines: l.lines, translations: translations, highlight: prefs.highlight)
        } else if let plain = player.lyrics?.plain, !plain.isEmpty {
            ScrollView {
                Text(plain).font(.system(size: 22, weight: .medium)).lineSpacing(12)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20)
            }
        } else {
            VStack(spacing: 8) {
                Image(systemName: "music.note").font(.system(size: 48)).foregroundStyle(.white.opacity(0.4))
                Text("Nessun testo trovato per questo brano").font(.headline).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
                Text("I testi vengono cercati automaticamente tra più fonti").font(.caption).foregroundStyle(.white.opacity(0.45)).multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)
        }
    }

    // MARK: Translation (the phone uses ML Kit on the device; the iPhone its own Translation)

    private var translationKey: String {
        "\(prefs.translate)|\(prefs.targetLang)|\(player.current?.id ?? "")|\(player.lyrics?.source ?? "")|\(player.lyrics?.lines.count ?? 0)"
    }

    private func prepareTranslation() {
        translations = [:]
        guard prefs.translate, let lines = player.lyrics?.lines, !lines.isEmpty else { translationConfig = nil; return }
        let sample = lines.prefix(40).map(\.text).joined(separator: " ")
        let r = NLLanguageRecognizer()
        r.processString(sample)
        guard let detected = r.dominantLanguage?.rawValue, detected != "und", detected != prefs.targetLang else { translationConfig = nil; return }
        translationConfig = .init(source: Locale.Language(identifier: detected), target: Locale.Language(identifier: prefs.targetLang))
    }

    private func translate(_ session: TranslationSession) async {
        guard let lines = player.lyrics?.lines else { return }
        let requests = lines.enumerated().compactMap { i, l in
            l.text.trimmingCharacters(in: .whitespaces).isEmpty ? nil : TranslationSession.Request(sourceText: l.text, clientIdentifier: String(i))
        }
        guard let responses = try? await session.translations(from: requests) else { return }
        var out: [Int: String] = [:]
        for r in responses {
            if let id = r.clientIdentifier, let i = Int(id), r.targetText.caseInsensitiveCompare(lines[i].text) != .orderedSame { out[i] = r.targetText }
        }
        withAnimation(.easeOut(duration: 0.3)) { translations = out }
    }
}

/// The synced lines. The active one sits at 42% of the height with one short nudge per change;
/// dragging pauses that for 3 s after the finger lifts. Past lines at 60%, future at 46%, the
/// active one bright with a glow; the others blur with their distance. A tap seeks to the line.
private struct SyncedLyricsList: View {
    @Environment(Player.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let lines: [LyricLine]
    let translations: [Int: String]
    let highlight: LyricsPrefs.Highlight
    @State private var followPausedUntil = Date.distantPast
    @State private var shownActive = -1

    var body: some View {
        TimelineView(.animation(paused: !player.isPlaying)) { tl in
            let now = Int64(player.livePosition(at: tl.date) * 1000)
            let active = LyricsTiming.activeLine(lines, at: now)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 26) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                            LineView(
                                line: line,
                                next: lines[safe: i + 1]?.timeMs,
                                state: i == active ? .active : i < active ? .past : .future,
                                distance: abs(i - active),
                                position: now,
                                karaoke: highlight == .word || (highlight == .auto && !line.words.isEmpty),
                                translation: translations[i]
                            )
                            .id(i)
                            .onTapGesture {
                                let lastSafe = player.duration > 0 ? max(player.duration - 0.5, 0) : Double(line.timeMs) / 1000
                                player.seek(to: min(max(Double(line.timeMs) / 1000, 0), lastSafe))
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 180)
                }
                .scrollIndicators(.hidden)
                .onScrollPhaseChange { _, phase in
                    if phase == .interacting { followPausedUntil = .distantFuture }
                    else if phase == .idle, followPausedUntil == .distantFuture { followPausedUntil = .now.addingTimeInterval(3) }
                }
                .onChange(of: active, initial: true) { _, i in
                    guard i >= 0, Date.now >= followPausedUntil else { return }
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.22)) {
                        proxy.scrollTo(i, anchor: UnitPoint(x: 0.5, y: 0.42))
                    }
                }
            }
        }
    }
}

private struct LineView: View {
    enum State { case past, active, future }
    let line: LyricLine
    let next: Int64?
    let state: State
    let distance: Int
    let position: Int64
    let karaoke: Bool
    let translation: String?

    var body: some View {
        let color: Color = state == .active ? .white : state == .past ? .white.opacity(0.62) : .white.opacity(0.44)
        let alpha: Double = state == .active ? 1 : state == .past ? 0.6 : 0.46
        let blur: CGFloat = state == .active ? 0 : min(1.2 + CGFloat(distance) * 0.65, 5)
        VStack(alignment: .leading, spacing: 3) {
            if state == .active && karaoke {
                KaraokeLine(words: LyricsTiming.words(for: line, nextStart: next), text: line.text, position: position)
            } else {
                Text(line.text)
                    .font(.system(size: 30, weight: state == .active ? .heavy : .bold))
                    .foregroundStyle(color)
                    .shadow(color: state == .active ? .white.opacity(0.5) : .clear, radius: 11)
            }
            if let bg = line.background, !bg.isEmpty {
                Text(bg).font(.system(size: 16, weight: .medium)).italic().foregroundStyle(color.opacity(0.58))
            }
            if let t = translation {
                Text(t).font(.system(size: 15)).foregroundStyle(color.opacity(0.65)).transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .opacity(alpha)
        .blur(radius: blur)
        .animation(.easeInOut(duration: 0.22), value: state)
        .contentShape(.rect)
    }
}

/// The active line word by word: sung words full white, the one being sung lifts by 2 pt, grows
/// 13% with a bouncy spring, glows and carries a shine sweeping across it; the rest wait dimmed.
private struct KaraokeLine: View {
    let words: [LyricsTiming.Word]
    let text: String
    let position: Int64
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let active = LyricsTiming.activeWord(words, at: position)
        if words.isEmpty {
            Text(text).font(.system(size: 30, weight: .heavy))
        } else {
            WordFlow(spacing: 4, lineSpacing: 2) {
                ForEach(Array(words.enumerated()), id: \.offset) { i, w in
                    KaraokeWord(text: w.text, state: i < active ? .sung : i == active ? .active : .future, reduceMotion: reduceMotion)
                }
            }
        }
    }
}

private struct KaraokeWord: View {
    enum State { case sung, active, future }
    let text: String
    let state: State
    let reduceMotion: Bool
    @SwiftUI.State private var shine = false

    var body: some View {
        let pop: CGFloat = state == .active ? 1 : 0
        Text(text)
            .font(.system(size: 30, weight: .heavy))
            .foregroundStyle(state == .future ? Color.white.opacity(0.42 * 0.62) : .white)
            .overlay {
                if state == .active {
                    // The sheen: a lighter band sweeping across the word every 1.1 s.
                    LinearGradient(colors: [.clear, .white.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: 60)
                        .offset(x: shine ? 120 : -120)
                        .mask(Text(text).font(.system(size: 30, weight: .heavy)))
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { shine = true }
                        }
                }
            }
            .shadow(color: .white.opacity(0.7 * pop), radius: 13 * pop)
            .scaleEffect(1 + pop * 0.13)
            .offset(y: -2 * pop)
            .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.46), value: state)
            .animation(.easeInOut(duration: 0.24), value: state == .sung)
    }
}

/// Words laid out like text: left to right, wrapping to the next row (Compose's FlowRow).
private struct WordFlow: Layout {
    var spacing: CGFloat
    var lineSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { y += row + lineSpacing; x = 0; row = 0 }
            x += size.width + spacing
            row = max(row, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, width), height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { y += row + lineSpacing; x = bounds.minX; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}

/// A thin seek bar (tap or drag), elapsed and remaining time, then shuffle, previous, play,
/// next and the options menu.
private struct LyricsFooter: View {
    @Environment(Player.self) private var player
    @Environment(LyricsPrefs.self) private var prefs
    @Binding var sourcesOpen: Bool
    @Binding var languagesOpen: Bool
    @State private var dragFraction: Double?

    var body: some View {
        let duration = max(player.duration, 1)
        let fraction = dragFraction ?? min(max(player.position / duration, 0), 1)
        let shown = dragFraction.map { $0 * duration } ?? player.position
        VStack(spacing: 4) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.2))
                    Capsule().fill(.white).frame(width: g.size.width * fraction)
                }
                .frame(height: 6)
                .frame(maxHeight: .infinity)
                .contentShape(.rect)
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { dragFraction = min(max($0.location.x / g.size.width, 0), 1) }
                    .onEnded { _ in
                        if let f = dragFraction { player.seek(to: f * duration) }
                        dragFraction = nil
                    })
            }
            .frame(height: 24)
            HStack {
                Text(timeText(shown))
                Spacer()
                Text("-" + timeText(max(duration - shown, 0)))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(.white.opacity(0.65))
            Spacer().frame(height: 12)
            HStack {
                Button { player.toggleShuffle() } label: {
                    Image(systemName: "shuffle").font(.system(size: 22)).foregroundStyle(.white.opacity(player.shuffle ? 1 : 0.55)).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Casuale")
                Spacer()
                Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 30)).frame(width: 48, height: 48) }
                    .accessibilityLabel("Brano precedente")
                Spacer()
                Button { player.toggle() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 40)).contentTransition(.symbolEffect(.replace)).frame(width: 58, height: 58)
                }
                .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                Spacer()
                Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 30)).frame(width: 48, height: 48) }
                    .accessibilityLabel("Brano successivo")
                Spacer()
                options
            }
            .buttonStyle(PressScale())
            Spacer().frame(height: 18)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
    }

    private var options: some View {
        @Bindable var prefs = prefs
        return Menu {
            Toggle(isOn: $prefs.translate) { Label(prefs.translate ? "Traduzione: ON" : "Traduzione: OFF", systemImage: "translate") }
            Button { languagesOpen = true } label: { Label("Lingua: \(LyricsPrefs.label(prefs.targetLang))", systemImage: "globe") }
            Divider()
            Button { sourcesOpen = true } label: {
                Label("Fonte: \(player.lyricsSource.flatMap { id in lyricsSources().first { $0.id == id }?.label } ?? "Automatica")", systemImage: "star")
            }
            Divider()
            Picker(selection: $prefs.highlight) {
                Text("Evidenzia: Auto").tag(LyricsPrefs.Highlight.auto)
                Text("Evidenzia: Parole").tag(LyricsPrefs.Highlight.word)
                Text("Evidenzia: Righe").tag(LyricsPrefs.Highlight.line)
            } label: { Label("Evidenzia", systemImage: "highlighter") }
            .pickerStyle(.inline)
        } label: {
            Image(systemName: "ellipsis").font(.system(size: 22, weight: .semibold)).frame(width: 44, height: 44)
        }
        .accessibilityLabel("Opzioni testi")
    }
}

/// Where lyrics come from: automatic (every source races, the best wins) or one of them.
private struct LyricsSourceSheet: View {
    @Environment(Player.self) private var player
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                row(nil, "Automatica", "Tutte le fonti, vince la migliore", words: true)
                Section("Fonti") {
                    ForEach(lyricsSources(), id: \.id) { s in row(s.id, s.label, s.detail, words: s.words) }
                }
                if let used = player.lyrics?.source {
                    Section { Text("Questo testo viene da \(used)").foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Fonte dei testi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ id: String?, _ label: String, _ detail: String, words: Bool) -> some View {
        Button {
            player.setLyricsSource(id)
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(label)
                        if words { Text("PAROLE").font(.caption2.bold()).padding(.horizontal, 5).padding(.vertical, 1).background(Capsule().fill(Color.lfly.opacity(0.2))).foregroundStyle(Color.lfly) }
                    }
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if player.lyricsSource == id { Image(systemName: "checkmark").foregroundStyle(Color.lfly) }
            }
        }
        .buttonStyle(.plain)
    }
}

/// The languages the iPhone translates into.
private struct LanguageSheet: View {
    nonisolated static func supported() async -> Set<String> {
        let all = await LanguageAvailability().supportedLanguages
        return Set(all.compactMap { $0.languageCode?.identifier })
    }

    @Environment(LyricsPrefs.self) private var prefs
    @Environment(\.dismiss) private var dismiss
    @State private var languages: [String] = []

    var body: some View {
        NavigationStack {
            List(languages, id: \.self) { code in
                Button {
                    prefs.targetLang = code
                    dismiss()
                } label: {
                    HStack {
                        Text(LyricsPrefs.label(code))
                        Spacer()
                        if code == prefs.targetLang { Image(systemName: "checkmark").foregroundStyle(Color.lfly) }
                    }
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Lingua")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
            .task {
                let codes = await Self.supported()
                languages = codes.sorted { LyricsPrefs.label($0) < LyricsPrefs.label($1) }
            }
        }
    }
}
