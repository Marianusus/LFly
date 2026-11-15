# LFly for computer, native (GPUI)

The Tauri window (`../app`) rewritten with GPUI: no webview, the GPU draws only when something
changed. Logic lives in `../crates/lfly-core`, shared with the Tauri app, which stays the shipped
app until this one has every screen.

    cargo run --release            # the app
    cargo run --release --example smoke   # the shared player end to end, at volume 0
    LFLY_DOWNLOADS_DIR=<empty dir> cargo run --release --example downloads   # one download, kept with KEEP=1
    OFFLINE=1 LFLY_DOWNLOADS_DIR=<that dir> unshare -rn target/release/examples/downloads   # it plays with no network

Test modes: `LFLY_AUTOPLAY="query"` plays the first result, `LFLY_ROUTE=liked|library|settings|lyrics|
console|nowplaying|album:<id>|playlist:<id>|local:<id>|artist:<id>` opens a page (`examples/ids.rs` prints
real ids), `LFLY_NP_PANEL=lyrics|queue|none` picks the full player's panel, `LFLY_PANEL=queue|lyrics|devices`
opens the right panel, `LFLY_MENU=1` opens the playing song's menu and a toast,
`LFLY_NP_OUTPUT=1` opens the full player's output menu, `LFLY_SIZE=WxH` sizes the window, `LFLY_ROUTE_AFTER=<route>@<seconds>` opens a second page later, `LFLY_MEDIA_TEST=1` registers the system media controls in a
test run, `LFLY_CONNECT_PORT=<port>` serves LFly Connect on loopback only, `LFLY_CONNECT_FOLLOW=<host>:<port>`
follows such a host at start, `LFLY_CONNECT_ID`/`NAME`/`SECRET` stand in for the identity, `LFLY_CONNECT_PLAY_HERE_AFTER=<s>` presses "Ascolta qui", `LFLY_CONNECT_FOLLOW_AFTER=<s>` delays the follow, `LFLY_CONNECT_ACCEPT=1` accepts a pairing (use a copied config: it trusts), `LFLY_MENU=sleep` opens the full player's menu with the sleep timer,
`LFLY_SLEEP_AFTER=<s>` sets a short sleep timer, `LFLY_DOWNLOAD_PAGE=1` (with `LFLY_DOWNLOADS_DIR`) downloads the
collection opened, `LFLY_LIBRARY_FILTER=playlists|downloads|youtube|recent` picks the library's filter, `LFLY_MIDI_TEST=<name>` opens a virtual MIDI input port of that name instead of a console (write to it with `examples/midi_send.rs`), `LFLY_MIDI_LEARN=<action>@<s>` starts learning an action, `LFLY_DJ=<s>` starts the DJ that long after launch (seed `tools/.headless/data/lfly/library/favorites.json` first), `LFLY_STANDBY=1` opens Modalità Ambient (not full screen) once a song plays, `LFLY_SPEED=1.5` plays at that speed, `LFLY_SEEK="20@120000;90@30000"` moves the song to 2:00 after 20 s (and to 0:30 after 90 s), a negative ms counts from the song's end, `LFLY_SKIP_LAST=<s>` skips to the queue's last song, `LFLY_MENU=speed` shows the speeds, `LFLY_HOVER_SWEEP="x,y0,y1@start+secs"` moves the pointer, `LFLY_SHELF_NEXT=recent@6` turns a Home shelf's page (its ›) after 6 s (`all`: every shelf),
`LFLY_SETTINGS=general|sound|lyrics|devices|account|storage|about` opens a Settings section, `LFLY_LANG=it|en|ro` picks the window's language for the run, `LFLY_MUTE=1`,
`LFLY_NO_GLASS=1` (no glass at all, to measure it), `LFLY_GLASS_BLEED=0|1`;
`LFLY_GLASS_DEBUG`, `LFLY_GLASS_EMPTY`, `LFLY_GLASS_HIDE` for the glass check
(`tools/glass-golden`).

The window's app id is `lfly-native` while the Tauri app is the shipped one (so window rules and
the desktop entry keep the two apart); it becomes `lfly` when this app replaces it.

GPUI is `gpui-kit` 0.9.1 (GPUI snapshot `gpui-pre` 0.3.12), patched in `vendor/` for Liquid
Glass (see `vendor/PATCHES.md`). Windows check from Linux:
`LFLY_CHECK_NO_RC=1 cargo check --target x86_64-pc-windows-gnu -p gpui-pre-windows`.
The whole app checks too (debug profile; `ring`'s C needs a compiler and, without mingw, two
stub headers, `assert.h` defining `assert(x)` away and `string.h` declaring `memcpy`, `memset`,
`memcmp`, `memmove`, in some folder `<inc>`):
`CC_x86_64_pc_windows_gnu=clang CFLAGS_x86_64_pc_windows_gnu="--target=x86_64-pc-windows-gnu -isystem <inc>" LFLY_CHECK_NO_RC=1 cargo check --target x86_64-pc-windows-gnu`.

## Languages

Italian, English and Romanian (Settings › Generale › Lingua; store key `native-lang`, Italian when
unset, whatever the system says). Text is written in Italian in the code and looked up when shown:
`tr("…")`, `trf("… {0} …", &[&x])`, `t("…")` for text declared in consts (`src/i18n.rs`); the rows
are in `src/i18n_table.rs`, and its test fails on a missing, unused or mismatched row. Counts
(`songs`, `minutes`: Romanian "20 de melodii"), decimals and dates have their own functions. Saved
or compared values stay Italian (EQ preset names, playlist ids). The switch refreshes the window,
so cached views draw again; text a page stored when it loaded (a collection's header) changes on
its next load. lfly-core's few messages and the DJ's lines follow through `lfly_core::lang` (the
Tauri app never sets it, so it stays Italian).

## Console

Two DJ decks and a mixer (sidebar › Console), independent of the music player's queue: songs
load from the browser (A, B, or a double click), are decoded whole into memory
(`lfly_audio::decks`, 20 minutes at most) and analysed for tempo, beat grid and key
(`lfly_core::decks`). Hot cues, loops, rolls, beat jumps, slip, scratch, SYNC, three effect
slots, a three-band kill EQ, filter, crossfader curves, a limiter and recording of the mix to
`~/Music/LFly`. The decks play on their own low-latency stream on the chosen output; starting
one pauses the music player and the other way round. Every control is a method of
`deck_model::DjDecks`. Tests: `LFLY_CONSOLE_LOAD=<s>`, `LFLY_CONSOLE_STEPS=…` (HANDOFF item 15).
Not built: headphone cueing, keylock, stems, sampler.

## Port status

Done: the shell (sidebar with the library's playlists and SelectionPill's spring, top bar with the glass pills and window
controls, navigation), the theme (palette.ts, checked number for number) and the scene behind
the window, Inter in its five weights (the face the Tauri window bundles; Segoe UI Variable on
Windows, as there), the icon set, the full player bar (with its 900/620 px layouts), Home, Album,
YouTube and library playlists, Liked songs, Artist, search, the library (grid), the full player
(Now Playing) with its queue and lyrics panels, the lyrics page, Settings (look,
general, playback with the equalizer, Automix crossfade and output, lyrics, space and privacy, about; the sections that need unported parts say
so), the keyboard shortcuts and the mouse's back/forward buttons, the system's media controls (MPRIS on
Linux, checked on a private bus; on Windows tied to the window's HWND, compile-checked only) and
Discord's "listening to", the YouTube Music account the Tauri app signed in (personal Home,
the account's playlists in the sidebar and the library's "YouTube Music" filter, likes sent to
YouTube Music, Settings > Account with "Esci"; signing in still needs the Tauri app's web view;
test runs leave the account out unless `LFLY_ACCOUNT_TEST=1`), the window's size and maximized state kept across launches (store key
`native-window`; Tauri keeps none), the phone's sleep timer in the full player's menu (15 to 90 minutes or the end of the song;
the Tauri app has none), the phone's playback speed in the same menu (0.5 to 2×, the pitch kept:
WSOLA in lfly-audio's `stretch.rs`, not kept between launches), its Modalità Ambient (standby.rs:
full screen, the clock, the cover, the line being sung, controls on a click or a move, the burn-in
walk; the pointer stays, GPUI cannot hide it), lyrics kept with the downloads (Settings > Spazio:
"Scarica anche i testi", "Scarica mancanti", read before the network; lfly-core `lyrics_store`),
hover backgrounds that fade (a GPUI patch, see vendor/PATCHES.md), covers that fade in, placeholders
while pages load, page fades, Home shelves of whole cards with ‹ › pages, the full player's cover
at 1200 px and video thumbnails whole in 16:9, the cover light drifting behind the full player and
the playing bars moving (drawn by the window over the page: the page is not drawn again for them), LFly Connect (this computer as a speaker for
phones and watches, with the pairing dialog; the devices on the network in the right panel, the
full player's output menu and Settings > Dispositivi; controlling another device, with the
"In riproduzione su" pill, "Ascolta qui" and the music moving between them; checked between two
native windows over loopback with `LFLY_CONNECT_PORT`/`LFLY_CONNECT_FOLLOW`, not yet with a phone),
downloads (the Scaricati page, the hero's download ring, the menu's
"Scarica", the sidebar entry, the library's filter, Settings > Spazio; `examples/downloads.rs`
downloads a song end to end into `LFLY_DOWNLOADS_DIR`), the heart (saved to
favorites.json as the Tauri app saves it, read again first; Drive backup and library sync are
not told yet, as Tauri's `library_save` does), the shared player (queue, gapless preload,
expired-URL retry), Liquid Glass on wgpu (checked against Chromium) and Direct3D 11 (not run
yet).

Lyrics sources (Settings > Testi > Fonti dei testi), as on the phone: lrc.red, BiniLyrics,
BetterLyrics and its Portato, PaxSenix Spotify and Musixmatch (with the listener's PaxSenix key),
LyricsPlus, Unison, YouTube captions, YouTube Music, KuGou, LRCLIB, Musixmatch and Genius, in the
listener's order (lfly-yt `lyrics/order.rs`). PaxSenix's keyless source is left out: it reads a
token out of Apple Music's web player. Songs play from YouTube Music and Monochrome raced
(Settings > Riproduzione > Da dove suona; lfly-yt `monochrome.rs`); downloads stay on YouTube Music.

Automix (Settings > Riproduzione > Automix > Mixa a tempo, off at first), as on the phone: the
last minute of the playing song and the first of the next are analysed from 150 s before the end
(lfly-core `automix.rs`: the Beat This! model on ONNX Runtime in lfly-beat, loudness and energy in
lfly-mix), the planner picks the bars and the incoming tempo, and the engine mixes the two decks
on the exit's very sample (lfly-audio `automix.rs`), the incoming song eased back to its own tempo
afterwards. Analyses are kept in the data folder (`lfly/automix`). AUTO's effects play on the
outgoing deck (lfly-mix `effects.rs`): REVERB_FADE's reverb and ROLL_DROP's loop roll. ECHO_OUT's
echo is ported too, but AUTO only picks it off the beat, and the desktop mixes on the beat or not at
all, so it is never heard yet. Unlike the phone, the incoming song eases back to its tempo only once
the reverb's tail (3.5 s) has rung out. Not ported yet: the jogwheel and the noise riser (Playlist
Mix's only), Playlist Mix and the DJ. The model is built in only where ONNX
Runtime ships a build (not for windows-gnu); elsewhere Automix falls back to a crossfade.

Known differences, from what GPUI can draw: the lyrics' soft edge sweeps letter by letter (no
gradient text), their fog fades whole lines (no masks);
the drifting cover light behind the full player moves and scales but does not turn (GPUI turns no
images); Copertina dinamica flows at a sixth of the window (Tauri: a
third) without the grain over it. The lyrics animate at most 60 frames a second, four when
the window is behind another one (Tauri: the monitor's rate, 30 in the background).
Songs played here go into history.json as in Tauri (not in test runs: `LFLY_MUTE` or
`LFLY_AUTOPLAY` set) unless Settings pauses it. The output is picked from a list in Settings
(Tauri: a `<select>`); the full player's output menu lists this computer's devices, not yet
LFly Connect's.

To do, in this order (each screen: its Tauri commands into lfly-core, the Svelte file with its
CSS numbers, a screenshot against `mock.html` in Chromium):

1. Custom themes, Spicetify and its marketplace.
2. Library sync over LFly Connect, Google Drive, YouTube Music sign-in (a short-lived webview
   helper process: GPUI has no webview).
3. Installers (the Tauri bundler made them), and macOS (Metal glass) if it stays.

Cannot be ported: a Spicetify `user.css` imported as a theme (arbitrary CSS needs a web
engine). Its colours (`color.ini`) can.
