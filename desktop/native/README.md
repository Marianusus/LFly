# LFly for computer, native (GPUI)

The Tauri window (`../app`) rewritten with GPUI: no webview, the GPU draws only when something
changed. Logic lives in `../crates/lfly-core`, shared with the Tauri app, which stays the shipped
app until this one has every screen.

    cargo run --release            # the app
    cargo run --release --example smoke   # the shared player end to end, at volume 0

Test modes: `LFLY_AUTOPLAY="query"` plays the first result, `LFLY_MUTE=1`, `LFLY_GLASS_BLEED=0|1`;
`LFLY_GLASS_DEBUG`, `LFLY_GLASS_EMPTY`, `LFLY_GLASS_HIDE` for the glass check
(`tools/glass-golden`).

GPUI is `gpui-kit` 0.9.1 (GPUI snapshot `gpui-pre` 0.3.12), patched in `vendor/` for Liquid
Glass (see `vendor/PATCHES.md`). Windows check from Linux:
`LFLY_CHECK_NO_RC=1 cargo check --target x86_64-pc-windows-gnu -p gpui-pre-windows`.

## Port status

Done: the shell (sidebar with the library's playlists, top bar with the glass pills and window
controls, navigation), the theme (palette.ts, checked number for number) and the scene behind
the window, the icon set, the full player bar (with its 900/620 px layouts), search (songs) with
a virtual track list, the shared player (queue, gapless preload, expired-URL retry), Liquid Glass
on wgpu (checked against Chromium) and Direct3D 11 (not run yet).

To do, in this order (each screen: its Tauri commands into lfly-core, the Svelte file with its
CSS numbers, a screenshot against `mock.html` in Chromium):

1. Foundations still missing: fonts, TrackRow, Shelf, Card, the SelectionPill spring, the
   sidebar's narrow mode, the Copertina dinamica flowing scene.
2. Shell still missing: the right panel (queue, lyrics, devices), the context menu, toasts.
3. Search (all result kinds), Home, Album, Playlist, Artist.
4. Now Playing, Lyrics.
5. Library, Liked, local playlists, Downloads, Settings, equalizer.
6. LFly Connect and library sync, Google Drive, system media controls, Discord, Spotify import,
   YouTube Music sign-in (a short-lived webview helper process: GPUI has no webview).
7. Installers (the Tauri bundler made them), and macOS (Metal glass) if it stays.

Cannot be ported: a Spicetify `user.css` imported as a theme (arbitrary CSS needs a web
engine). Its colours (`color.ini`) can.
