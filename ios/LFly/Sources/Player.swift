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
    private(set) var lyricsLoading = false
    /// The lyrics source the listener picked (an id of `lyricsSources()`), or nil for the usual race.
    private(set) var lyricsSource: String? = UserDefaults.standard.string(forKey: "lyricsSource")
    /// When `position` was last read from the player, to run it on between reads.
    private(set) var positionAt = Date.now
    private(set) var error: String?
    private(set) var shuffle = false
    /// When the queue runs out, YouTube Music's next songs follow (the phone's ∞).
    private(set) var autoplay = UserDefaults.standard.object(forKey: "autoplayOn") as? Bool ?? true
    /// Where the song plays from ("YouTube Music (VISIONOS)", "Monochrome", "Scaricato").
    private(set) var provider: String?
    private(set) var repeatMode: RepeatMode = .off
    /// Playback speed (pitch kept).
    private(set) var rate: Float = 1
    /// When the sleep timer stops the music; `endOfSong` stops after this one.
    private(set) var sleepAt: Date?
    private(set) var sleepAtEndOfSong = false

    enum RepeatMode { case off, all, one }

    /// In a Party as a guest: the controls go to the host instead (true = handled).
    enum PartyAction { case toggle, next, previous, seek }
    var partyTransport: ((PartyAction, Int64) -> Bool)?
    private var partyDrift: SyncEngine.DriftAction = .none

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }

    private let core: Core
    private let library: Library
    /// A queue player: the song after this one waits in its queue, buffering.
    private let avPlayer = AVQueuePlayer()
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    /// Streams resolved ahead, by song id.
    private var ready: [String: AudioStream] = [:]
    /// The next song already queued in the player behind this one, where AVQueuePlayer buffers it
    /// ahead (the phone's N+1 pre-cache: up to two minutes, some 2 MB at 130 kbit/s).
    private var warm: (id: String, item: AVPlayerItem)?
    private var controlObs: NSKeyValueObservation?
    private var itemObs: NSKeyValueObservation?
    /// The song whose refused URL was already resolved again once.
    private var retried: String?
    /// Where songs play from first ("YOUTUBE_MUSIC", "AUTO", "MONOCHROME"). On iPhone YouTube
    /// Music by default: Auto waits 1.5 s more for Monochrome on every song.
    var playbackSource: String = UserDefaults.standard.string(forKey: "playbackSource") ?? "YOUTUBE_MUSIC" {
        didSet {
            UserDefaults.standard.set(playbackSource, forKey: "playbackSource")
            core.setPlaybackSource(name: playbackSource)
            ready.removeAll()
            dropWarm()
            prepareNext()
        }
    }
    private var artwork: (id: String, image: MPMediaItemArtwork)?
    /// The queue in its own order, to go back to when shuffle goes off.
    private var unshuffled: [Song]?
    private var sleepTask: Task<Void, Never>?
    private var extending = false

    init(core: Core, library: Library) {
        self.core = core
        self.library = library
        // Start as soon as there is audio to play: waiting to "keep up" cost some 6 s per song,
        // as googlevideo sends the first bytes slowly.
        avPlayer.automaticallyWaitsToMinimizeStalling = false
        // At a song's end the queue waits for ended(): repeat, sleep timer and autoplay decide.
        avPlayer.actionAtItemEnd = .pause
        core.setPlaybackSource(name: playbackSource)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
        timeObserver = avPlayer.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, (note.object as? AVPlayerItem) === self.avPlayer.currentItem else { return }
                self.ended()
            }
        }
        // The spinner shows while AVPlayer waits for data, not just while the URL is found.
        controlObs = avPlayer.observe(\.timeControlStatus) { [weak self] _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.controlChanged() } }
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
        prepareNext()
    }

    /// Puts [song] at the end of the queue.
    func addToQueue(_ song: Song) {
        guard current != nil else { return play([song]) }
        queue.append(song)
        prepareNext()
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
        dropWarm()
        prepareNext()
    }

    func toggleAutoplay() {
        autoplay.toggle()
        UserDefaults.standard.set(autoplay, forKey: "autoplayOn")
        if autoplay { extendIfNeeded() }
    }

    /// Near the queue's end with autoplay on: YouTube Music's next songs join it.
    private func extendIfNeeded() {
        guard autoplay, repeatMode == .off, let cur = current, index + 2 >= queue.count, !extending else { return }
        extending = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.extending = false }
            let more = (try? await self.core.upNext(song: cur)) ?? []
            let have = Set(self.queue.map(\.id))
            let new = more.filter { !have.contains($0.id) }.prefix(20)
            guard !new.isEmpty else { return }
            self.queue.append(contentsOf: new)
            self.prepareNext()
        }
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

    // MARK: Party (guest side, as the phone's applyRemoteState)

    /// The host plays [song] at [targetMs]: change song if needed, else follow its position.
    func applyRemoteState(_ song: Song, targetMs: Int64, playing: Bool) {
        if current?.id != song.id {
            avPlayer.rate = playing ? 1 : 0
            partyDrift = .none
            if let i = queue.firstIndex(where: { $0.id == song.id }) { index = i } else { queue = [song]; index = 0 }
            load()
            return
        }
        applyRemotePosition(targetMs: targetMs, playing: playing)
    }

    /// Small drift is closed by playing 2% faster or slower (pitch kept), a big one by a seek.
    func applyRemotePosition(targetMs: Int64, playing: Bool) {
        guard current != nil, !isLoading else { return }
        if !playing {
            partyDrift = .none
            if isPlaying { avPlayer.pause(); isPlaying = false; updateNowPlaying() }
            return
        }
        if !isPlaying { avPlayer.play(); isPlaying = true }
        let now = Int64(livePosition() * 1000)
        let action = SyncEngine.driftAction(targetMs - now, playing: true, previous: partyDrift)
        switch action {
        case .seek: partyDrift = .none; avPlayer.rate = 1; localSeek(Double(targetMs) / 1000)
        case .speedUp: partyDrift = .speedUp; avPlayer.rate = SyncEngine.speedUp
        case .slowDown: partyDrift = .slowDown; avPlayer.rate = SyncEngine.slowDown
        case .none: partyDrift = .none; if avPlayer.rate != rate { avPlayer.rate = rate }
        }
        updateNowPlaying()
    }

    /// The host's queue, shown here without touching what plays.
    func mirrorRemoteQueue(_ songs: [Song], index i: Int) {
        let playing = current?.id
        queue = songs
        index = playing.flatMap { id in songs.firstIndex { $0.id == id } } ?? i
    }

    func resetPartyAdjustments() {
        partyDrift = .none
        if isPlaying { avPlayer.rate = rate }
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
        if let route = partyTransport, route(.toggle, 0) { return }
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
        if let route = partyTransport, route(.next, 0) { return }
        if index + 1 >= queue.count, autoplay, let cur = current {
            Task { [weak self] in
                guard let self else { return }
                let more = (try? await self.core.upNext(song: cur)) ?? []
                let have = Set(self.queue.map(\.id))
                let new = more.filter { !have.contains($0.id) }.prefix(20)
                if new.isEmpty { self.stopAtEnd() } else {
                    self.queue.append(contentsOf: new)
                    self.index += 1
                    self.load()
                }
            }
            return
        }
        guard index + 1 < queue.count else {
            avPlayer.pause()
            isPlaying = false
            updateNowPlaying()
            return
        }
        index += 1
        load()
    }

    private func stopAtEnd() {
        avPlayer.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func previous() {
        if let route = partyTransport, route(.previous, 0) { return }
        // As Music: back to the start first, then the song before.
        if position > 3 || index == 0 {
            seek(to: 0)
            return
        }
        index -= 1
        load()
    }

    func seek(to seconds: Double) {
        if let route = partyTransport, route(.seek, Int64(seconds * 1000)) { return }
        localSeek(seconds)
    }

    private func localSeek(_ seconds: Double) {
        avPlayer.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        position = seconds
        positionAt = .now
        updateNowPlaying()
    }

    private func load() {
        guard let song = current else { return }
        loadTask?.cancel()
        itemObs = nil
        // The next song, already queued and buffering: it takes over at once.
        if let w = warm, w.id == song.id, w.item.status != .failed, avPlayer.items().contains(where: { $0 === w.item }) {
            warm = nil
            loadTask = nil
            if avPlayer.currentItem !== w.item { avPlayer.advanceToNextItem() }
            provider = ready.removeValue(forKey: song.id)?.provider ?? provider
            library.played(song)
            w.item.audioTimePitchAlgorithm = .timeDomain
            watch(w.item, song: song)
            avPlayer.play()
            if rate != 1 { avPlayer.rate = rate }
            isPlaying = true
            isLoading = false
            controlChanged()
            updateNowPlaying()
            prepareNext()
            extendIfNeeded()
            Task { await fetchLyrics(song) }
            return
        }
        // The old song goes now: while the new URL is found nothing plays and the spinner shows.
        avPlayer.removeAllItems()
        warm = nil
        isLoading = true
        error = nil
        lyrics = nil
        lyricsLoading = true
        position = 0
        duration = Double(song.durationMs) / 1000
        updateNowPlaying()
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let item: AVPlayerItem
                // A downloaded song plays from its file, without a network.
                if let local = self.library.localFile(for: song) {
                    item = AVPlayerItem(asset: AVURLAsset(url: local))
                    self.provider = "Scaricato"
                } else {
                    let stream = try await self.stream(for: song)
                    self.provider = stream.provider
                    item = try Self.item(stream)
                }
                guard !Task.isCancelled, self.current?.id == song.id else { return }
                try? AVAudioSession.sharedInstance().setActive(true)
                self.library.played(song)
                item.audioTimePitchAlgorithm = .timeDomain
                self.watch(item, song: song)
                self.avPlayer.replaceCurrentItem(with: item)
                self.avPlayer.play()
                if self.rate != 1 { self.avPlayer.rate = self.rate }
                self.isPlaying = true
                self.controlChanged()
                self.updateNowPlaying()
                self.prepareNext()
                self.extendIfNeeded()
                await self.fetchLyrics(song)
            } catch {
                guard !Task.isCancelled else { return }
                self.isLoading = false
                self.error = error.localizedDescription
                // A song that cannot play is skipped, as on the phone.
                if self.index + 1 < self.queue.count { self.next() }
            }
        }
    }

    private func fetchLyrics(_ song: Song) async {
        lyricsLoading = true
        let l = try? await core.lyrics(song: song, source: lyricsSource)
        guard current?.id == song.id else { return }
        lyrics = l
        lyricsLoading = false
    }

    /// Lyrics from that source only (nil: all of them, the best wins), now and for the next songs.
    func setLyricsSource(_ id: String?) {
        lyricsSource = id
        UserDefaults.standard.set(id, forKey: "lyricsSource")
        guard let song = current else { return }
        lyrics = nil
        Task { await fetchLyrics(song) }
    }

    /// The position now, run on from the last read at the playing speed (frame-accurate karaoke).
    func livePosition(at date: Date = .now) -> Double {
        guard isPlaying, !isLoading else { return position }
        return min(position + date.timeIntervalSince(positionAt) * Double(rate), max(duration, position))
    }

    private func stream(for song: Song) async throws -> AudioStream {
        if let s = ready.removeValue(forKey: song.id) { return s }
        return try await core.stream(song: song)
    }

    /// A player item for a stream, sending the user agent its source asks for.
    private static func item(_ stream: AudioStream) throws -> AVPlayerItem {
        var options: [String: Any] = [:]
        if let ua = stream.userAgent {
            options["AVURLAssetHTTPHeaderFieldsKey"] = ["User-Agent": ua]
        }
        guard let url = URL(string: stream.url) else { throw URLError(.badURL) }
        return AVPlayerItem(asset: AVURLAsset(url: url, options: options))
    }

    /// The next song: its stream resolved and its first two minutes buffered while this one plays.
    private func prepareNext() {
        guard queue.indices.contains(index + 1) else { return dropWarm() }
        let next = queue[index + 1]
        guard warm?.id != next.id, library.localFile(for: next) == nil else { return }
        dropWarm()
        Task { [weak self] in
            guard let self, let s = try? await self.core.stream(song: next) else { return }
            guard self.queue.indices.contains(self.index + 1), self.queue[self.index + 1].id == next.id,
                  self.warm?.id != next.id, let item = try? Self.item(s) else { return }
            guard let cur = self.avPlayer.currentItem, self.avPlayer.canInsert(item, after: cur) else { return }
            self.ready[next.id] = s
            item.preferredForwardBufferDuration = 120
            self.warm = (next.id, item)
            self.avPlayer.insert(item, after: cur)
        }
    }

    private func dropWarm() {
        guard let w = warm else { return }
        warm = nil
        avPlayer.remove(w.item)
    }

    /// Loading follows AVPlayer: spinning while it waits for data, not once it plays or pauses.
    private func controlChanged() {
        guard avPlayer.currentItem != nil else { return }
        let waiting = avPlayer.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if isLoading != waiting {
            isLoading = waiting
            updateNowPlaying()
        }
    }

    /// A URL refused once AVPlayer opens it (expired, or the host said no): resolved again once,
    /// then the song is skipped as on the phone.
    private func watch(_ item: AVPlayerItem, song: Song) {
        itemObs = item.observe(\.status) { [weak self] item, _ in
            guard item.status == .failed else { return }
            let reason = item.error?.localizedDescription
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.avPlayer.currentItem === item, self.current?.id == song.id else { return }
                    self.core.forgetStream(songId: song.id)
                    self.ready.removeValue(forKey: song.id)
                    if self.retried != song.id, self.library.localFile(for: song) == nil {
                        self.retried = song.id
                        self.load()
                        return
                    }
                    self.isLoading = false
                    self.isPlaying = false
                    self.error = reason ?? "Il brano non si riproduce"
                    if self.index + 1 < self.queue.count { self.next() }
                }
            }
        }
    }

    private func tick(_ time: CMTime) {
        guard !isLoading else { return }
        position = time.seconds.isFinite ? time.seconds : 0
        positionAt = .now
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
