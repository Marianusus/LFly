import SwiftUI

/// The phone's Settings (SettingsGroupedList.kt): small grey captions over 16 pt glass groups,
/// rows with a coloured 30 pt icon tile, hairlines from the text, footnotes under a group. Only
/// what the iPhone app can act on: accounts, appearance, source, playback, lyrics, devices,
/// storage, about.
struct SettingsView: View {
    @Environment(Account.self) private var account
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    @Environment(LyricsPrefs.self) private var lyricsPrefs
    @Environment(PartyManager.self) private var party
    @Environment(PhoneTheme.self) private var theme
    @State private var showAccount = false
    @State private var showDevices = false
    @State private var showParty = false
    @State private var cleared = false

    var body: some View {
        @Bindable var lyricsPrefs = lyricsPrefs
        @Bindable var theme = theme
        @Bindable var player = player
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                PhoneTitle(title: "Impostazioni").padding(.horizontal, -4)

                SettingsGroup(header: "Account") {
                    SettingsRow(title: account.profile?.name ?? "YouTube Music",
                                subtitle: account.profile?.email ?? "Accedi per la tua Home e le tue playlist",
                                chevron: true, action: { showAccount = true }) {
                        Artwork(url: account.profile?.avatarUrl, radius: 15).frame(width: 30, height: 30)
                    }
                }

                SettingsGroup(header: "Aspetto", footer: "Dinamico scuro e Copertina prendono i colori dal brano in riproduzione.") {
                    SettingsRow(title: "Tema", icon: "paintpalette.fill", tile: SettingsTile.purple) {
                        AccentMenu(selection: $theme.mode, value: theme.mode.title) {
                            ForEach(PhoneTheme.Mode.allCases, id: \.self) { Text($0.title).tag($0) }
                        }
                    }
                }

                SettingsGroup(header: "Sorgente streaming", footer: sourceNote) {
                    SettingsRow(title: "Sorgente", icon: "antenna.radiowaves.left.and.right", tile: SettingsTile.blue) {
                        AccentMenu(selection: $player.playbackSource, value: sourceName) {
                            Text("YouTube Music").tag("YOUTUBE_MUSIC")
                            Text("Automatica").tag("AUTO")
                            Text("Monochrome").tag("MONOCHROME")
                        }
                    }
                    if let p = player.provider {
                        SettingsDivider()
                        SettingsRow(title: "In riproduzione da", icon: "waveform", tile: SettingsTile.teal) {
                            // "YouTube Music (VISIONOS)": the client in brackets says nothing to a listener.
                            Text(p.components(separatedBy: " (").first ?? p).font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
                        }
                    }
                }

                SettingsGroup(header: "Riproduzione e coda") {
                    SettingsRow(title: "Riproduzione automatica", subtitle: "Radio infinita a fine coda", icon: "infinity", tile: SettingsTile.green) {
                        Toggle("", isOn: Binding(get: { player.autoplay }, set: { if $0 != player.autoplay { player.toggleAutoplay() } }))
                            .labelsHidden()
                    }
                    SettingsDivider()
                    SettingsRow(title: "Velocità", icon: "gauge.with.dots.needle.67percent", tile: SettingsTile.orange) {
                        AccentMenu(selection: Binding(get: { player.rate }, set: { player.setRate($0) }),
                                   value: player.rate == 1 ? "Normale" : "\(player.rate.formatted())×") {
                            ForEach([Float(0.75), 1, 1.25, 1.5], id: \.self) { r in Text(r == 1 ? "Normale" : "\(r.formatted())×").tag(r) }
                        }
                    }
                }

                SettingsGroup(header: "Testi") {
                    SettingsRow(title: "Fonte dei testi", icon: "quote.bubble.fill", tile: SettingsTile.indigo) {
                        AccentMenu(selection: Binding(get: { player.lyricsSource ?? "" }, set: { player.setLyricsSource($0.isEmpty ? nil : $0) }),
                                   value: player.lyricsSource.flatMap { id in lyricsSources().first { $0.id == id }?.label } ?? "Automatica") {
                            Text("Automatica (la più veloce)").tag("")
                            ForEach(lyricsSources(), id: \.id) { s in Text(s.label).tag(s.id) }
                        }
                    }
                    SettingsDivider()
                    SettingsRow(title: "Evidenziazione", icon: "highlighter", tile: SettingsTile.purple) {
                        AccentMenu(selection: $lyricsPrefs.highlight,
                                   value: ["auto": "Automatica", "word": "Parola", "line": "Riga"][lyricsPrefs.highlight.rawValue] ?? "") {
                            Text("Automatica").tag(LyricsPrefs.Highlight.auto)
                            Text("Parola per parola").tag(LyricsPrefs.Highlight.word)
                            Text("Riga per riga").tag(LyricsPrefs.Highlight.line)
                        }
                    }
                    SettingsDivider()
                    SettingsRow(title: "Traduci testi", icon: "character.bubble.fill", tile: SettingsTile.teal) {
                        Toggle("", isOn: $lyricsPrefs.translate).labelsHidden()
                    }
                    if lyricsPrefs.translate {
                        SettingsDivider()
                        SettingsRow(title: "Traduci in", icon: "globe", tile: SettingsTile.blue) {
                            AccentMenu(selection: $lyricsPrefs.targetLang, value: LyricsPrefs.label(lyricsPrefs.targetLang)) {
                                ForEach(["it", "en", "ro", "es", "fr", "de", "pt"], id: \.self) { Text(LyricsPrefs.label($0)).tag($0) }
                            }
                        }
                    }
                }

                SettingsGroup(header: "Dispositivi") {
                    SettingsRow(title: "LFly Connect", subtitle: "Comanda gli altri dispositivi", icon: "hifispeaker.2.fill",
                                tile: SettingsTile.green, chevron: true, action: { showDevices = true })
                    SettingsDivider()
                    SettingsRow(title: "LFly Party", subtitle: partyStatus, icon: "party.popper.fill",
                                tile: SettingsTile.red, chevron: true, action: { showParty = true })
                }

                SettingsGroup(header: "Archiviazione e cache") {
                    SettingsRow(title: "Brani offline", icon: "arrow.down.circle.fill", tile: SettingsTile.green) {
                        Text("\(library.downloads.count) · \(downloadsSize)").font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary)
                    }
                    SettingsDivider()
                    SettingsRow(title: cleared ? "Cache svuotata" : "Svuota cache immagini", icon: "trash.fill",
                                tile: SettingsTile.graphite, titleColor: cleared ? PhoneTheme.textSecondary : SettingsTile.destructive,
                                action: cleared ? nil : {
                                    URLCache.shared.removeAllCachedResponses()
                                    ArtworkCache.shared.clear()
                                    cleared = true
                                })
                }

                SettingsGroup(header: "Listen & Fly") {
                    SettingsRow(title: "Versione", icon: "info.circle.fill", tile: SettingsTile.graphite) {
                        Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                            .font(.system(size: 15)).foregroundStyle(PhoneTheme.textSecondary)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .scrollIndicators(.hidden)
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(isPresented: $showParty) { PartyView().phonePage() }
        .sheet(isPresented: $showAccount) { AccountView() }
        .sheet(isPresented: $showDevices) { DevicesView() }
    }

    private var partyStatus: String {
        guard party.isActive else { return "Ascoltate insieme, in sincrono" }
        return party.role == .host ? "Party aperto: \(party.code ?? "")" : "Nel Party"
    }

    private var sourceName: String {
        ["AUTO": "Automatica", "MONOCHROME": "Monochrome"][player.playbackSource] ?? "YouTube Music"
    }

    private var sourceNote: String {
        switch player.playbackSource {
        case "AUTO": "Chiede a YouTube Music e a Monochrome insieme e aspetta Monochrome fino a 1,5 s per la qualità più alta: ogni brano parte più tardi."
        case "MONOCHROME": "Monochrome prima (AAC fino a 320 kbit/s), YouTube Music se non risponde. Quando Monochrome è giù, l'attesa è lunga."
        default: "La più veloce: YouTube Music (AAC 128 kbit/s), Monochrome solo se YouTube non ha il brano."
        }
    }

    private var downloadsSize: String {
        let bytes = library.downloads.compactMap { library.localFile(for: $0) }.reduce(Int64(0)) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
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
    var body: some View {
        Rectangle().fill(Color.white.opacity(0.15)).frame(height: 0.5).padding(.leading, 16 + 30 + 12)
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
                Text(title).font(.system(size: 16)).foregroundStyle(titleColor).lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(2)
                }
            }
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
