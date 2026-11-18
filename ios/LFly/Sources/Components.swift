import SwiftUI

/// A cover from the network, with a quiet placeholder while it loads. It takes the size it is
/// given and crops the image to it (some covers are 16:9 video frames).
struct Artwork: View {
    let url: String?
    var radius: CGFloat = 8

    var body: some View {
        Rectangle()
            .fill(.quaternary)
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
    let song: Song

    var body: some View {
        HStack(spacing: 12) {
            Artwork(url: song.coverUrl, radius: 6).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).font(.body).lineLimit(1)
                    .foregroundStyle(player.current?.id == song.id ? Color.accentColor : .primary)
                Text(song.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if song.isVideo {
                Image(systemName: "play.rectangle").foregroundStyle(.secondary).accessibilityLabel("Video")
            }
        }
        .contentShape(.rect)
    }
}

/// A card on a horizontal shelf.
struct ItemCard: View {
    let item: Item

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Artwork(url: item.coverUrl, radius: item.kind == .artist ? 70 : 10)
                .frame(width: 140, height: 140)
            Text(item.title).font(.subheadline.weight(.medium)).lineLimit(1)
            if let sub = item.subtitle {
                Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(width: 140, alignment: .leading)
    }
}

func timeText(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let s = Int(seconds)
    return String(format: "%d:%02d", s / 60, s % 60)
}
