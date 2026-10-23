# Changelog

All notable changes to **LFly** (`org.sonora.android`).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow the app's `versionName`.

## [1.3] — 2026-10-23

**LFly v1.3** · `versionCode 3` · `minSdk 26` / `targetSdk 35` · 502 unit tests green.

This release rolls up everything built since **v1.1**: the Now Playing V2 redesign, the new Home, editorial library collections, the offline-lyrics manager, multi-artist/search work, the BitChord-style queue and menus, real Liquid Glass chrome, and a large batch of playback and stability fixes.

### Now Playing V2

- **Full-bleed artwork** — the sharp cover dissolves into a full-screen blurred copy of the same artwork (`DstIn` mask from 76%) with a continuous scrim across the cover/controls boundary. No hard edge, no colour band.
- **Measured marquee title** — long titles scroll BitChord-style instead of being clipped or mis-centred.
- **Karaoke lyric strip above the timeline** — word-by-word accent sweep with glow, animated line changes (slide, fade, micro-scale), respecting *Highlight lines* and *Reduce motion*.
- **Inline queue that morphs** — the cover shrinks into the 48 dp header thumbnail, the queue takes the cover's place, and the transport deck stays visible. One 420 ms progress drives the transition; **Clear** removes all upcoming tracks; drag works in both *Next* and *Radio*.
- **BitChord-style track menu** — shared by the player, rows and search: artwork header, flat grouped actions, download state, play-next/queue, open album/artist, an **Information** sub-page (album, release year, duration, plays, source, type), EQ/DSP, share and source.
- **Metadata resolution** — `/player` Android, VisionOS and iOS clients are tried independently; release year is resolved from the album header first, then album detail/search; 24 h caching only when the year is known, with retry when the menu opens.
- **Audio-output sheet** — flat output rows, active output disabled with a check, system volume, Equalizer/DSP and a Cast sub-menu with disconnect. Refreshes on audio callbacks, headset/HDMI/ACL/noisy broadcasts and delayed re-reads.
- **Held track menu** — compact long-press overlay, distinct from the ⋯ sheet, with contextual actions for playlist/queue removal and *Play now*.
- **Artist credits** — multi-artist parsing (`ArtistCredits`) that never splits names such as *Tyler, The Creator*, *Earth, Wind & Fire* or *R&B*; inline links are active only in the large player, with *Open artist* still available as an explicit action.
- **Artist hero fade** — the YouTube artist photo now dissolves into a blurred 64 dp copy inside the 420 dp hero, with a continuous scrim into the canvas, a 140 dp top scrim and a white back arrow.

### Home

- **Apple-design hierarchy** — “Your listening, now”: resume hero, 3×3 paginated *Most played* shelf, On Repeat, a single *For you* rail (mixes + recommendations), and Italy/Europe chart tabs.
- **Resume hero morph** — the hero is hidden while a track is playing and shrinks/fades into the mini player when *Resume* is tapped.
- **Real Liquid Glass (opt-in)** — backdrop-sampled blur, saturation, highlight, shadow and lens refraction on API 33+ (blur + highlight on API 31–32), with a dedicated Home backdrop to avoid the recursive-backdrop crash fixed in this cycle.
- **BitChord-style chrome** — 64 dp now-playing dock and tab pill, separate search circle, drag bubble with haptic tick, immediate tap lift, `sharedBounds` fold and track-change slide.
- **Accessibility and layout** — 12 sp minimum content type, one column at font scale ≥ 1.3, WCAG-contrast foregrounds, no state conveyed by colour alone, decorative artwork hidden from TalkBack, coherent skeletons and actionable empty states.

### Library & collections

- **One editorial “sleeve” layout** for albums, playlists, YouTube albums/playlists, mixes, favorites, history and downloads: collapsible top bar, hero sleeve, metadata, expandable description, localized stats and a flat track list.
- **Flat content, glass chrome** — per-row glass cards removed; glass stays on top bars, buttons and sheets. Rows use 48 dp targets, quick favorite and a download action in the menu.
- **Collection actions** — download the whole collection with aggregate progress, add it to the queue, jump to the current track and an empty state that leads to search.
- **Album covers restored** — local and YouTube albums show track numbers plus artwork; playlists show artwork only.
- **Favorites, history and downloads** use the same layout, including reorder handles, collection download and a “now playing” thread.

### Offline lyrics

- **New Settings → Music → Download → Offline lyrics screen**, listing only tracks downloaded by the app (local files and the existing offline list are untouched).
- **Per-track state** — `Lyrics · source`, missing, not found, fetching, error.
- **Per-track source picker** — *Automatic* or an explicit source; the override is persisted (`lyrics_source_<id>`) and shared by the player and automatic downloads. Choosing a source starts the fetch immediately.
- **Actions** — download, re-download, remove with confirmation, filters *All / With lyrics / Without lyrics* and **Download missing (N)**.
- **Persistence** — JSON sidecars in `filesDir/downloaded_lyrics/`; the player reads them before the network, so lyrics are available in airplane mode. Fetching runs outside the audio semaphore and cannot fail a download. The global “Download lyrics too” toggle is unchanged.

### Downloads & favorites

- **Favorites are never pruned** by download cleanup, and `provider="Local"` copies are mapped back to their catalog identity.
- **Dead local paths are repaired** — missing `streamUrl`/cover is cleared and the provider is restored (`yt_` → YouTube) without removing the heart.
- **Canonical track identity** — when a downloaded/local copy matches by title/artist, `PlayableTrackFor` keeps the catalog identity and takes only stream/cover from the local match; fuzzy lookup is skipped when an exact download exists. Player and mini player now use a canonical favorite lookup, so the heart reflects the real state.
- **Download manager** — per-track and whole-collection downloads with aggregate progress, canonical matching, lyrics sidecars and safe cleanup.

### Search & artist pages

- **Typeahead** — up to four YouTube Music completions (170 ms debounce), a `Search "text"` row and local matches from recents. Results start only on Enter/tap, not on every pause.
- **Recent entities** — tracks, albums, artists and playlists with cover/type, plus confirmed queries; single removal and *Clear* (`search_history.json`, max 20). Tapping a track plays it; albums/artists/playlists open their page; a query re-runs.
- **Artist search** — InnerTube artist filter with continuation, merged/deduplicated results and correct top-artist resolution; `openArtist` uses the ID when available, otherwise an exact-name search with fallback.
- **Cleaner YouTube results** — generic `YouTube Music` / `YouTube Music - Topic` authors are discarded, `Artist - Track` titles are split, and singles/albums inherit artist, album title and cover from the header.
- **Search tab behaviour** — every tap on the Search tab returns to the root, clears the field and opens the keyboard.

### Queue, playback & stability

- **Shuffle fix** — `ShuffleIndexMath` keeps the queue order stable when playing next/adding to the queue.
- **Queue morph** — a single title block moves/scales from under the cover to the compact header; no duplicated title or fade-out/fade-in.
- **Lyrics overlay fix** — the player chrome fades to zero while lyrics are open and the lyrics view gets `zIndex(6f)`, so controls no longer appear over the verses.
- **LyricsPlus fix** — an all-zero timestamp response falls back to plain static lyrics instead of a broken sync.
- **Playback** — full-length stream resolution (no 30 s previews as the primary path), rotating endpoint pool with health tracking and transparent failover, asynchronous N+1 prefetch/pre-cache, autoplay recommendations.
- **Audio routing** — local output selection (phone, Bluetooth, wired, USB, HDMI) with state, fallback and cleanup when a device disappears.
- **Party (from v1.1)** — synchronized group listening with nearby/QR/code join, host-controlled permissions, clock handshake, drift correction and per-device delay.

### Design, accessibility & localization

- AMOLED-first theme, four appearance modes, artwork-derived adaptive accent, opt-in real Liquid Glass, reduced-motion support, semantic light/dark colours and WCAG-aware foreground selection.
- Minimum 44–48 dp targets, flat content surfaces, glass reserved for chrome and sheets, no state signalled by colour alone.
- Italian, English and Romanian UI strings, including the new collection, download and offline-lyrics screens.
- Shared `Modifier.artworkBottomFade` used by both the player hero and the artist hero.

### Performance

- Collection, library and artist screens no longer receive `PlaybackState` at 4 Hz; they receive only `currentTrackId` / `isPlaying`.
- `TrackAnchor` moved out of snapshot state; long-press controllers and `DownloadUiState` stabilized; `ArtistLinks` and favorite/download lookups memoized.
- Home isolated from the position ticker; player artist callback stabilized; queue rows use plain text.
- Album/artist/YouTube detail state cleared and request-tokenized to prevent stale content; metadata cached only when complete.

### Build & verification

- **502 unit tests**, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug` green.
- Installed and version-verified on `R5CWA292N5R` (Galaxy S23 Ultra, SM-S918B): `versionName=1.3`, `versionCode=3`.
- Debug-signed APK, package `org.sonora.android`, Android 8.0+ (API 26+).

### Install

```bash
adb install -r "LFly-v1.3.apk"
```

### Known limitations

- **Cast** discovery/connection UI is ready, but remote Cast playback is not implemented yet (needs `RemoteMediaClient` plus a LAN HTTP proxy for protected/YouTube streams).
- The inactive `BitchordPlayerScreen` / `BitMesh` experiments remain in the tree for reference and are not wired to `MainScreen`.

### Credits

- Lyrics engine adapted from [sonorahq/sonora](https://github.com/sonorahq/sonora) (GPL-3.0).
- Inline queue and bottom-chrome interaction adapted from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0).
- Backdrop/Liquid Glass engine: [Kyant0/backdrop](https://github.com/Kyant0/backdrop) (Apache-2.0).
- Audio streaming via the public [Monochrome](https://monochrome.tf) HiFi API; YouTube Music via public InnerTube endpoints.

**License:** GPL-3.0 · Not affiliated with YouTube, Spotify, Monochrome or sonorahq.

## [1.1] — 2026-10-16

- **Party** — synchronized group listening: nearby/QR/6-character join, host-controlled guest permissions, clock handshake, 1.5 s heartbeat, align burst, smooth drift correction via playback-speed nudges and remote pause/play from notification, lock screen, Quick Settings and Bluetooth.
- Playback-sync and control fixes since v1.0.0.

## [1.0.0] — 2026-10-14

- Initial public release: native Kotlin/Compose/Media3 player, Monochrome HiFi + YouTube Music streaming, word-level lyrics, background/lock-screen playback, Spotify import, local music and the Liquid-Glass UI.
