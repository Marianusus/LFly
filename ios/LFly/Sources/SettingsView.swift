import SwiftUI

/// The phone's Settings (SettingsScreen.kt): a root list as iOS Settings has it, the account
/// first, then topics, each opening its page. The groups are SettingsGroupedList.kt's: small grey
/// captions over 16 pt glass surfaces, rows with a coloured 30 pt tile, hairlines from the text,
/// footnotes. Pages the iPhone has nothing behind yet (equalizer, crossfade and automix, language
/// and country, watch, backup) are left out rather than shown dead.
struct SettingsView: View {
    @Environment(Account.self) private var account
    /// Test runs: `-settingsPage audio` opens that page (the simulator takes no taps).
    @State private var testPage = UserDefaults.standard.string(forKey: "settingsPage")

    var body: some View {
        SettingsPage(title: "Impostazioni", root: true) {
            SettingsGroup {
                NavigationLink { AccountSettings() } label: {
                    SettingsRow(title: account.profile?.name ?? "Account YouTube Music",
                                subtitle: account.profile == nil ? "Libreria, Mi piace e playlist" : "Account YouTube Music",
                                chevron: true) {
                        ZStack {
                            Circle().fill(SettingsTile.red)
                            if let avatar = account.profile?.avatarUrl {
                                Artwork(url: avatar, radius: 15)
                            } else {
                                Image(systemName: "person.crop.circle.fill").font(.system(size: 17)).foregroundStyle(.white)
                            }
                        }
                        .frame(width: 30, height: 30)
                        .clipShape(.circle)
                    }
                }
                .buttonStyle(RowPress())
            }
            SettingsGroup {
                link("Riproduzione", "play.fill", SettingsTile.red) { PlaybackSettings() }
                SettingsDivider()
                link("Audio", "waveform", SettingsTile.orange) { AudioSettings() }
                SettingsDivider()
                link("Download", "arrow.down.circle.fill", SettingsTile.green) { DownloadSettings() }
                SettingsDivider()
                link("Testi", "quote.bubble.fill", SettingsTile.purple) { LyricsSettings() }
            }
            SettingsGroup {
                link("Aspetto", "paintpalette.fill", SettingsTile.blue) { AppearanceSettings() }
            }
            SettingsGroup {
                link("Dati e spazio", "internaldrive.fill", SettingsTile.teal) { DataSettings() }
                SettingsDivider()
                link("Avanzate", "slider.horizontal.3", SettingsTile.graphite) { AdvancedSettings() }
                SettingsDivider()
                link("Info su LFly", "info.circle.fill", SettingsTile.graphite) { AboutSettings() }
            }
        }
        .navigationDestination(item: $testPage) { page in
            switch page {
            case "account": AccountSettings()
            case "playback": PlaybackSettings()
            case "audio": AudioSettings()
            case "downloads": DownloadSettings()
            case "lyrics": LyricsSettings()
            case "appearance": AppearanceSettings()
            case "data": DataSettings()
            case "advanced": AdvancedSettings()
            default: AboutSettings()
            }
        }
    }

    private func link(_ title: String, _ icon: String, _ tile: Color, @ViewBuilder _ page: @escaping () -> some View) -> some View {
        NavigationLink { page() } label: { SettingsRow(title: title, icon: icon, tile: tile, chevron: true) }
            .buttonStyle(RowPress())
    }
}

// MARK: Pages

/// Account YouTube Music and Spotify.
private struct AccountSettings: View {
    @Environment(Account.self) private var account
    @Environment(SpotifyModel.self) private var spotify
    @State private var signingIn = false
    @State private var spotifySignIn = false

    var body: some View {
        SettingsPage(title: "Account YouTube Music") {
            if let p = account.profile {
                SettingsGroup(footer: "La Home e le playlist sono quelle del tuo account, e i brani che ti piacciono su YouTube Music sono anche qui.") {
                    SettingsRow(title: p.name, subtitle: p.email) {
                        Artwork(url: p.avatarUrl, radius: 15).frame(width: 30, height: 30)
                    }
                    SettingsDivider()
                    SettingsRow(title: "Esci", icon: "rectangle.portrait.and.arrow.right", tile: SettingsTile.graphite,
                                titleColor: SettingsTile.destructive, action: { account.signOut() })
                }
            } else {
                SettingsGroup(footer: "Senza account, la Home e le playlist sono quelle pubbliche. Si apre la vera pagina di accesso di Google: la password non passa da LFly.") {
                    SettingsRow(title: "Accedi a YouTube Music", icon: "person.badge.key.fill", tile: SettingsTile.red,
                                chevron: true, action: account.busy ? nil : { signingIn = true })
                }
            }
            SettingsGroup(header: "Spotify", footer: "Porta in LFly le tue playlist e i brani che ti piacciono su Spotify. LFly tiene solo la sessione, su questo iPhone; ogni brano viene cercato su YouTube Music quando lo ascolti.") {
                if spotify.connected {
                    SettingsRow(title: "Importa la libreria ora",
                                subtitle: spotify.last.map { "Importate \($0.playlists) playlist e \($0.liked) brani (\($0.newFavorites) nuovi)" },
                                icon: "square.and.arrow.down.fill", tile: SettingsTile.green,
                                action: spotify.busy ? nil : { Task { await spotify.importNow() } }) {
                        if spotify.busy { ProgressView() }
                    }
                    SettingsDivider()
                    SettingsRow(title: "Scollega Spotify", icon: "link", tile: SettingsTile.graphite,
                                titleColor: SettingsTile.destructive, action: { spotify.disconnect() })
                } else {
                    SettingsRow(title: "Collega Spotify", icon: "link", tile: SettingsTile.green, chevron: true, action: { spotifySignIn = true })
                }
            }
        }
        .sheet(isPresented: $signingIn) { SignInView() }
        .sheet(isPresented: $spotifySignIn) { SpotifySignInView() }
        .alert("Spotify", isPresented: Binding(get: { spotify.failed != nil }, set: { if !$0 { spotify.failed = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(spotify.failed ?? "")
        }
    }
}

private struct PlaybackSettings: View {
    @Environment(Player.self) private var player

    var body: some View {
        SettingsPage(title: "Riproduzione") {
            SettingsGroup(header: "Riproduzione e coda") {
                SettingsToggle(title: "Riproduzione automatica / Radio infinita",
                               subtitle: "Continua la riproduzione con brani simili al termine della coda",
                               icon: "infinity", tile: SettingsTile.red,
                               isOn: Binding(get: { player.autoplay }, set: { if $0 != player.autoplay { player.toggleAutoplay() } }))
                SettingsDivider()
                SettingsRow(title: "Velocità", icon: "gauge.with.dots.needle.67percent", tile: SettingsTile.orange) {
                    AccentMenu(selection: Binding(get: { player.rate }, set: { player.setRate($0) }),
                               value: player.rate == 1 ? "Normale" : "\(player.rate.formatted())×") {
                        ForEach([Float(0.75), 1, 1.25, 1.5], id: \.self) { r in Text(r == 1 ? "Normale" : "\(r.formatted())×").tag(r) }
                    }
                }
            }
        }
    }
}

private struct AudioSettings: View {
    @Environment(Player.self) private var player

    var body: some View {
        SettingsPage(title: "Audio") {
            SettingsGroup(header: "Sorgente streaming", footer: "Quale API preferire per risolvere i brani. Su iPhone YouTube Music è la più veloce: Auto aspetta Monochrome fino a 1,5 s per ogni brano.") {
                choice("YouTube Music", "YouTube Music, poi Monochrome", "YOUTUBE_MUSIC", "play.rectangle.fill", SettingsTile.red)
                SettingsDivider()
                choice("Auto", "Entrambe insieme, Monochrome se risponde presto", "AUTO", "sparkles", SettingsTile.blue)
                SettingsDivider()
                choice("Monochrome", "Monochrome (AAC fino a 320 kbit/s), poi YouTube Music", "MONOCHROME", "waveform.circle.fill", SettingsTile.indigo)
            }
            if let p = player.provider {
                SettingsGroup(header: "In riproduzione") {
                    SettingsRow(title: "Sorgente del brano", icon: "dot.radiowaves.left.and.right", tile: SettingsTile.teal) {
                        Text(p.components(separatedBy: " (").first ?? p).font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                    }
                }
            }
        }
    }

    private func choice(_ title: String, _ subtitle: String, _ id: String, _ icon: String, _ tile: Color) -> some View {
        SettingsChoice(title: title, subtitle: subtitle, selected: player.playbackSource == id,
                       action: { player.playbackSource = id }) { SettingsIconTile(icon: icon, color: tile) }
    }
}

private struct DownloadSettings: View {
    @Environment(Library.self) private var library
    @AppStorage("downloadWifiOnly") private var wifiOnly = false
    @State private var confirm = false

    var body: some View {
        SettingsPage(title: "Download") {
            SettingsGroup(header: "Download") {
                SettingsToggle(title: "Scarica solo in Wi-Fi", subtitle: "Evita il consumo di dati mobili",
                               icon: "wifi", tile: SettingsTile.blue, isOn: $wifiOnly)
                SettingsDivider()
                SettingsRow(title: library.downloads.isEmpty ? "Nessun brano scaricato"
                                : "Spazio occupato: \(ByteCountFormatter.string(fromByteCount: library.downloadsBytes, countStyle: .file))",
                            subtitle: library.downloads.isEmpty ? nil : "\(library.downloads.count) brani",
                            icon: "internaldrive.fill", tile: SettingsTile.green)
                if !library.downloads.isEmpty {
                    SettingsDivider()
                    SettingsRow(title: "Elimina tutti i download", icon: "trash.fill", tile: SettingsTile.graphite,
                                titleColor: SettingsTile.destructive, action: { confirm = true })
                }
            }
        }
        .confirmationDialog("Eliminare tutti i brani scaricati?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Elimina tutti i download", role: .destructive) { library.removeAllDownloads() }
        }
    }
}

private struct LyricsSettings: View {
    @Environment(Player.self) private var player
    @Environment(LyricsPrefs.self) private var prefs

    var body: some View {
        @Bindable var prefs = prefs
        SettingsPage(title: "Testi") {
            SettingsGroup(header: "Traduzione dei testi", footer: "Traduzione sul dispositivo mostrata sotto il testo originale") {
                SettingsToggle(title: "Traduci testi", subtitle: "Traduci i testi sul dispositivo con Apple Translation",
                               icon: "character.bubble.fill", tile: SettingsTile.teal, isOn: $prefs.translate)
                SettingsDivider()
                SettingsRow(title: "Lingua di destinazione", icon: "globe", tile: SettingsTile.blue) {
                    AccentMenu(selection: $prefs.targetLang, value: LyricsPrefs.label(prefs.targetLang)) {
                        ForEach(["it", "en", "ro", "es", "fr", "de", "pt"], id: \.self) { Text(LyricsPrefs.label($0)).tag($0) }
                    }
                }
            }
            SettingsGroup(header: "Evidenziazione dei testi") {
                highlight("Auto", "Parola per parola se la fonte lo sa fare, se no per righe", .auto)
                SettingsDivider()
                highlight("Parole", "Ogni parola si accende mentre è cantata", .word)
                SettingsDivider()
                highlight("Righe", "Una riga alla volta", .line)
            }
            SettingsGroup(header: "Fonti dei testi", footer: "Automatica chiede a tutte insieme e tiene la migliore che arriva per prima.") {
                source("Automatica", "La più veloce tra tutte", nil)
                ForEach(lyricsSources(), id: \.id) { s in
                    SettingsDivider()
                    source(s.label, s.words ? "\(s.detail) · parola per parola" : s.detail, s.id)
                }
            }
        }
    }

    private func highlight(_ title: String, _ subtitle: String, _ mode: LyricsPrefs.Highlight) -> some View {
        SettingsChoice(title: title, subtitle: subtitle, selected: prefs.highlight == mode, action: { prefs.highlight = mode }) {
            SettingsIconTile(icon: mode == .word ? "textformat.abc" : mode == .line ? "text.alignleft" : "sparkles", color: SettingsTile.purple)
        }
    }

    private func source(_ title: String, _ subtitle: String, _ id: String?) -> some View {
        SettingsChoice(title: title, subtitle: subtitle, selected: player.lyricsSource == id, action: { player.setLyricsSource(id) }) {
            SettingsIconTile(icon: id == nil ? "sparkles" : "quote.bubble.fill", color: SettingsTile.indigo)
        }
    }
}

private struct AppearanceSettings: View {
    @Environment(PhoneTheme.self) private var theme

    /// AccentPalette.kt's options.
    private static let accents: [(String, RGB)] = [
        ("Smeraldo", RGB(0x1D, 0xB9, 0x54)), ("Blu", RGB(0x4D, 0x7C, 0xFF)), ("Indaco", RGB(0x5B, 0x5B, 0xD6)),
        ("Viola", RGB(0x7C, 0x4D, 0xFF)), ("Rosa", RGB(0xFF, 0x2D, 0x78)), ("Rosso", RGB(0xFF, 0x3B, 0x30)),
        ("Arancione", RGB(0xFF, 0x7A, 0x00)), ("Ambra", RGB(0xFF, 0xB3, 0x00)), ("Verde acqua", RGB(0x00, 0xBF, 0xA5)),
        ("Ciano", RGB(0x00, 0xB8, 0xD9)), ("Oro champagne", RGB(0xD4, 0xAF, 0x37)), ("Oro rosa", RGB(0xB7, 0x6E, 0x79)),
        ("Platino", RGB(0xC9, 0xCC, 0xD1)), ("Titanio", RGB(0x5C, 0x66, 0x70)), ("Zaffiro", RGB(0x1F, 0x4F, 0xD8)),
        ("Mezzanotte", RGB(0x20, 0x30, 0x4A)), ("Bordeaux", RGB(0x8E, 0x1B, 0x3A)), ("Bronzo", RGB(0xA9, 0x71, 0x4B)),
        ("Ossidiana", RGB(0x3A, 0x3F, 0x44)),
    ]

    var body: some View {
        @Bindable var theme = theme
        SettingsPage(title: "Aspetto") {
            SettingsGroup(header: "Aspetto", footer: "Canvas Liquid Glass e animazioni") {
                ForEach(Array(PhoneTheme.Mode.allCases.enumerated()), id: \.element) { i, mode in
                    if i > 0 { SettingsDivider() }
                    SettingsChoice(title: mode.title, subtitle: mode.subtitle, selected: theme.mode == mode, action: { theme.mode = mode }) {
                        preview(mode)
                    }
                }
            }
            SettingsGroup {
                SettingsToggle(title: "Liquid Glass", subtitle: "Il vetro di iOS su barra e miniplayer, che si stringono scorrendo",
                               icon: "drop.fill", tile: SettingsTile.blue, isOn: $theme.liquidGlass)
                SettingsDivider()
                SettingsToggle(title: "Riduci animazioni", subtitle: "Disattiva molle, dissolvenze e sfondi animati",
                               icon: "figure.walk.motion", tile: SettingsTile.graphite, isOn: $theme.reduceMotion)
            }
            SettingsGroup(header: "Colore accento", footer: "Si applica a tutti i temi; nei temi dinamici segue la copertina quando disponibile") {
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(Self.accents, id: \.0) { name, rgb in
                            let on = theme.userAccent == rgb
                            Button { theme.userAccent = rgb } label: {
                                Circle().fill(rgb.color)
                                    .padding(5)
                                    .overlay(Circle().strokeBorder(on ? Color.white : .clear, lineWidth: 2))
                                    .overlay { if on { Image(systemName: "checkmark").font(.system(size: 17, weight: .bold)).foregroundStyle(.white) } }
                                    .frame(width: 48, height: 48)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(name)
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 14)
                }
                .scrollIndicators(.hidden)
            }
        }
    }

    /// The mode's canvas in a 30 pt tile with its accent dot (the phone's preview).
    private func preview(_ mode: PhoneTheme.Mode) -> some View {
        let colors: [Color] = mode == .amoled ? [.black, .black] : [RGB.black.mix(theme.userAccent, 0.55).darkened(to: 0.29).color, RGB(0x05, 0x05, 0x09).color]
        return RoundedRectangle(cornerRadius: 8)
            .fill(LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom))
            .overlay {
                if mode == .dynamicCover {
                    Image(systemName: "photo.fill").font(.system(size: 13)).foregroundStyle(.white.opacity(0.8))
                } else {
                    Circle().fill(theme.userAccent.lightened(to: 0.55).color).frame(width: 10, height: 10)
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(PhoneTheme.glassBorder, lineWidth: 0.5))
            .frame(width: 30, height: 30)
    }
}

private struct DataSettings: View {
    @State private var cacheBytes = Int64(URLCache.shared.currentDiskUsage)
    @State private var cleared = false

    var body: some View {
        SettingsPage(title: "Dati e spazio") {
            SettingsGroup(header: "Archiviazione e cache", footer: "Immagini e risposte di rete che LFly tiene su questo iPhone.") {
                SettingsRow(title: "Dimensione cache", icon: "internaldrive.fill", tile: SettingsTile.teal) {
                    Text(ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .file))
                        .font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary)
                }
                SettingsDivider()
                SettingsRow(title: cleared ? "Cache svuotata" : "Svuota cache", icon: "trash.fill", tile: SettingsTile.graphite,
                            titleColor: cleared ? PhoneTheme.textSecondary : SettingsTile.destructive,
                            action: cleared ? nil : {
                                URLCache.shared.removeAllCachedResponses()
                                ArtworkCache.shared.clear()
                                cacheBytes = Int64(URLCache.shared.currentDiskUsage)
                                cleared = true
                            })
            }
        }
    }
}

private struct AdvancedSettings: View {
    @Environment(AppModel.self) private var model
    @State private var health: [ApiHealth] = []

    var body: some View {
        SettingsPage(title: "Avanzate") {
            SettingsGroup(header: "Endpoint API attivi", footer: "Stato di ogni sorgente in questa sessione: si aggiorna a ogni brano e testo cercato.") {
                ForEach(Array(health.enumerated()), id: \.offset) { i, h in
                    if i > 0 { SettingsDivider(leading: false) }
                    HStack(spacing: 10) {
                        Circle().fill(color(h)).frame(width: 9, height: 9)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(h.name).font(.system(size: 15)).foregroundStyle(.white).lineLimit(1)
                            if let e = h.lastError, h.status != "ONLINE" {
                                Text(e).font(.system(size: 12)).foregroundStyle(PhoneTheme.textTertiary).lineLimit(1)
                            }
                        }
                        Spacer()
                        Text(label(h)).font(.system(size: 13).monospacedDigit()).foregroundStyle(PhoneTheme.textSecondary)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 10)
                }
                SettingsDivider(leading: false)
                SettingsRow(title: "Aggiorna", icon: "arrow.clockwise", tile: SettingsTile.blue, action: { health = model.core.apiHealth() })
            }
            SettingsGroup(header: "Sviluppatore") {
                SettingsRow(title: "Marianusus", subtitle: "@Marianusus su GitHub", icon: "person.fill", tile: SettingsTile.graphite,
                            chevron: true, action: { open("https://github.com/Marianusus") })
                SettingsDivider()
                SettingsRow(title: "Codice sorgente", subtitle: "LFly su GitHub, licenza GPL-3.0", icon: "chevron.left.forwardslash.chevron.right",
                            tile: SettingsTile.graphite, chevron: true, action: { open("https://github.com/Marianusus/LFly") })
                SettingsDivider()
                SettingsRow(title: "Versione", icon: "number", tile: SettingsTile.graphite) {
                    Text(appVersion).font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary)
                }
            }
        }
        .onAppear { health = model.core.apiHealth() }
    }

    private func color(_ h: ApiHealth) -> Color {
        switch h.status {
        case "OFFLINE": .red
        case "DEGRADED": .orange
        default: h.used ? .green : PhoneTheme.textTertiary
        }
    }

    private func label(_ h: ApiHealth) -> String {
        if h.status == "OFFLINE" { return "Offline" }
        if let ms = h.latencyMs { return "\(ms) ms" }
        return h.used ? "Online" : "Non usato"
    }

    private func open(_ url: String) {
        if let u = URL(string: url) { UIApplication.shared.open(u) }
    }
}

private struct AboutSettings: View {
    @Environment(PhoneTheme.self) private var theme

    var body: some View {
        SettingsPage(title: "Info su LFly") {
            VStack(spacing: 8) {
                Image(systemName: "waveform")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 76, height: 76)
                    .background(LinearGradient(colors: [theme.accent, theme.accent.opacity(0.5)], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: .rect(cornerRadius: 18))
                Text("Listen & Fly").font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                Text("Listen & Fly - 100% Swift nativo, versione \(appVersion)").font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary)
                Text("Client audio veloce, resiliente e senza fronzoli, realizzato con SwiftUI e AVFoundation su un core Rust. Failover dello stream, testi sincronizzati e Liquid Glass.")
                    .font(.system(size: 14)).foregroundStyle(PhoneTheme.textSecondary).multilineTextAlignment(.center)
                    .padding(.top, 4)
                Text("Miau will always achieve something").font(.system(size: 11, weight: .medium)).foregroundStyle(PhoneTheme.textTertiary)
                    .padding(.top, 8)
            }
            .frame(maxWidth: .infinity)
            .padding(20)
            .background(Color.white.opacity(0.08), in: .rect(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(PhoneTheme.glassBorder, lineWidth: 0.5))
        }
    }
}

private var appVersion: String {
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
}

// MARK: The grouped list (SettingsGroupedList.kt)

/// A page: its big title (with the back button above it, off the root) and its groups 28 pt apart.
private struct SettingsPage<Content: View>: View {
    let title: String
    var root = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text(title).font(.system(size: 34, weight: .bold)).tracking(-0.7).foregroundStyle(.white)
                    .padding(.top, root ? 12 : 0)
                    .padding(.horizontal, 4)
                content()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .toolbar(root ? .hidden : .visible, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        .phoneCanvas()
    }
}

/// Icon tiles, one hue per subject, each keeping its white glyph legible (SettingsTile).
enum SettingsTile {
    static let blue = Color(red: 0x0A / 255, green: 0x6C / 255, blue: 1)
    static let indigo = Color(red: 0x58 / 255, green: 0x56 / 255, blue: 0xD6 / 255)
    static let purple = Color(red: 0x89 / 255, green: 0x44 / 255, blue: 0xAB / 255)
    static let red = Color(red: 0xD7 / 255, green: 0, blue: 0x15 / 255)
    static let teal = Color(red: 0x0E / 255, green: 0x7C / 255, blue: 0x86 / 255)
    static let orange = Color(red: 0xC2 / 255, green: 0x57 / 255, blue: 0x0C / 255)
    static let graphite = Color(red: 0x63 / 255, green: 0x63 / 255, blue: 0x66 / 255)
    static let green = Color(red: 0x24 / 255, green: 0x8A / 255, blue: 0x3D / 255)
    /// The destructive red on a dark surface (#FF6961).
    static let destructive = Color(red: 1, green: 0x69 / 255, blue: 0x61 / 255)
}

/// A caption, the rows on one 16 pt glass surface, and an optional footnote.
private struct SettingsGroup<Content: View>: View {
    var header: String?
    var footer: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            if let header {
                Text(header).font(.system(size: 13, weight: .medium)).foregroundStyle(PhoneTheme.textSecondary)
                    .padding(.horizontal, 16)
                    .accessibilityAddTraits(.isHeader)
            }
            VStack(spacing: 0, content: content)
                .background(Color.white.opacity(0.08), in: .rect(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(PhoneTheme.glassBorder, lineWidth: 0.5))
                .clipShape(.rect(cornerRadius: 16))
            if let footer {
                Text(footer).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary)
                    .padding(.horizontal, 16)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// A hairline from the row's text (after the tile), so the group reads as one list.
private struct SettingsDivider: View {
    var leading = true

    var body: some View {
        Rectangle().fill(Color.white.opacity(0.15)).frame(height: 0.5).padding(.leading, leading ? 16 + 30 + 12 : 16)
    }
}

/// A row: a tile (or any leading view), title and subtitle, then its value, control or chevron;
/// with an action the whole row is the button.
private struct SettingsRow<Leading: View, Trailing: View>: View {
    let title: String
    var subtitle: String?
    var titleColor: Color = .white
    var chevron = false
    var action: (() -> Void)?
    let leading: Leading
    let trailing: Trailing

    init(title: String, subtitle: String? = nil, icon: String, tile: Color, titleColor: Color = .white, chevron: Bool = false,
         action: (() -> Void)? = nil, @ViewBuilder trailing: () -> Trailing = { EmptyView() }) where Leading == SettingsIconTile {
        self.title = title; self.subtitle = subtitle; self.titleColor = titleColor; self.chevron = chevron; self.action = action
        leading = SettingsIconTile(icon: icon, color: tile)
        self.trailing = trailing()
    }

    init(title: String, subtitle: String? = nil, chevron: Bool = false, action: (() -> Void)? = nil,
         @ViewBuilder leading: () -> Leading) where Trailing == EmptyView {
        self.title = title; self.subtitle = subtitle; self.chevron = chevron; self.action = action
        self.leading = leading()
        trailing = EmptyView()
    }

    var body: some View {
        if let action {
            Button(action: action) { row }.buttonStyle(RowPress())
        } else {
            row
        }
    }

    private var row: some View {
        HStack(spacing: 12) {
            leading
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 16)).foregroundStyle(titleColor).lineLimit(2)
                if let subtitle {
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(3)
                }
            }
            .multilineTextAlignment(.leading)
            Spacer(minLength: 8)
            trailing
            if chevron {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(PhoneTheme.textTertiary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 50)
        .contentShape(.rect)
    }
}

/// A switch row (SettingsToggleRow): the whole row flips it.
private struct SettingsToggle: View {
    let title: String
    var subtitle: String?
    let icon: String
    let tile: Color
    @Binding var isOn: Bool

    var body: some View {
        SettingsRow(title: title, subtitle: subtitle, icon: icon, tile: tile) {
            Toggle("", isOn: $isOn).labelsHidden()
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isToggle)
    }
}

/// One choice of several (SettingsChoiceRow): a checkmark in the accent on the chosen one.
private struct SettingsChoice<Leading: View>: View {
    @Environment(PhoneTheme.self) private var theme
    let title: String
    let subtitle: String
    let selected: Bool
    let action: () -> Void
    @ViewBuilder let leading: () -> Leading

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                leading()
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 16)).foregroundStyle(.white)
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(2)
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                Image(systemName: "checkmark").font(.system(size: 16, weight: .bold)).foregroundStyle(theme.accent)
                    .opacity(selected ? 1 : 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(minHeight: 50)
            .contentShape(.rect)
        }
        .buttonStyle(RowPress())
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A rounded 30 pt tile with a white symbol.
struct SettingsIconTile: View {
    let icon: String
    let color: Color

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 30, height: 30)
            .background(color, in: .rect(cornerRadius: 8))
            .accessibilityHidden(true)
    }
}

/// A tapped row lights up a little, as the phone's ripple on a grouped row.
private struct RowPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.background(Color.white.opacity(configuration.isPressed ? 0.08 : 0))
    }
}

/// A value in the theme's accent that opens a menu of choices (iOS menu pickers keep their own
/// colour).
private struct AccentMenu<Value: Hashable, Options: View>: View {
    @Environment(PhoneTheme.self) private var theme
    @Binding var selection: Value
    let value: String
    @ViewBuilder let options: () -> Options

    var body: some View {
        Menu {
            Picker("", selection: $selection, content: options)
        } label: {
            HStack(spacing: 4) {
                Text(value).font(.system(size: 15)).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(theme.accent)
        }
    }
}
