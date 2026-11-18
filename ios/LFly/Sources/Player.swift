import AVFoundation
import MediaPlayer
import Observation
import UIKit

/// Playback: one AVPlayer, LFly's own queue, the lock screen and Control Center (Now Playing and
/// remote commands), and the next song's stream resolved ahead so the change is instant.
@MainActor
@Observable
final class Player {
    private(set) var queue: [Song] = []
    private(set) var index: Int = 0
    private(set) var isPlaying = false
    private(set) var isLoading = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var lyrics: SongLyrics?
    private(set) var error: String?
    private(set) var shuffle = false
    private(set) var repeatMode: RepeatMode = .off
    /// Playback speed (pitch kept).
    private(set) var rate: Float = 1
    /// When the sleep timer stops the music; `endOfSong` stops after this one.
    private(set) var sleepAt: Date?
    private(set) var sleepAtEndOfSong = false

    enum RepeatMode { case off, all, one }

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }

    private let core: Core
    private let library: Library
    private let avPlayer = AVPlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    /// Streams resolved ahead, by song id.
    private var ready: [String: AudioStream] = [:]
    private var artwork: (id: String, image: MPMediaItemArtwork)?
    /// The queue in its own order, to go back to when shuffle goes off.
    private var unshuffled: [Song]?
    private var sleepTask: Task<Void, Never>?

    init(core: Core, library: Library) {
        self.core = core
        self.library = library
        avPlayer.automaticallyWaitsToMinimizeStalling = true
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
        timeObserver = avPlayer.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        }
        setupRemoteCommands()
    }

    /// Plays [songs] from [start]: the queue becomes that list.
    func play(_ songs: [Song], from start: Int = 0) {
        guard songs.indices.contains(start) else { return }
        queue = songs
        index = start
        load()
    }

    /// Puts [song] right after the one playing.
    func playNext(_ song: Song) {
        guard current != nil else { return play([song]) }
        queue.removeAll { $0.id == song.id && $0.id != current?.id }
        queue.insert(song, at: min(index + 1, queue.count))
    }

    /// Puts [song] at the end of the queue.
    func addToQueue(_ song: Song) {
        guard current != nil else { return play([song]) }
        queue.append(song)
    }

    /// Shuffle keeps the song playing and mixes the rest; off puts the list back in order.
    func toggleShuffle() {
        guard let cur = current else { return }
        if shuffle, let original = unshuffled {
            queue = original
            index = original.firstIndex { $0.id == cur.id } ?? 0
            unshuffled = nil
        } else {
            unshuffled = queue
            var rest = queue
            rest.remove(at: index)
            queue = [cur] + rest.shuffled()
            index = 0
        }
        shuffle.toggle()
        ready.removeAll()
        prepareNext()
    }

    /// Off → all → one → off, as on the phone.
    func cycleRepeat() {
        repeatMode = switch repeatMode { case .off: .all; case .all: .one; case .one: .off }
    }

    /// Plays the queue's song at [i].
    func jump(to i: Int) {
        guard queue.indices.contains(i) else { return }
        index = i
        load()
    }

    func removeFromQueue(at offsets: IndexSet) {
        let playing = current?.id
        for i in offsets.sorted(by: >) where i != index {
            queue.remove(at: i)
            if i < index { index -= 1 }
        }
        if let id = playing, let i = queue.firstIndex(where: { $0.id == id }) { index = i }
        prepareNext()
    }

    func moveInQueue(from: IndexSet, to: Int) {
        let playing = current?.id
        queue.move(fromOffsets: from, toOffset: to)
        if let id = playing, let i = queue.firstIndex(where: { $0.id == id }) { index = i }
        prepareNext()
    }

    func setRate(_ r: Float) {
        rate = r
        avPlayer.currentItem?.audioTimePitchAlgorithm = .timeDomain
        if isPlaying { avPlayer.rate = r }
        updateNowPlaying()
    }

    /// Stops the music after [minutes], or at the end of this song (nil); 0 turns it off.
    func sleep(minutes: Int?) {
        sleepTask?.cancel()
        sleepAt = nil
        sleepAtEndOfSong = false
        guard let m = minutes else {
            sleepAtEndOfSong = true
            return
        }
        guard m > 0 else { return }
        let at = Date.now.addingTimeInterval(TimeInterval(m * 60))
        sleepAt = at
        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(m * 60)))
            guard !Task.isCancelled, let self else { return }
            if self.isPlaying { self.toggle() }
            self.sleepAt = nil
        }
    }

    func toggle() {
        guard current != nil else { return }
        if isPlaying { avPlayer.pause() } else { avPlayer.play(); if rate != 1 { avPlayer.rate = rate } }
        isPlaying.toggle()
        updateNowPlaying()
    }

    /// A song played to its end: the sleep timer, repeat one, then the next (or the first again).
    private func ended() {
        if sleepAtEndOfSong {
            sleepAtEndOfSong = false
            isPlaying = false
            updateNowPlaying()
            return
        }
        switch repeatMode {
        case .one: seek(to: 0); avPlayer.play()
        case .all where index + 1 >= queue.count: jump(to: 0)
        default: next()
        }
    }

    func next() {
        guard index + 1 < queue.count else {
            avPlayer.pause()
            isPlaying = false
            updateNowPlaying()
            return
        }
        index += 1
        load()
    }

    func previous() {
        // As Music: back to the start first, then the song before.
        if position > 3 || index == 0 {
            seek(to: 0)
            return
        }
        index -= 1
        load()
    }

    func seek(to seconds: Double) {
        avPlayer.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        position = seconds
        updateNowPlaying()
    }

    private func load() {
        guard let song = current else { return }
        loadTask?.cancel()
        avPlayer.pause()
        isLoading = true
        error = nil
        lyrics = nil
        position = 0
        duration = Double(song.durationMs) / 1000
        updateNowPlaying()
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let asset: AVURLAsset
                // A downloaded song plays from its file, without a network.
                if let local = self.library.localFile(for: song) {
                    asset = AVURLAsset(url: local)
                } else {
                    let stream = try await self.stream(for: song)
                    var options: [String: Any] = [:]
                    if let ua = stream.userAgent {
                        options["AVURLAssetHTTPHeaderFieldsKey"] = ["User-Agent": ua]
                    }
                    guard let url = URL(string: stream.url) else { throw URLError(.badURL) }
                    asset = AVURLAsset(url: url, options: options)
                }
                guard !Task.isCancelled, self.current?.id == song.id else { return }
                try? AVAudioSession.sharedInstance().setActive(true)
                let item = AVPlayerItem(asset: asset)
                self.library.played(song)
                item.audioTimePitchAlgorithm = .timeDomain
                self.avPlayer.replaceCurrentItem(with: item)
                self.avPlayer.play()
                if self.rate != 1 { self.avPlayer.rate = self.rate }
                self.isPlaying = true
                self.isLoading = false
                self.updateNowPlaying()
                self.prepareNext()
                self.lyrics = try? await self.core.lyrics(song: song)
            } catch {
                guard !Task.isCancelled else { return }
                self.isLoading = false
                self.error = error.localizedDescription
                // A song that cannot play is skipped, as on the phone.
                if self.index + 1 < self.queue.count { self.next() }
            }
        }
    }

    private func stream(for song: Song) async throws -> AudioStream {
        if let s = ready.removeValue(forKey: song.id) { return s }
        return try await core.stream(song: song)
    }

    /// The next song's stream, resolved while this one plays.
    private func prepareNext() {
        guard queue.indices.contains(index + 1) else { return }
        let next = queue[index + 1]
        guard ready[next.id] == nil, library.localFile(for: next) == nil else { return }
        Task { [weak self] in
            guard let self, let s = try? await self.core.stream(song: next) else { return }
            self.ready[next.id] = s
        }
    }

    private func tick(_ time: CMTime) {
        guard !isLoading else { return }
        position = time.seconds.isFinite ? time.seconds : 0
        if let d = avPlayer.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
    }

    // MARK: Lock screen, Control Center, headphones

    private func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if self?.isPlaying == false { self?.toggle() } }
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if self?.isPlaying == true { self?.toggle() } }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.toggle() }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.next() }
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.previous() }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(to: e.positionTime) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let song = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let album = song.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let art = artwork, art.id == song.id { info[MPMediaItemPropertyArtwork] = art.image }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artwork?.id != song.id, let cover = song.coverUrl, let url = URL(string: cover) {
            Task { [weak self] in
                guard let (data, _) = try? await URLSession.shared.data(from: url), let image = UIImage(data: data) else { return }
                let art = Player.artwork(image)
                guard let self, self.current?.id == song.id else { return }
                self.artwork = (song.id, art)
                self.updateNowPlaying()
            }
        }
    }

    /// MediaPlayer asks for the image on a thread of its own: the closure must not belong to the
    /// main actor (Swift 6 traps when it runs elsewhere).
    nonisolated private static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }
}
