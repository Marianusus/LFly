import SwiftUI

/// A cover from the network, with a quiet placeholder while it loads. It takes the size it is
/// given and crops the image to it (some covers are 16:9 video frames).
struct Artwork: View {
    let url: String?
    var radius: CGFloat = 8

    var body: some View {
        Rectangle()
            .fill(PhoneTheme.glassTint)
            .overlay {
                AsyncImage(url: url.flatMap(URL.init(string:))) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
            .clipShape(.rect(cornerRadius: radius))
            .accessibilityHidden(true)
    }
}

/// A song in a list: cover, title, artist; the playing one is marked.
struct SongRow: View {
    @Environment(Player.self) private var player
    @Environment(Library.self) private var library
    let song: Song

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: song.coverUrl, radius: 6).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    .foregroundStyle(player.current?.id == song.id ? AnyShapeStyle(.tint) : AnyShapeStyle(.white))
                Text(song.artist).font(.system(size: 13)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let p = library.downloading[song.id] {
                ProgressView(value: p).progressViewStyle(.circular).controlSize(.small)
            } else if library.isDownloaded(song) {
                Image(systemName: "arrow.down.circle.fill").foregroundStyle(.secondary).accessibilityLabel("Scaricato")
            }
            if song.isVideo {
                Image(systemName: "play.rectangle").foregroundStyle(.secondary).accessibilityLabel("Video")
            }
        }
        .contentShape(.rect)
        .songMenu(song)
    }
}

/// A card on a rail (the phone's 160 pt rail card): cover, title, subtitle.
struct ItemCard: View {
    let item: Item

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: item.coverUrl, radius: item.kind == .artist ? 80 : Phone.cardRadius)
                .frame(width: 160, height: 160)
            Text(item.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
            if let sub = item.subtitle {
                Text(sub).font(.system(size: 12)).foregroundStyle(PhoneTheme.textSecondary).lineLimit(1)
            }
        }
        .frame(width: 160, alignment: .leading)
        .multilineTextAlignment(.leading)
    }
}

func timeText(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let s = Int(seconds)
    return String(format: "%d:%02d", s / 60, s % 60)
}

extension Item: Identifiable {}

/// The phone's Play (accent, filled) and Shuffle (glass) pair.
struct PlayShuffleButtons: View {
    @Environment(PhoneTheme.self) private var theme
    let play: () -> Void
    let shuffle: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: play) {
                Label("Riproduci", systemImage: "play.fill").font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.black).frame(maxWidth: .infinity).frame(height: 50)
                    .background(theme.accent, in: .capsule)
            }
            Button(action: shuffle) {
                Label("Casuale", systemImage: "shuffle").font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 50)
                    .background(PhoneTheme.glassTint, in: .capsule)
                    .overlay(Capsule().strokeBorder(PhoneTheme.glassBorder, lineWidth: 1))
            }
        }
        .buttonStyle(PressScale(scale: 0.96))
    }
}
