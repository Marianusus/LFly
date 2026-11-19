import Foundation
import Observation
import os

private let log = Logger(subsystem: "org.sonora.lfly", category: "library")

/// A playlist made in LFly (the phone's `Playlist`).
struct LocalPlaylist: Identifiable, Hashable {
    let id: String
    var name: String
    var songs: [Song]
    var coverUrl: String? { songs.first?.coverUrl }
}

/// The listener's library on this iPhone, in the phone's and the computer's own files
/// (favorites.json, playlists.json, history.json: Gson's JSON of `List<Track>` and
/// `List<Playlist>`), so a library moves between them as it is. Tracks stay the JSON they came
/// in: fields this app does not know survive.
@MainActor
@Observable
final class Library {
    private(set) var favorites: [Song] = []
    private(set) var playlists: [LocalPlaylist] = []
    private(set) var history: [Song] = []
    private(set) var downloads: [Song] = []
    /// Downloads in progress, by song id (0…1).
    private(set) var downloading: [String: Double] = [:]

    private let dir: URL

    /// Called after every edit here (LFly Connect offers the library to the account's devices).
    var onEdit: (() -> Void)?

    init() {
        dir = URL.documentsDirectory.appending(path: "library", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: downloadsDir, withIntermediateDirectories: true)
        reload()
        downloads = Self.songs(read("downloads.json")).filter { FileManager.default.fileExists(atPath: file(for: $0).path) }
    }

    /// The folder LFly Connect syncs.
    var folder: URL { dir }

    /// Reads the files again (a sync rewrote them).
    func reload() {
        favorites = Self.songs(read("favorites.json"))
        history = Self.songs(read("history.json"))
        playlists = (read("playlists.json") ?? []).compactMap { p in
            guard let id = p["id"] as? String else { return nil }
            return LocalPlaylist(id: id, name: p["name"] as? String ?? "", songs: Self.songs(p["tracks"] as? [[String: Any]]))
        }
    }

    // MARK: Favourites

    func isFavorite(_ song: Song) -> Bool { favorites.contains { $0.id == song.id } }

    func toggleFavorite(_ song: Song) {
        if let i = favorites.firstIndex(where: { $0.id == song.id }) {
            favorites.remove(at: i)
        } else {
            favorites.insert(song, at: 0)
        }
        write("favorites.json", favorites.map(Self.json))
    }

    /// Adds songs not there yet (YouTube Music's liked songs after signing in).
    func addFavorites(_ songs: [Song]) {
        let have = Set(favorites.map(\.id))
        let new = songs.filter { !have.contains($0.id) }
        guard !new.isEmpty else { return }
        favorites.insert(contentsOf: new, at: 0)
        write("favorites.json", favorites.map(Self.json))
    }

    // MARK: Playlists

    @discardableResult
    func createPlaylist(_ name: String, songs: [Song] = []) -> LocalPlaylist {
        let p = LocalPlaylist(id: UUID().uuidString, name: name.isEmpty ? "La mia playlist" : name, songs: songs)
        playlists.insert(p, at: 0)
        savePlaylists()
        return p
    }

    func add(_ song: Song, to playlist: LocalPlaylist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }), !playlists[i].songs.contains(where: { $0.id == song.id }) else { return }
        playlists[i].songs.append(song)
        savePlaylists()
    }

    func remove(_ song: Song, from playlist: LocalPlaylist) {
        guard let i = playlists.firstIndex(where: { $0.id == playlist.id }) else { return }
        playlists[i].songs.removeAll { $0.id == song.id }
        savePlaylists()
    }

    func delete(_ playlist: LocalPlaylist) {
        playlists.removeAll { $0.id == playlist.id }
        savePlaylists()
    }

    private func savePlaylists() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Fields of the phone's Playlist this app does not edit are kept from the file.
        let old = Dictionary((read("playlists.json") ?? []).compactMap { p in (p["id"] as? String).map { ($0, p) } }, uniquingKeysWith: { a, _ in a })
        let list: [[String: Any]] = playlists.map { p in
            var o = old[p.id] ?? ["createdAt": now, "schemaVersion": 2]
            o["id"] = p.id
            o["name"] = p.name
            o["tracks"] = p.songs.map(Self.json)
            o["updatedAt"] = now
            return o
        }
        write("playlists.json", list)
    }

    // MARK: History

    func played(_ song: Song) {
        history.removeAll { $0.id == song.id }
        history.insert(song, at: 0)
        if history.count > 200 { history.removeLast(history.count - 200) }
        write("history.json", history.map(Self.json))
    }

    // MARK: Downloads

    private var downloadsDir: URL { URL.documentsDirectory.appending(path: "downloads", directoryHint: .isDirectory) }

    func file(for song: Song) -> URL {
        let safe = song.id.replacingOccurrences(of: "/", with: "_")
        return downloadsDir.appending(path: safe + ".m4a")
    }

    /// The downloaded copy, when there is one.
    func localFile(for song: Song) -> URL? {
        let f = file(for: song)
        return FileManager.default.fileExists(atPath: f.path) ? f : nil
    }

    func isDownloaded(_ song: Song) -> Bool { downloads.contains { $0.id == song.id } }

    /// Downloads the full stream so the song plays without a network.
    func download(_ song: Song, core: Core) {
        guard !isDownloaded(song), downloading[song.id] == nil else { return }
        downloading[song.id] = 0
        Task {
            defer { downloading[song.id] = nil }
            do {
                let stream = try await core.stream(song: song)
                log.info("download \(song.title, privacy: .public): \(stream.provider, privacy: .public) \(stream.mime, privacy: .public)")
                guard let url = URL(string: stream.url) else { return }
                let data = try await Self.fetch(url, userAgent: stream.userAgent) { p in
                    Task { @MainActor in if self.downloading[song.id] != nil { self.downloading[song.id] = p } }
                }
                let dest = file(for: song)
                try data.write(to: dest, options: .atomic)
                downloads.insert(song, at: 0)
                write("downloads.json", downloads.map(Self.json))
            } catch {
                log.error("download \(song.title, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// The whole file in 1 MiB `Range` requests, four at a time: googlevideo throttles one long
    /// request to a crawl, not ranged ones (the desktop's http.rs does the same).
    nonisolated static func fetch(_ url: URL, userAgent: String?, progress: @escaping @Sendable (Double) -> Void) async throws -> Data {
        let chunk = 1 << 20
        func get(_ from: Int, _ to: Int) async throws -> (Data, Int?) {
            var r = URLRequest(url: url)
            r.setValue("bytes=\(from)-\(to)", forHTTPHeaderField: "Range")
            if let ua = userAgent { r.setValue(ua, forHTTPHeaderField: "User-Agent") }
            let (data, response) = try await URLSession.shared.data(for: r)
            let http = response as? HTTPURLResponse
            guard let status = http?.statusCode, status < 400 else { throw URLError(.badServerResponse) }
            // "bytes a-b/total"; a server that ignores Range sends it all (200).
            let total = (http?.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap { Int($0) }
            return (data, status == 200 ? data.count : total)
        }
        let (first, total) = try await get(0, chunk - 1)
        guard let total, total > first.count else { return first }
        let count = (total + chunk - 1) / chunk
        var parts = [Int: Data]()
        parts[0] = first
        try await withThrowingTaskGroup(of: (Int, Data).self) { group in
            var next = 1
            func add() {
                guard next < count else { return }
                let i = next
                next += 1
                group.addTask { (i, try await get(i * chunk, min((i + 1) * chunk, total) - 1).0) }
            }
            for _ in 0..<4 { add() }
            while let (i, d) = try await group.next() {
                parts[i] = d
                progress(Double(parts.count) / Double(count))
                add()
            }
        }
        var out = Data(capacity: total)
        for i in 0..<count { out.append(parts[i] ?? Data()) }
        return out
    }

    func removeDownload(_ song: Song) {
        try? FileManager.default.removeItem(at: file(for: song))
        downloads.removeAll { $0.id == song.id }
        write("downloads.json", downloads.map(Self.json))
    }

    // MARK: Files

    private func read(_ name: String) -> [[String: Any]]? {
        guard let data = try? Data(contentsOf: dir.appending(path: name)) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]
    }

    private func write(_ name: String, _ list: [[String: Any]]) {
        guard let data = try? JSONSerialization.data(withJSONObject: list) else { return }
        try? data.write(to: dir.appending(path: name), options: .atomic)
        if name != "downloads.json" { onEdit?() }
    }

    /// The track's own JSON (as lfly-yt and the phone write it).
    nonisolated static func json(_ song: Song) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(song.raw.utf8))) as? [String: Any] ?? [
            "id": song.id, "title": song.title, "artist": song.artist, "durationMs": song.durationMs,
        ]
    }

    nonisolated static func songs(_ list: [[String: Any]]?) -> [Song] {
        (list ?? []).compactMap(song)
    }

    nonisolated static func song(_ o: [String: Any]) -> Song? {
        guard let id = o["id"] as? String, let title = o["title"] as? String else { return nil }
        let raw = (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return Song(
            id: id,
            title: title,
            artist: o["artist"] as? String ?? "",
            album: o["album"] as? String,
            durationMs: (o["durationMs"] as? NSNumber)?.int64Value ?? 0,
            coverUrl: o["coverUrl"] as? String,
            isVideo: o["isVideo"] as? Bool ?? false,
            raw: raw
        )
    }
}
