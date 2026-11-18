# LFly for iPhone

SwiftUI app (iOS 26, Liquid Glass of the system) over a Rust core shared with the desktop:
`core/` wraps `desktop/crates/lfly-yt` (YouTube Music, full-length streams in AAC for AVPlayer,
synced lyrics) and is exported to Swift with UniFFI.

## Build (on a Mac with Xcode)

```sh
cd ios
./build-core.sh          # Rust → LFlyCore.xcframework + LFly/Generated/lfly_ios.swift
xcodegen                 # project.yml → LFly.xcodeproj (brew install xcodegen)
open LFly.xcodeproj      # pick your Apple ID team under Signing, run on the iPhone
```

With a free Apple ID the app stays installed for 7 days, then Xcode installs it again.

`cd core && cargo run --example smoke` checks search → stream → lyrics → home on any computer.

## What is there

- Home (YouTube Music shelves), Search (songs, albums, playlists), album and playlist pages.
- Player: AVPlayer with LFly's queue, next song resolved ahead, lock screen and Control Center
  (Now Playing with cover, play/pause, next/previous, scrubbing), background audio.
- Mini player as the tab bar accessory, full player with blurred cover and synced lyrics.

Next: library (favourites, playlists, history, the same files as the desktop), LFly Connect with
the phone and the computer, artist pages, YouTube Music sign-in, downloads, Automix.
