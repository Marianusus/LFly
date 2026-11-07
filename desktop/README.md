# LFly per computer

L'app desktop di LFly per Linux, Windows e macOS. Il cuore è in Rust (player audio, YouTube Music, LFly Connect); la finestra è Tauri 2 con un'interfaccia Svelte 5.

## Avvio in sviluppo

```sh
cd desktop/app
npm install          # la prima volta
npx tauri dev        # finestra con ricarica automatica
```

Per i test:

```sh
cd desktop
cargo test --workspace                 # test offline
cargo test --workspace -- --ignored    # anche i test dal vivo (YouTube Music, uscita audio)
```

Per compilare una build con l'interfaccia incorporata, senza installer: `npx tauri build --debug --no-bundle` in `desktop/app`. Gli installer (.deb, .rpm, AppImage, .msi, .dmg) li prepara il workflow `.github/workflows/desktop.yml`: si lancia a mano da GitHub Actions, oppure parte con un tag `desktop-v*`.

## Come è fatta

| Parte | Cosa fa |
| --- | --- |
| `crates/lfly-connect` | Il protocollo LFly Connect del telefono, identico campo per campo (`connect/protocol/.../ConnectProtocol.kt`): ricerca in rete (DNS-SD), telecomando (`Controller`) e altoparlante comandabile (`Host`). I test leggono gli stessi vettori di `connect/fixtures/connect-vectors.json` usati dai test Kotlin. |
| `crates/lfly-yt` | YouTube Music, portato dal client Kotlin: Home, ricerca, artisti, album, playlist, radio, stream completi (VISIONOS, poi Invidious/Piped con circuit breaker), testi da LRCLIB. |
| `crates/lfly-audio` | Il player: stream HTTP a blocchi con cache su disco (2 GB), decodifica symphonia (AAC, FLAC, MP3, Ogg) e Opus in Rust, uscita cpal, passaggio senza pause al brano dopo, lo stesso equalizzatore del telefono. Contiene una copia corretta di `opus-decoder` in `vendor/` (vedi `PATCHES.md`). |
| `app/src-tauri` | Mette insieme i pezzi: coda (`queue.rs`), player locale (`player.rs`), comandi YouTube Music (`yt.rs`), libreria nel formato del telefono e import del backup `.lfly` (`library.rs`), tasti multimediali e MPRIS (`media.rs`), account YouTube Music (`account.rs`), download (`downloads.rs`), copertine (`covers.rs`), stato su Discord (`discord.rs`), import da Spotify (`spotify.rs`). |
| `app/src` | L'interfaccia in stile Spotify: Home, Cerca, Libreria, Artista, Album, Playlist, Testo, Coda, Dispositivi, Impostazioni. |

## Temi (come Spicetify)

- I colori hanno gli stessi nomi di Spicetify e diventano le stesse variabili CSS (`--spice-text`, `--spice-rgb-text`, …), quindi i suoi `color.ini` (anche con più schemi) e gli `user.css` che usano quelle variabili si importano direttamente.
- Impostazioni › Aspetto ha 10 temi inclusi e un editor con anteprima dal vivo: tutti i colori con controllo del contrasto, caratteri, angoli, densità, vetro, immagine di sfondo, colori presi dalla copertina, CSS personalizzato. I temi si esportano e si importano come file JSON.
- Classi stabili per il CSS: `.lfly-sidebar`, `.lfly-main`, `.lfly-panel`, `.lfly-player`, `.lfly-card`, `.lfly-row`, `.lfly-cover`, `.lfly-shelf`, `.lfly-page-title`, `.lfly-progress`, `.lfly-button-primary`, `.lfly-lyrics`.

## Funzioni

- **Account YouTube Music** (Impostazioni › Account): si accede nella vera pagina di Google, in una finestra a parte, poi si preme Fatto. Dà la Home e le playlist del tuo account, porta i "mi piace" su YouTube Music e fa fidare da soli i dispositivi con lo stesso account. Entrare o uscire vale subito, senza riavviare.
- **Download**: tasto destro › Scarica, oppure il tasto ⬇ su album e playlist. I brani diventano file interi in `~/.local/share/lfly/downloads/` e si ascoltano senza connessione.
- **Automix**: Impostazioni › Riproduzione › dissolvenza da 0 a 12 secondi tra un brano e l'altro (a 0 i brani si susseguono senza pause).
- **Discord**: con l'app Discord aperta, lo stato diventa "Sta ascoltando". Non serve il token: passa dal canale locale di Discord. Si spegne in Impostazioni › Account.
- **Da Spotify**: Libreria › Importa da Spotify, incollando il link di una playlist, di un album o di un brano. Ogni brano viene cercato su YouTube Music. Non serve l'account, ma la playlist deve essere pubblica; di una playlist Spotify mostra solo i primi 100 brani.
- **Copertine**: le scarica il core, non la finestra, perché i server di Google rispondono 429 alle richieste di WebKit. Restano in cache in `~/.cache/lfly/covers/`, fino a 300 MB.

## Con gli altri dispositivi

- **Telecomando:** il PC vede i telefoni con LFly sulla stessa rete e li comanda (Dispositivi in basso a destra). La prima volta con un altro account si conferma un codice di 4 cifre.
- **Altoparlante:** il PC si annuncia in rete, quindi un telefono può mandargli la musica e comandarla. Anche l'orologio lo vede, tramite il telefono, nell'elenco delle uscite.
- **"Ascolta qui"** riporta sul PC la musica che suona sul telefono, dal punto in cui era.
- **Party di ascolto:** non ci sono, perché usano Google Nearby, che esiste solo su Android.

## Dove salva

- Impostazioni e temi: `~/.config/lfly/state/` (su Windows `%APPDATA%\lfly`, su macOS `~/Library/Application Support/lfly`).
- Identità Connect e dispositivi fidati: `~/.config/lfly/connect.json`.
- Libreria (stesso formato del telefono): `~/.local/share/lfly/library/`.
- Account YouTube Music (cookie, leggibili solo da te): `~/.config/lfly/account.json`.
- Download: `~/.local/share/lfly/downloads/`.
- Cache audio: `~/.cache/lfly/audio/`.

## Guardare l'interfaccia senza il core

`app/mock.html` simula il core con dati finti, così l'interfaccia si può aprire in un browser. Serve solo per lo sviluppo e non entra nella build. Uso: `npx vite --port 1421`, poi apri `http://localhost:1421/mock.html#settings/appearance`. Dopo `#` puoi mettere `home`, `search`, `library`, `liked`, `lyrics` oppure `settings/<sezione>`; con `#home/theme=dracula` parte con quel tema.
