import Foundation
import NearbyConnections
import Observation
import UIKit

// LFly Party on iPhone: the phone's party/ package (PartyCode, PartyProtocol, SyncClock,
// SyncEngine, PartyManager) line by line, on Google's Nearby Connections for iOS with the
// phone's service id and strategy, so an iPhone joins an Android's party and the other way round.
// Every device streams the song itself; only the queue, the state and the clock travel.

/// Short codes to pair devices: no look-alike characters (0/O, 1/I/L).
enum PartyCode {
    static let length = 6
    static let alphabet = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
    static let endpointPrefix = "LFly-Party-"

    static func generate() -> String { String((0..<length).map { _ in alphabet.randomElement()! }) }

    static func normalize(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let cleaned = String(raw.trimmingCharacters(in: .whitespaces).uppercased().filter { alphabet.contains($0) })
        return cleaned.count == length ? cleaned : nil
    }

    static func endpointName(_ code: String) -> String { endpointPrefix + code }

    static func code(fromEndpointName name: String?) -> String? {
        guard let name, name.hasPrefix(endpointPrefix) else { return nil }
        return normalize(String(name.dropFirst(endpointPrefix.count)))
    }

    /// The QR's deep link, `lfly://party?code=K7M2QX&name=Nick`.
    static func uri(_ code: String, hostNick: String?) -> String {
        let nick = hostNick.flatMap { $0.isEmpty ? nil : "&name=" + ($0.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? $0) } ?? ""
        return "lfly://party?code=\(code)\(nick)"
    }

    static func parse(uri raw: String?) -> String? {
        guard let raw, raw.lowercased().hasPrefix("lfly://"), let c = URLComponents(string: raw) else { return nil }
        return normalize(c.queryItems?.first { $0.name.lowercased() == "code" }?.value)
    }
}

/// A song on the wire: no stream URL, every device resolves its own.
struct PartyTrack: Equatable {
    var id: String, title: String, artist: String, album: String?, durationMs: Int64, coverUrl: String?, provider: String

    init(_ song: Song) {
        let o = Library.json(song)
        id = song.id; title = song.title; artist = song.artist; album = song.album
        durationMs = song.durationMs; coverUrl = song.coverUrl; provider = o["provider"] as? String ?? "YouTube"
    }

    init(json o: [String: Any]) {
        id = o["id"] as? String ?? ""; title = o["title"] as? String ?? ""; artist = o["artist"] as? String ?? ""
        album = o["album"] as? String; durationMs = (o["duration"] as? NSNumber)?.int64Value ?? 0
        coverUrl = o["cover"] as? String; provider = o["provider"] as? String ?? "Unknown"
    }

    var json: [String: Any] {
        var o: [String: Any] = ["id": id, "title": title, "artist": artist, "duration": durationMs, "provider": provider]
        if let album { o["album"] = album }
        if let coverUrl { o["cover"] = coverUrl }
        return o
    }

    /// The phone's Track JSON, so the core resolves it like any other song. Peer input: only
    /// web addresses are taken as covers.
    var song: Song? {
        var o: [String: Any] = ["id": id, "title": title, "artist": artist, "durationMs": durationMs, "provider": provider]
        if let album { o["album"] = album }
        if let c = coverUrl, c.lowercased().hasPrefix("http") { o["coverUrl"] = c }
        return Library.song(o)
    }
}

enum PartyMessage {
    case hello(nick: String)
    case welcome(hostNick: String, allowControl: Bool, allowQueue: Bool)
    case state(track: PartyTrack?, positionMs: Int64, playing: Bool, startsAtHostMs: Int64, seq: Int64)
    case queue(tracks: [PartyTrack], index: Int)
    case ping(t0: Int64)
    case pong(t0: Int64, t1: Int64)
    case control(action: String, positionMs: Int64, track: PartyTrack?)
    case perms(allowControl: Bool, allowQueue: Bool)
    case bye(reason: String?)
}

/// The phone's JSON wire format, one message per BYTES payload of at most 30 KiB.
enum PartyProtocol {
    static let version = 1
    static let maxBytes = 30 * 1024

    static func encode(_ m: PartyMessage) -> Data {
        var o: [String: Any]
        switch m {
        case .hello(let nick): o = ["t": "hello", "nick": nick, "v": version]
        case .welcome(let n, let c, let q): o = ["t": "welcome", "nick": n, "allowControl": c, "allowQueue": q]
        case .state(let track, let pos, let playing, let startsAt, let seq):
            o = ["t": "state", "track": track?.json ?? NSNull(), "pos": pos, "playing": playing, "startsAt": startsAt, "seq": seq]
        case .queue(let tracks, let index): o = ["t": "queue", "tracks": tracks.map(\.json), "index": index]
        case .ping(let t0): o = ["t": "ping", "t0": t0]
        case .pong(let t0, let t1): o = ["t": "pong", "t0": t0, "t1": t1]
        case .control(let action, let pos, let track):
            o = ["t": "control", "action": action, "pos": pos]
            if let track { o["track"] = track.json }
        case .perms(let c, let q): o = ["t": "perms", "allowControl": c, "allowQueue": q]
        case .bye(let reason):
            o = ["t": "bye"]
            if let reason { o["reason"] = reason }
        }
        return (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
    }

    static func decode(_ data: Data) -> PartyMessage? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func num(_ k: String) -> Int64 { (o[k] as? NSNumber)?.int64Value ?? 0 }
        func bool(_ k: String) -> Bool { o[k] as? Bool ?? false }
        func track(_ k: String) -> PartyTrack? { (o[k] as? [String: Any]).map(PartyTrack.init(json:)) }
        switch o["t"] as? String {
        case "hello": return .hello(nick: o["nick"] as? String ?? "?")
        case "welcome": return .welcome(hostNick: o["nick"] as? String ?? "?", allowControl: bool("allowControl"), allowQueue: bool("allowQueue"))
        case "state": return .state(track: track("track"), positionMs: num("pos"), playing: bool("playing"), startsAtHostMs: num("startsAt"), seq: num("seq"))
        case "queue": return .queue(tracks: ((o["tracks"] as? [[String: Any]]) ?? []).map(PartyTrack.init(json:)), index: Int(num("index")))
        case "ping": return .ping(t0: num("t0"))
        case "pong": return .pong(t0: num("t0"), t1: num("t1"))
        case "control":
            guard let action = o["action"] as? String else { return nil }
            return .control(action: action, positionMs: num("pos"), track: track("track"))
        case "perms": return .perms(allowControl: bool("allowControl"), allowQueue: bool("allowQueue"))
        case "bye": return .bye(reason: o["reason"] as? String)
        default: return nil
        }
    }

    /// The queue in one payload: a window round the playing song, narrower until it fits.
    static func fitQueue(_ tracks: [PartyTrack], index: Int) -> PartyMessage {
        guard !tracks.isEmpty else { return .queue(tracks: [], index: -1) }
        let i = min(max(index, 0), tracks.count - 1)
        for radius in [40, 20, 8, 3, 0] {
            let start = max(i - radius, 0), end = min(i + radius, tracks.count - 1) + 1
            let m = PartyMessage.queue(tracks: Array(tracks[start..<end]), index: i - start)
            if encode(m).count <= maxBytes || end - start <= 1 { return m }
        }
        return .queue(tracks: [tracks[i]], index: 0)
    }
}

/// The host's clock from NTP-style rounds (guest side): `hostNow = localNow + offset`, the median
/// of the last 12 rounds under 2 s.
struct SyncClock {
    private var offsets: [Int64] = []

    mutating func addRound(localSend: Int64, hostRecv: Int64, localRecv: Int64) -> Int64? {
        let rtt = localRecv - localSend
        guard rtt >= 0, rtt <= 2_000 else { return nil }
        offsets.append(hostRecv - (localSend + rtt / 2))
        if offsets.count > 12 { offsets.removeFirst(offsets.count - 12) }
        return offset
    }

    var offset: Int64? { offsets.isEmpty ? nil : offsets.sorted()[offsets.count / 2] }
    var isReady: Bool { !offsets.isEmpty }
    mutating func reset() { offsets.removeAll() }
}

/// How a guest follows the host (the phone's SyncEngine).
enum SyncEngine {
    static let smallDriftMs: Int64 = 40, enterSpeedMs: Int64 = 150, driftSeekMs: Int64 = 500
    static let speedUp: Float = 1.02, slowDown: Float = 0.98, nudgeRangeMs: Int64 = 500

    enum DriftAction { case none, speedUp, slowDown, seek }

    static func targetPosition(statePositionMs: Int64, startsAtHostMs: Int64, playing: Bool, offsetMs: Int64, localNowMs: Int64, nudgeMs: Int64) -> Int64 {
        guard playing, startsAtHostMs > 0 else { return max(statePositionMs - nudgeMs, 0) }
        let elapsed = max(localNowMs + offsetMs - startsAtHostMs, 0)
        return max(statePositionMs + elapsed - nudgeMs, 0)
    }

    /// With hysteresis: a nudge starts above 150 ms and stops only under 40 ms.
    static func driftAction(_ drift: Int64, playing: Bool, previous: DriftAction) -> DriftAction {
        guard playing else { return .none }
        let m = abs(drift)
        if m >= driftSeekMs { return .seek }
        if m >= enterSpeedMs { return drift > 0 ? .speedUp : .slowDown }
        if m <= smallDriftMs { return .none }
        return previous == .seek ? .none : previous
    }
}

/// A device's monotonic clock in ms (Android's elapsedRealtime).
func partyNow() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

@MainActor
@Observable
final class PartyManager {
    enum Role { case idle, host, guest }
    struct Peer: Identifiable { let id: String; var nick: String }
    struct Found: Identifiable { let id: String; let code: String }

    private(set) var role: Role = .idle
    private(set) var code: String?
    let selfNick: String = UIDevice.current.name
    private(set) var hostNick: String?
    private(set) var guests: [Peer] = []
    private(set) var found: [Found] = []
    private(set) var allowGuestControl = true
    private(set) var allowQueueAdd = true
    private(set) var syncReady = false
    private(set) var busy = false
    var error: String?
    var nudgeMs: Int64 = Int64(UserDefaults.standard.integer(forKey: "partyNudge")) {
        didSet { UserDefaults.standard.set(Int(nudgeMs), forKey: "partyNudge") }
    }
    var isActive: Bool { role != .idle }
    /// A guest connected to its host (not just looking).
    var inParty: Bool { role == .guest && hostEndpoint != nil }

    static let serviceID = "org.sonora.android.PARTY"
    private let manager = ConnectionManager(serviceID: PartyManager.serviceID, strategy: .star)
    private var advertiser: Advertiser?
    private var discoverer: Discoverer?
    private let bridge = Bridge()
    private let player: Player

    private var hostEndpoint: String?
    private var clock = SyncClock()
    private var seq: Int64 = 0
    private var lastHostPlaying = false
    private var pendingState: PartyMessage?
    private var lastRawState: (track: PartyTrack?, pos: Int64, playing: Bool, startsAt: Int64)?
    private var hostLoop: Task<Void, Never>?
    private var pingLoop: Task<Void, Never>?
    private var alignTask: Task<Void, Never>?

    init(player: Player) {
        self.player = player
        bridge.owner = self
        manager.delegate = bridge
    }

    // MARK: Public

    func startHosting() {
        guard role == .idle else { return }
        let c = PartyCode.generate()
        role = .host; code = c; busy = true; error = nil; guests = []
        let a = Advertiser(connectionManager: manager)
        a.delegate = bridge
        advertiser = a
        a.startAdvertising(using: Data(PartyCode.endpointName(c).utf8)) { [weak self] err in
            Task { @MainActor in
                guard let self else { return }
                if let err { self.reset(err.localizedDescription) } else { self.busy = false; self.startHostLoop() }
            }
        }
    }

    func startDiscovery() {
        guard role == .idle else { return }
        role = .guest; busy = true; error = nil; found = []
        let d = Discoverer(connectionManager: manager)
        d.delegate = bridge
        discoverer = d
        d.startDiscovery { [weak self] err in
            Task { @MainActor in
                guard let self else { return }
                if let err { self.reset(err.localizedDescription) } else { self.busy = false }
            }
        }
    }

    func stopDiscovery() {
        guard role == .guest else { return }
        discoverer?.stopDiscovery()
        reset(nil)
    }

    func join(_ endpoint: String) {
        if role != .guest { startDiscovery() }
        busy = true; error = nil
        discoverer?.requestConnection(to: endpoint, using: Data("LFly-Guest-\(selfNick)".utf8)) { [weak self] err in
            guard let err else { return }
            Task { @MainActor in self?.busy = false; self?.error = err.localizedDescription }
        }
    }

    /// Joins the party with that code (typed or from a QR), waiting up to 20 s to see it.
    @discardableResult
    func join(code raw: String) -> Bool {
        guard let c = PartyCode.normalize(raw) ?? PartyCode.parse(uri: raw) else { return false }
        if let f = found.first(where: { $0.code == c }) { join(f.id); return true }
        if role != .guest { startDiscovery() }
        Task {
            for _ in 0..<40 {
                if let f = found.first(where: { $0.code == c }) { join(f.id); return }
                try? await Task.sleep(for: .milliseconds(500))
            }
            error = "Nessun Party con questo codice"
        }
        return true
    }

    func leave() {
        if role == .host { broadcast(.bye(reason: "host_left")) } else if let h = hostEndpoint { send(h, .bye(reason: "guest_left")) }
        advertiser?.stopAdvertising()
        discoverer?.stopDiscovery()
        let all = guests.map(\.id) + [hostEndpoint].compactMap { $0 }
        for e in all { manager.disconnect(from: e) }
        reset(nil)
    }

    func setAllowGuestControl(_ v: Bool) { allowGuestControl = v; broadcast(.perms(allowControl: allowGuestControl, allowQueue: allowQueueAdd)) }
    func setAllowQueueAdd(_ v: Bool) { allowQueueAdd = v; broadcast(.perms(allowControl: allowGuestControl, allowQueue: allowQueueAdd)) }

    /// Guest: asks the host to queue a song (when it allows it).
    func guestAdd(_ song: Song, next: Bool) {
        guard let h = hostEndpoint, allowQueueAdd else { return }
        send(h, .control(action: next ? "queue_next" : "queue_add", positionMs: 0, track: PartyTrack(song)))
    }

    // MARK: Nearby events (from the bridge, on the main queue)

    fileprivate func connectionRequest(_ endpoint: String, accept: (Bool) -> Void) { accept(true) }
    fileprivate func verification(_ endpoint: String, accept: (Bool) -> Void) { accept(true) }

    fileprivate func endpointFound(_ endpoint: String, info: Data) {
        guard let c = PartyCode.code(fromEndpointName: String(data: info, encoding: .utf8)) else { return }
        found.removeAll { $0.id == endpoint }
        found.append(Found(id: endpoint, code: c))
    }

    fileprivate func endpointLost(_ endpoint: String) { found.removeAll { $0.id == endpoint } }

    fileprivate func stateChanged(_ endpoint: String, _ state: ConnectionState) {
        switch state {
        case .connected:
            busy = false
            if role == .guest {
                hostEndpoint = endpoint
                discoverer?.stopDiscovery()
                found = []
                send(endpoint, .hello(nick: selfNick))
                startGuestLoops()
            }
        case .disconnected:
            if role == .host { guests.removeAll { $0.id == endpoint } } else if endpoint == hostEndpoint { reset("La connessione con l'host si è persa") }
        case .rejected:
            busy = false; error = "Connessione rifiutata"
        case .connecting:
            break
        @unknown default:
            break
        }
    }

    fileprivate func received(_ data: Data, from endpoint: String) {
        guard let m = PartyProtocol.decode(data) else { return }
        if role == .host { hostHandle(endpoint, m) } else if role == .guest { guestHandle(endpoint, m) }
    }

    // MARK: Host

    private func hostHandle(_ endpoint: String, _ m: PartyMessage) {
        // A peer introduces itself before anything else is honoured.
        if case .hello = m {} else if !guests.contains(where: { $0.id == endpoint }) { return }
        switch m {
        case .hello(let nick):
            if let i = guests.firstIndex(where: { $0.id == endpoint }) { guests[i].nick = nick } else { guests.append(Peer(id: endpoint, nick: nick)) }
            send(endpoint, .welcome(hostNick: selfNick, allowControl: allowGuestControl, allowQueue: allowQueueAdd))
            sendCurrentState(endpoint)
        case .ping(let t0):
            send(endpoint, .pong(t0: t0, t1: partyNow()))
        case .control(let action, let pos, let track):
            switch action {
            case "next" where allowGuestControl: player.next()
            case "previous" where allowGuestControl: player.previous()
            case "play" where allowGuestControl: if !player.isPlaying { player.toggle() }
            case "pause" where allowGuestControl: if player.isPlaying { player.toggle() }
            case "seek" where allowGuestControl: player.seek(to: Double(pos) / 1000)
            case "queue_add" where allowQueueAdd: if let s = track?.song { player.addToQueue(s) }
            case "queue_next" where allowQueueAdd: if let s = track?.song { player.playNext(s) }
            default: break
            }
        default: break
        }
    }

    private func startHostLoop() {
        hostLoop?.cancel()
        hostLoop = Task { [weak self] in
            var lastTrack: String?, lastPlaying = false, lastIndex = Int.min, lastSig = ""
            var lastPosition: Int64 = 0, lastTick = partyNow(), lastBeat = partyNow()
            while !Task.isCancelled, let self {
                let p = self.player
                let tick = partyNow()
                let live = Int64(p.livePosition() * 1000)
                let expected = lastPosition + (lastPlaying ? tick - lastTick : 0)
                let seeked = abs(live - expected) > 1_200
                let sig = "\(p.queue.count):\(p.index):\(p.queue.first?.id ?? ""):\(p.queue.last?.id ?? "")"
                let changed = p.current?.id != lastTrack || p.isPlaying != lastPlaying || p.index != lastIndex || sig != lastSig || seeked
                if changed || tick - lastBeat >= 1_500 {
                    self.seq += 1
                    self.lastHostPlaying = p.isPlaying
                    self.broadcast(.state(track: p.current.map(PartyTrack.init), positionMs: live, playing: p.isPlaying, startsAtHostMs: tick, seq: self.seq))
                    if changed || sig != lastSig { self.broadcastQueue() }
                    lastBeat = tick; lastTrack = p.current?.id; lastPlaying = p.isPlaying; lastIndex = p.index; lastSig = sig
                    lastPosition = live; lastTick = tick
                }
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
    }

    private func sendCurrentState(_ endpoint: String) {
        seq += 1
        send(endpoint, .state(track: player.current.map(PartyTrack.init), positionMs: Int64(player.livePosition() * 1000), playing: player.isPlaying, startsAtHostMs: partyNow(), seq: seq))
        let tracks = player.queue.map(PartyTrack.init)
        if !tracks.isEmpty { send(endpoint, PartyProtocol.fitQueue(tracks, index: player.index)) }
    }

    private func broadcastQueue() {
        let tracks = player.queue.map(PartyTrack.init)
        if !tracks.isEmpty { broadcast(PartyProtocol.fitQueue(tracks, index: player.index)) }
    }

    // MARK: Guest

    private func guestHandle(_ endpoint: String, _ m: PartyMessage) {
        if let h = hostEndpoint, endpoint != h { return }
        switch m {
        case .welcome(let n, let c, let q): hostNick = n; allowGuestControl = c; allowQueueAdd = q
        case .perms(let c, let q): allowGuestControl = c; allowQueueAdd = q
        case .state: applyState(m)
        case .queue(let tracks, let index):
            let songs = tracks.compactMap(\.song)
            if !songs.isEmpty { player.mirrorRemoteQueue(songs, index: min(max(index, 0), songs.count - 1)) }
        case .pong(let t0, let t1):
            if let offset = clock.addRound(localSend: t0, hostRecv: t1, localRecv: partyNow()) {
                syncReady = true
                if let pending = pendingState {
                    pendingState = nil
                    apply(pending, offset: offset)
                }
            }
        case .bye: reset("L'host ha chiuso il Party")
        default: break
        }
    }

    private func applyState(_ m: PartyMessage) {
        guard case .state(let track, let pos, let playing, let startsAt, _) = m else { return }
        lastHostPlaying = playing
        lastRawState = (track, pos, playing, startsAt)
        // Never act before the clock offset is known: the two clocks may differ by hours.
        guard let offset = clock.offset else {
            pendingState = m
            if let h = hostEndpoint { send(h, .ping(t0: partyNow())) }
            return
        }
        pendingState = nil
        apply(m, offset: offset)
    }

    private func apply(_ m: PartyMessage, offset: Int64) {
        guard case .state(let track, let pos, let playing, let startsAt, _) = m, let song = track?.song else { return }
        let before = player.current?.id
        let target = SyncEngine.targetPosition(statePositionMs: pos, startsAtHostMs: startsAt, playing: playing, offsetMs: offset, localNowMs: partyNow(), nudgeMs: nudgeMs)
        player.applyRemoteState(song, targetMs: target, playing: playing)
        if song.id != before { alignBurst() }
    }

    /// After a song change the guest resolves its own stream: the host's state is applied again
    /// a few times so it lands on the host's position as soon as the stream is ready.
    private func alignBurst() {
        alignTask?.cancel()
        alignTask = Task { [weak self] in
            for _ in 0..<4 {
                try? await Task.sleep(for: .milliseconds(400))
                guard let self, let raw = self.lastRawState, let offset = self.clock.offset else { return }
                let target = SyncEngine.targetPosition(statePositionMs: raw.pos, startsAtHostMs: raw.startsAt, playing: raw.playing, offsetMs: offset, localNowMs: partyNow(), nudgeMs: self.nudgeMs)
                self.player.applyRemotePosition(targetMs: target, playing: raw.playing)
            }
        }
    }

    private func startGuestLoops() {
        player.partyTransport = { [weak self] action, positionMs in
            guard let self, self.role == .guest, let h = self.hostEndpoint else { return false }
            switch action {
            case .toggle: self.send(h, .control(action: self.lastHostPlaying ? "pause" : "play", positionMs: 0, track: nil))
            case .next: if self.allowGuestControl { self.send(h, .control(action: "next", positionMs: 0, track: nil)) }
            case .previous: if self.allowGuestControl { self.send(h, .control(action: "previous", positionMs: 0, track: nil)) }
            case .seek: if self.allowGuestControl { self.send(h, .control(action: "seek", positionMs: positionMs, track: nil)) }
            }
            return true
        }
        pingLoop?.cancel()
        pingLoop = Task { [weak self] in
            var attempts = 0
            while !Task.isCancelled, let self {
                if let h = self.hostEndpoint { self.send(h, .ping(t0: partyNow())) }
                attempts += 1
                // A burst until the offset is known, then every 5 s.
                try? await Task.sleep(for: .milliseconds(!self.clock.isReady && attempts <= 20 ? 300 : 5_000))
            }
        }
    }

    // MARK: Wire

    private func broadcast(_ m: PartyMessage) {
        guard role == .host, !guests.isEmpty else { return }
        let data = PartyProtocol.encode(m)
        guard data.count <= PartyProtocol.maxBytes else { return }
        _ = manager.send(data, to: guests.map(\.id))
    }

    private func send(_ endpoint: String, _ m: PartyMessage) {
        let data = PartyProtocol.encode(m)
        guard data.count <= PartyProtocol.maxBytes else { return }
        _ = manager.send(data, to: [endpoint])
    }

    private func reset(_ reason: String?) {
        hostLoop?.cancel(); pingLoop?.cancel(); alignTask?.cancel()
        hostLoop = nil; pingLoop = nil; alignTask = nil
        pendingState = nil; lastRawState = nil; hostEndpoint = nil
        guests = []; found = []; clock.reset(); syncReady = false
        player.partyTransport = nil
        player.resetPartyAdjustments()
        advertiser = nil; discoverer = nil
        role = .idle; code = nil; hostNick = nil; busy = false
        error = reason
    }
}

/// Nearby's delegates (it calls them on the main queue) handed to the manager.
private final class Bridge: NSObject, AdvertiserDelegate, DiscovererDelegate, ConnectionManagerDelegate {
    weak var owner: PartyManager?

    private func on(_ f: @escaping @MainActor (PartyManager) -> Void) {
        MainActor.assumeIsolated { if let o = owner { f(o) } }
    }

    func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID, with context: Data, connectionRequestHandler: @escaping (Bool) -> Void) {
        on { $0.connectionRequest(endpointID, accept: connectionRequestHandler) }
    }

    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        on { $0.endpointFound(endpointID, info: context) }
    }

    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        on { $0.endpointLost(endpointID) }
    }

    func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String, from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        on { $0.verification(endpointID, accept: verificationHandler) }
    }

    func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data, withID payloadID: PayloadID, from endpointID: EndpointID) {
        on { $0.received(data, from: endpointID) }
    }

    func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream, withID payloadID: PayloadID, from endpointID: EndpointID, cancellationToken token: CancellationToken) {}

    func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID, from endpointID: EndpointID, at localURL: URL, withName name: String, cancellationToken token: CancellationToken) {}

    func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate, from endpointID: EndpointID, forPayload payloadID: PayloadID) {}

    func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState, for endpointID: EndpointID) {
        on { $0.stateChanged(endpointID, state) }
    }
}
