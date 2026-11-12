# LFly for computer, native (GPUI)

The Tauri window (`../app`) rewritten with GPUI: no webview, the GPU draws only when something
changed. Logic lives in `../crates/lfly-core`, shared with the Tauri app, which stays the shipped
app until this one has every screen.

    cargo run --release            # the app
    cargo run --release --example smoke   # the shared player end to end, at volume 0

Test modes: `LFLY_AUTOPLAY="query"` plays the first result, `LFLY_ROUTE=liked|library|settings|lyrics|
nowplaying|album:<id>|playlist:<id>|local:<id>|artist:<id>` opens a page (`examples/ids.rs` prints
real ids), `LFLY_NP_PANEL=lyrics|queue|none` picks the full player's panel, `LFLY_MUTE=1`,
`LFLY_NO_GLASS=1` (no glass at all, to measure it), `LFLY_GLASS_BLEED=0|1`;
`LFLY_GLASS_DEBUG`, `LFLY_GLASS_EMPTY`, `LFLY_GLASS_HIDE` for the glass check
(`tools/glass-golden`).

GPUI is `gpui-kit` 0.9.1 (GPUI snapshot `gpui-pre` 0.3.12), patched in `vendor/` for Liquid
Glass (see `vendor/PATCHES.md`). Windows check from Linux:
`LFLY_CHECK_NO_RC=1 cargo check --target x86_64-pc-windows-gnu -p gpui-pre-windows`.

## Port status

Done: the shell (sidebar with the library's playlists, top bar with the glass pills and window
controls, navigation), the theme (palette.ts, checked number for number) and the scene behind
the window, Inter in its five weights (the face the Tauri window bundles; Segoe UI Variable on
Windows, as there), the icon set, the full player bar (with its 900/620 px layouts), Home, Album,
YouTube and library playlists, Liked songs, Artist, search, the library (grid), the full player
(Now Playing) with its queue and lyrics panels, the lyrics page, the heart (saved to
favorites.json as the Tauri app saves it, read again first; Drive backup and library sync are
not told yet, as Tauri's `library_save` does), the shared player (queue, gapless preload,
expired-URL retry), Liquid Glass on wgpu (checked against Chromium) and Direct3D 11 (not run
yet).

Known differences, from what GPUI can draw: the lyrics' soft edge sweeps letter by letter (no
gradient text), their fog fades whole lines (no masks), held notes do not glow (no text shadow);
the ambient light behind the full player stands still (Tauri drifts two copies of the cover);
the queue's playing bars stand still. The lyrics animate at most 60 frames a second, four when
the window is behind another one (Tauri: the monitor's rate, 30 in the background).
Missing in the full player: the output menu's devices, the "more" menu. Lyrics settings
(size, alignment, whole lines, source line) use Tauri's defaults until Settings is ported.

To do, in this order (each screen: its Tauri commands into lfly-core, the Svelte file with its
CSS numbers, a screenshot against `mock.html` in Chromium):

1. Foundations still missing: the SelectionPill spring, the
   sidebar's narrow mode, the Copertina dinamica flowing scene.
2. Shell still missing: the right panel (queue, lyrics, devices), the context menu, toasts.
3. Search: suggestions, category chips, recent searches.
4. Library: list view, import and create, the downloads filter; Downloads, Settings, equalizer.
6. LFly Connect and library sync, Google Drive, system media controls, Discord, Spotify import,
   YouTube Music sign-in (a short-lived webview helper process: GPUI has no webview).
7. Installers (the Tauri bundler made them), and macOS (Metal glass) if it stays.

Cannot be ported: a Spicetify `user.css` imported as a theme (arbitrary CSS needs a web
engine). Its colours (`color.ini`) can.
