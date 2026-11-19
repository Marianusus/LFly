import CoreImage.CIFilterBuiltins
import SwiftUI

/// LFly Party, as the phone's PartyBottomSheet: create one, or join one nearby (list or code);
/// the host shows its code and QR, its guests and what they may do; both tune the delay.
struct PartyView: View {
    @Environment(PartyManager.self) private var party
    @State private var typed = ""

    var body: some View {
        List {
            if party.role == .host { hostSections } else if party.inParty { guestSections } else { startSections }
            if party.isActive { nudgeSection }
        }
        .navigationTitle("LFly Party")
        .navigationBarTitleDisplayMode(.inline)
        .alert("LFly Party", isPresented: Binding(get: { party.error != nil }, set: { if !$0 { party.error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(party.error ?? "")
        }
    }

    // MARK: Not in a party (or still looking)

    @ViewBuilder private var startSections: some View {
        Section {
            Button { party.startHosting() } label: {
                Label("Crea un Party", systemImage: "party.popper.fill")
            }
            .disabled(party.role != .idle)
        } footer: {
            Text("Chi è vicino si unisce e ascolta la stessa musica, in sincrono. Ogni telefono scarica il brano da sé.")
        }
        Section("Unisciti con un codice") {
            HStack {
                TextField("Codice", text: $typed)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.body.monospaced())
                    .onSubmit(joinTyped)
                Button("Unisciti", action: joinTyped)
                    .disabled(PartyCode.normalize(typed) == nil)
            }
        }
        Section {
            if party.role == .guest {
                if party.found.isEmpty {
                    Label("Cerco Party vicini…", systemImage: "dot.radiowaves.left.and.right")
                        .foregroundStyle(.secondary)
                }
                ForEach(party.found) { f in
                    Button { party.join(f.id) } label: {
                        HStack {
                            Label(f.code, systemImage: "person.3.fill").font(.body.monospaced())
                            Spacer()
                            if party.busy { ProgressView() }
                        }
                    }
                }
                Button("Smetti di cercare", role: .cancel) { party.stopDiscovery() }
            } else {
                Button { party.startDiscovery() } label: {
                    Label("Cerca Party vicini", systemImage: "dot.radiowaves.left.and.right")
                }
            }
        } header: {
            Text("Vicino a te")
        }
    }

    private func joinTyped() {
        if party.join(code: typed) { typed = "" } else { party.error = "Codice non valido" }
    }

    // MARK: Host

    @ViewBuilder private var hostSections: some View {
        Section {
            VStack(spacing: 12) {
                if let code = party.code {
                    if let qr = Self.qr(PartyCode.uri(code, hostNick: party.selfNick)) {
                        Image(uiImage: qr)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 180, height: 180)
                            .padding(10)
                            .background(.white, in: .rect(cornerRadius: 12))
                            .accessibilityLabel("Codice QR del Party")
                    }
                    Text(code)
                        .font(.largeTitle.monospaced().bold())
                        .tracking(4)
                        .textSelection(.enabled)
                }
                Text(party.busy ? "Avvio…" : party.guests.isEmpty ? "In attesa degli ospiti" : "Live")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        Section("Ospiti") {
            if party.guests.isEmpty {
                Text("Nessuno ancora: fai inquadrare il QR o dai il codice.").foregroundStyle(.secondary)
            }
            ForEach(party.guests) { g in Label(g.nick, systemImage: "iphone") }
        }
        Section("Permessi") {
            Toggle("Gli ospiti possono comandare", isOn: Binding(get: { party.allowGuestControl }, set: { party.setAllowGuestControl($0) }))
            Toggle("Gli ospiti possono aggiungere brani", isOn: Binding(get: { party.allowQueueAdd }, set: { party.setAllowQueueAdd($0) }))
        }
        Section {
            Button("Chiudi il Party", role: .destructive) { party.leave() }
        }
    }

    // MARK: Guest

    @ViewBuilder private var guestSections: some View {
        Section {
            HStack(spacing: 12) {
                Image(systemName: "person.3.fill").font(.title2).foregroundStyle(.tint)
                VStack(alignment: .leading) {
                    Text(party.hostNick ?? "Party").font(.headline)
                    Text(party.syncReady ? "In sincrono" : "Sincronizzo…").font(.subheadline).foregroundStyle(.secondary)
                }
            }
        } footer: {
            Text(party.allowGuestControl ? "I comandi di riproduzione vanno all'host." : "Solo l'host comanda la riproduzione.")
        }
        Section {
            Button("Esci dal Party", role: .destructive) { party.leave() }
        }
    }

    // MARK: Delay

    private var nudgeSection: some View {
        @Bindable var party = party
        let range = Double(SyncEngine.nudgeRangeMs)
        return Section {
            VStack(alignment: .leading) {
                Text("Ritardo: \(party.nudgeMs) ms").monospacedDigit()
                Slider(value: Binding(get: { Double(party.nudgeMs) }, set: { party.nudgeMs = Int64($0.rounded()) }), in: -range...range, step: 10)
            }
        } header: {
            Text("Ritardo cuffie")
        } footer: {
            Text("Se questo telefono suona in anticipo o in ritardo (cuffie Bluetooth, casse), spostalo finché non coincide.")
        }
    }

    private static func qr(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage, let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
