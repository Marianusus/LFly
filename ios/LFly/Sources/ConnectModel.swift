import Foundation
import Observation
import SwiftUI
import UIKit

/// LFly Connect for the screens: the devices in sight, the one followed as a remote and what
/// plays there, the pairing code. The core does the work (lfly-connect, libsync).
@MainActor
@Observable
final class ConnectModel {
    private(set) var peers: [Peer] = []
    private(set) var following: Peer?
    private(set) var remote: RemoteState?
    /// Shown while the followed device's owner has to accept this iPhone.
    var pairing: (device: String, code: String)?
    var refusedBy: String?
    private(set) var lastSync: Date?

    private var connect: Connect?
    private let library: Library

    init(library: Library) {
        self.library = library
        let defaults = UserDefaults.standard
        let id = defaults.string(forKey: "connectId") ?? {
            let new = "ios-" + UUID().uuidString.lowercased()
            defaults.set(new, forKey: "connectId")
            return new
        }()
        let state = URL.applicationSupportDirectory
        try? FileManager.default.createDirectory(at: state, withIntermediateDirectories: true)
        connect = try? Connect(
            id: id,
            name: UIDevice.current.name,
            libraryDir: library.folder.path,
            stateDir: state.path,
            listener: Listener(model: self)
        )
        library.onEdit = { [weak self] in self?.connect?.libraryEdited() }
    }

    /// The account's secret: devices of the same account trust each other and share the library.
    func setAccount(_ secret: String?) {
        connect?.setAccount(secret: secret)
    }

    func follow(_ peer: Peer) { connect?.follow(id: peer.id) }
    func disconnect() {
        connect?.disconnect()
        remote = nil
    }

    func command(_ action: String, _ value: Int64 = 0) { connect?.command(action: action, value: value) }

    /// Position on the followed device now, in seconds.
    var remotePosition: Double { Double(connect?.positionNow() ?? 0) / 1000 }

    func syncNow() async -> UInt32 {
        let n = await connect?.syncNow() ?? 0
        if n > 0 { lastSync = .now }
        return n
    }

    // Called by the listener, on the main actor.
    fileprivate func apply(_ change: @MainActor (ConnectModel) -> Void) { change(self) }
}

/// The core's callbacks arrive on its threads; each one hops to the main actor.
private final class Listener: ConnectListener, @unchecked Sendable {
    weak var model: ConnectModel?

    init(model: ConnectModel) { self.model = model }

    private func main(_ f: @escaping @MainActor (ConnectModel) -> Void) {
        Task { @MainActor [weak self] in
            if let m = self?.model { f(m) }
        }
    }

    func devices(peers: [Peer]) { main { $0.peersChanged(peers) } }
    func pairing(deviceName: String, code: String) { main { $0.pairing = (deviceName, code) } }
    func refused(deviceName: String) { main { $0.pairing = nil; $0.refusedBy = deviceName } }
    func following(peer: Peer?) { main { $0.followingChanged(peer) } }
    func state(state: RemoteState) { main { $0.remoteChanged(state) } }
    func libraryChanged() { main { m in m.libraryChanged() } }
}

extension ConnectModel {
    fileprivate func peersChanged(_ list: [Peer]) { peers = list }
    fileprivate func followingChanged(_ p: Peer?) {
        following = p
        if p != nil { pairing = nil } else { remote = nil }
    }
    fileprivate func remoteChanged(_ s: RemoteState) { remote = s }
    fileprivate func libraryChanged() {
        library.reload()
        lastSync = .now
    }
}

/// The devices sheet: who is in sight; a tap follows one as a remote.
struct DevicesView: View {
    @Environment(ConnectModel.self) private var connect
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var connect = connect
        NavigationStack {
            List {
                if let f = connect.following {
                    Section("Controlli") { RemoteView(peer: f) }
                }
                Section {
                    if connect.peers.isEmpty {
                        Label("Cerco telefoni e computer con LFly sulla stessa Wi-Fi…", systemImage: "wifi")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(connect.peers, id: \.id) { p in
                        Button {
                            if connect.following?.id == p.id { connect.disconnect() } else { connect.follow(p) }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: p.kind == "phone" ? "iphone" : p.kind == "watch" ? "applewatch" : "laptopcomputer")
                                    .frame(width: 28)
                                VStack(alignment: .leading) {
                                    Text(p.name)
                                    if p.sameAccount {
                                        Text("Stesso account: la libreria è la stessa").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                if connect.following?.id == p.id { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("Dispositivi")
                } footer: {
                    Text("Tocca un dispositivo per comandarlo da qui. Con lo stesso account YouTube Music, preferiti, playlist e cronologia restano uguali da soli.")
                }
            }
            .navigationTitle("LFly Connect")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fine") { dismiss() } } }
            .alert("Accetta su \(connect.pairing?.device ?? "")", isPresented: Binding(get: { connect.pairing != nil }, set: { if !$0 { connect.pairing = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Controlla che sull'altro dispositivo compaia lo stesso codice: \(connect.pairing?.code ?? "")")
            }
            .alert("\(connect.refusedBy ?? "") ha rifiutato", isPresented: Binding(get: { connect.refusedBy != nil }, set: { if !$0 { connect.refusedBy = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
    }
}

/// The followed device's song and controls.
struct RemoteView: View {
    @Environment(ConnectModel.self) private var connect
    let peer: Peer

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Artwork(url: connect.remote?.track?.coverUrl, radius: 8).frame(width: 56, height: 56)
                VStack(alignment: .leading) {
                    Text(connect.remote?.track?.title ?? "Niente in riproduzione").font(.headline).lineLimit(1)
                    Text(connect.remote?.track?.artist ?? peer.name).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
            }
            HStack(spacing: 40) {
                Button { connect.command("previous") } label: { Image(systemName: "backward.fill") }
                    .accessibilityLabel("Brano precedente")
                Button { connect.command("toggle") } label: {
                    Image(systemName: connect.remote?.playing == true ? "pause.fill" : "play.fill").font(.largeTitle)
                }
                .accessibilityLabel(connect.remote?.playing == true ? "Pausa" : "Riproduci")
                Button { connect.command("next") } label: { Image(systemName: "forward.fill") }
                    .accessibilityLabel("Brano successivo")
            }
            .font(.title2)
            .buttonStyle(.plain)
            if let v = connect.remote?.volume {
                HStack {
                    Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                    Slider(value: Binding(get: { Double(v) }, set: { connect.command("volume", Int64($0)) }), in: 0...100)
                    Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 6)
    }
}
