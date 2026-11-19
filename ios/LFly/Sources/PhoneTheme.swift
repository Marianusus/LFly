import SwiftUI
import UIKit

// The phone's look on iPhone: its "Dinamico scuro" canvas (BackgroundScheme.kt + SonoraBackground.kt),
// the two accents taken from the playing cover (CoverPalette.kt), and the floating glass chrome
// (BottomChrome.kt, SonoraBottomNav.kt, MiniPlayer.kt) with the same sizes.

/// Two accents from a cover: the vivid warm and the vivid cool pixels' averages (CoverPalette.accentFrom).
struct CoverAccent: Equatable {
    var primary: RGB
    var secondary: RGB

    static let fallback = CoverAccent(primary: RGB(0x1D, 0xB9, 0x54), secondary: RGB(0x4D, 0x7C, 0xFF))

    static func from(_ image: UIImage) -> CoverAccent {
        let side = 32
        guard let cg = image.cgImage,
              let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return .fallback }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        guard let p = ctx.data?.assumingMemoryBound(to: UInt8.self) else { return .fallback }
        var warm = (0, 0, 0, 0), cool = (0, 0, 0, 0), all = (0, 0, 0, 0)
        for i in 0..<(side * side) {
            let o = i * 4
            if p[o + 3] < 32 { continue }
            let r = Int(p[o]), g = Int(p[o + 1]), b = Int(p[o + 2])
            let hi = max(r, g, b), lo = min(r, g, b)
            let sat = hi <= 0 ? 0 : Double(hi - lo) / Double(hi)
            let luma = (0.299 * Double(r) + 0.587 * Double(g) + 0.114 * Double(b)) / 255
            all = (all.0 + r, all.1 + g, all.2 + b, all.3 + 1)
            if sat >= 0.22, (0.10...0.94).contains(luma) {
                if r >= b { warm = (warm.0 + r, warm.1 + g, warm.2 + b, warm.3 + 1) } else { cool = (cool.0 + r, cool.1 + g, cool.2 + b, cool.3 + 1) }
            }
        }
        guard all.3 > 0 else { return .fallback }
        func avg(_ t: (Int, Int, Int, Int)) -> RGB { RGB(t.0 / t.3, t.1 / t.3, t.2 / t.3) }
        let primary = warm.3 > 0 ? avg(warm) : cool.3 > 0 ? avg(cool) : avg(all)
        let secondary = cool.3 > 0 ? avg(cool) : warm.3 > 0 ? avg(warm) : primary.mix(.white, 0.35)
        return CoverAccent(primary: primary, secondary: secondary)
    }
}

/// A colour as the phone's colour math wants it (0…1 channels).
struct RGB: Equatable {
    var r: Double, g: Double, b: Double

    init(_ r: Int, _ g: Int, _ b: Int) { self.r = Double(r) / 255; self.g = Double(g) / 255; self.b = Double(b) / 255 }
    init(r: Double, g: Double, b: Double) { self.r = r; self.g = g; self.b = b }

    static let black = RGB(r: 0, g: 0, b: 0), white = RGB(r: 1, g: 1, b: 1)

    var luma: Double { min(max(0.299 * r + 0.587 * g + 0.114 * b, 0), 1) }
    var color: Color { Color(red: r, green: g, blue: b) }

    func mix(_ o: RGB, _ t: Double) -> RGB {
        let f = min(max(t, 0), 1)
        return RGB(r: r + (o.r - r) * f, g: g + (o.g - g) * f, b: b + (o.b - b) * f)
    }

    /// Toward black until luma ≤ max (clampLumaDark).
    func darkened(to maxLuma: Double) -> RGB { luma <= maxLuma || luma <= 0.0001 ? self : RGB.black.mix(self, maxLuma / luma) }
    /// Toward white until luma ≥ min (clampLumaLight).
    func lightened(to minLuma: Double) -> RGB { luma >= minLuma ? self : mix(.white, (minLuma - luma) / (1 - luma)) }
}

/// The resolved canvas, as LocalBackgroundScheme: gradient stops, accent and glass colours.
@MainActor
@Observable
final class PhoneTheme {
    /// The phone's dark "Aspetto" modes.
    enum Mode: String, CaseIterable {
        case dynamicDark, dynamicCover, amoled

        var title: String {
            switch self {
            case .dynamicDark: "Dinamico scuro"
            case .dynamicCover: "Copertina"
            case .amoled: "AMOLED nero"
            }
        }
    }

    private(set) var accentPair = CoverAccent.fallback
    /// The playing song's cover, for the cover mode.
    private(set) var cover: String?
    private var cache: [String: CoverAccent] = [:]
    var mode: Mode = Mode(rawValue: UserDefaults.standard.string(forKey: "appearance") ?? "") ?? .dynamicDark {
        didSet { UserDefaults.standard.set(mode.rawValue, forKey: "appearance") }
    }

    var accent: Color { accentPair.primary.lightened(to: 0.55).color }
    var gradient: [Color] {
        if mode == .amoled { return [.black, .black, .black] }
        return [RGB.black.mix(accentPair.primary, 0.55).darkened(to: 0.29).color,
         RGB.black.mix(accentPair.secondary, 0.34).darkened(to: 0.19).color,
         RGB(0x05, 0x05, 0x09).color]
    }
    /// chromeSurface(DYNAMIC_DARK): near-opaque dark with a tenth of the accent.
    var chromeTint: Color {
        if mode == .amoled { return RGB(0x0B, 0x0B, 0x12).color.opacity(0xF0 / 255) }
        return RGB(0x10, 0x10, 0x16).mix(accentPair.primary.lightened(to: 0.55), mode == .dynamicCover ? 0.16 : 0.10).color.opacity(mode == .dynamicCover ? 0.90 : 0.94)
    }
    static let chromeBorder = Color.white.opacity(0x30 / 255)
    static let glassTint = Color.white.opacity(0x14 / 255)
    static let glassBorder = Color.white.opacity(0x26 / 255)
    static let textSecondary = Color.white.opacity(0.70)
    static let textTertiary = Color.white.opacity(0.45)

    /// Follows the playing cover (the phone's dynamic theme).
    func follow(cover url: String?) async {
        cover = url
        guard let url, let u = URL(string: url) else { return }
        if let a = cache[url] { set(a); return }
        guard let image = await ArtworkCache.shared.image(url, u) else { return }
        let a = CoverAccent.from(image)
        cache[url] = a
        set(a)
    }

    private func set(_ a: CoverAccent) {
        withAnimation(.easeInOut(duration: 0.38)) { accentPair = a }
    }
}

/// The canvas behind every tab: the vertical gradient and the accent's radial glow.
struct PhoneBackground: View {
    @Environment(PhoneTheme.self) private var theme

    var body: some View {
        ZStack {
            LinearGradient(colors: theme.gradient, startPoint: .top, endPoint: .bottom)
            switch theme.mode {
            case .dynamicDark:
                RadialGradient(colors: [theme.accent.opacity(0.32), theme.accent.opacity(0.12), .clear],
                               center: .center, startRadius: 0, endRadius: 520)
            case .dynamicCover:
                // The playing cover itself, blurred behind everything, under a dark scrim.
                GeometryReader { g in
                    Artwork(url: theme.cover, radius: 0)
                        .frame(width: g.size.width, height: g.size.height)
                        .blur(radius: 60)
                        .scaleEffect(1.3)
                        .overlay(Color.black.opacity(0.55))
                        .animation(.easeInOut(duration: 0.6), value: theme.cover)
                }
            case .amoled:
                EmptyView()
            }
        }
        .ignoresSafeArea()
    }
}

/// Spacing and sizes (Dimensions.kt, BottomChrome.kt, HomeTokens.kt).
enum Phone {
    static let barHeight: CGFloat = 64
    static let edge: CGFloat = 8
    static let stackGap: CGFloat = 8
    static let gutter: CGFloat = 20
    static let sectionGap: CGFloat = 32
    static let cardRadius: CGFloat = 20

    /// What a scrolling screen keeps clear at the bottom for the floating bars.
    static func bottomInset(miniPlayer: Bool) -> CGFloat {
        edge + barHeight + (miniPlayer ? stackGap + barHeight + edge : 0) + 8
    }
}

extension View {
    /// The phone's liquid glass card: a faint white tint and hairline border.
    func phoneGlass(radius: CGFloat = Phone.cardRadius) -> some View {
        background(PhoneTheme.glassTint, in: .rect(cornerRadius: radius))
            .overlay(RoundedRectangle(cornerRadius: radius).strokeBorder(PhoneTheme.glassBorder, lineWidth: 1))
    }

    /// A tab's root page on the canvas: no system bar background, lists see through.
    func phonePage() -> some View {
        scrollContentBackground(.hidden)
            .toolbarBackground(.hidden, for: .navigationBar)
            .containerBackground(.clear, for: .navigation)
    }
}

/// A section title with its subtitle and an optional trailing action (HomeSectionHeader).
struct PhoneSectionHeader: View {
    @Environment(PhoneTheme.self) private var theme
    let title: String
    var subtitle: String?
    var action: (String, () -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 22, weight: .bold)).tracking(-0.3).foregroundStyle(.white)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle {
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(2)
                }
            }
            Spacer()
            if let action {
                Button(action.0, action: action.1).font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.accent)
            }
        }
        .padding(.horizontal, Phone.gutter)
    }
}

/// A tab's big title (the phone's screen headers: 34 pt bold).
struct PhoneTitle: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.system(size: 34, weight: .bold)).tracking(-0.7).foregroundStyle(.white).lineLimit(2)
            if let subtitle {
                Text(subtitle).font(.system(size: 13.5, weight: .medium)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Phone.gutter)
        .padding(.top, 12)
    }
}

// MARK: Bottom chrome

enum PhoneTab: String, CaseIterable {
    case home, search, library, settings

    var title: String {
        switch self {
        case .home: "Home"
        case .search: "Cerca"
        case .library: "Libreria"
        case .settings: "Impostazioni"
        }
    }

    var icon: String {
        switch self {
        case .home: "house.fill"
        case .search: "magnifyingglass"
        case .library: "music.note.square.stack.fill"
        case .settings: "gearshape.fill"
        }
    }
}

/// The floating tab bar: one accent pill that slides (and can be dragged) across four tabs.
struct PhoneTabBar: View {
    @Environment(PhoneTheme.self) private var theme
    @Binding var tab: PhoneTab
    @GestureState private var drag: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let tabs = PhoneTab.allCases
            let gap: CGFloat = 6
            let width = (geo.size.width - gap * CGFloat(tabs.count - 1)) / CGFloat(tabs.count)
            let step = width + gap
            let selected = CGFloat(tabs.firstIndex(of: tab) ?? 0)
            let position = min(max(selected + drag / step, 0), CGFloat(tabs.count - 1))
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(theme.accent.opacity(0.17))
                    .overlay(Capsule().strokeBorder(theme.accent.opacity(0.26), lineWidth: 1))
                    .frame(width: width)
                    .offset(x: position * step)
                    .animation(.spring(response: 0.38, dampingFraction: 0.72), value: position)
                HStack(spacing: gap) {
                    ForEach(tabs, id: \.self) { t in
                        let on = t == tab
                        Button {
                            if !on { UISelectionFeedbackGenerator().selectionChanged() }
                            tab = t
                        } label: {
                            VStack(spacing: 2) {
                                Image(systemName: t.icon).font(.system(size: 20, weight: .semibold))
                                    .scaleEffect(on ? 1.08 : 1)
                                    .frame(height: 25)
                                Text(t.title).font(.system(size: 11, weight: on ? .bold : .medium)).lineLimit(1).minimumScaleFactor(0.8)
                            }
                            .foregroundStyle(on ? theme.accent : PhoneTheme.textSecondary)
                            .frame(width: width)
                            .frame(maxHeight: .infinity)
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }
            .gesture(
                DragGesture(minimumDistance: 10)
                    .updating($drag) { v, s, _ in s = v.translation.width }
                    .onEnded { v in
                        let target = Int((selected + v.translation.width / step).rounded())
                        tab = tabs[min(max(target, 0), tabs.count - 1)]
                    }
            )
        }
        .padding(6)
        .frame(height: Phone.barHeight)
        .background(theme.chromeTint, in: .capsule)
        .overlay(Capsule().strokeBorder(PhoneTheme.chromeBorder, lineWidth: 1))
        .animation(.easeOut(duration: 0.2), value: tab)
    }
}

/// The floating mini player pill: progress hairline on top, cover, title, heart, play/pause,
/// next; a sideways swipe skips, a pull up or a tap opens the full player.
struct PhoneMiniPlayer: View {
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @Environment(PhoneTheme.self) private var theme
    @Environment(ConnectModel.self) private var connect
    var open: () -> Void

    var body: some View {
        if let song = player.current {
            let fav = library.isFavorite(song)
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                let progress = player.duration > 0 ? min(max(player.position / player.duration, 0), 1) : 0
                VStack(spacing: 0) {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color.white.opacity(0.14))
                            Rectangle().fill(theme.accent).frame(width: g.size.width * progress)
                        }
                    }
                    .frame(height: 2)
                    HStack(spacing: 10) {
                        Artwork(url: song.coverUrl, radius: 8)
                            .frame(width: 42, height: 42)
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(PhoneTheme.chromeBorder.opacity(0.45), lineWidth: 1))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(song.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                            if let f = connect.following {
                                Label(f.name, systemImage: "hifispeaker.fill").font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)).lineLimit(1)
                            } else {
                                Text(song.artist).font(.system(size: 12)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Button { library.toggleFavorite(song) } label: {
                            Image(systemName: fav ? "heart.fill" : "heart").font(.system(size: 18))
                                .foregroundStyle(fav ? theme.accent : PhoneTheme.textSecondary)
                                .scaleEffect(fav ? 1.2 : 1)
                                .animation(.spring(response: 0.3, dampingFraction: 0.5), value: fav)
                                .frame(width: 36, height: 36)
                        }
                        .accessibilityLabel(fav ? "Togli dai preferiti" : "Aggiungi ai preferiti")
                        Group {
                            if player.isLoading {
                                ProgressView().tint(theme.accent)
                            } else {
                                Button { player.toggle() } label: {
                                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 26))
                                        .contentTransition(.symbolEffect(.replace))
                                        .frame(width: 40, height: 40)
                                }
                                .accessibilityLabel(player.isPlaying ? "Pausa" : "Riproduci")
                            }
                        }
                        .frame(width: 40, height: 40)
                        Button { player.next() } label: {
                            Image(systemName: "forward.end.fill").font(.system(size: 20)).frame(width: 40, height: 40)
                        }
                        .accessibilityLabel("Brano successivo")
                    }
                    .foregroundStyle(.white)
                    .buttonStyle(.plain)
                    .padding(.horizontal, 12)
                    .frame(maxHeight: .infinity)
                }
            }
            .frame(height: Phone.barHeight)
            .background(theme.chromeTint)
            .clipShape(.capsule)
            .overlay(Capsule().strokeBorder(PhoneTheme.chromeBorder, lineWidth: 0.5))
            .contentShape(.capsule)
            .onTapGesture(perform: open)
            .gesture(
                DragGesture(minimumDistance: 16).onEnded { v in
                    let dx = v.translation.width, dy = v.translation.height
                    if dy < -40, abs(dx) < -dy * 1.5 { open() }
                    else if abs(dx) > 72, abs(dx) > abs(dy) {
                        UISelectionFeedbackGenerator().selectionChanged()
                        if dx < 0 { player.next() } else { player.previous() }
                    }
                }
            )
        }
    }
}
