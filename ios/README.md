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

- Home (YouTube Music shelves), Search (songs, artists, albums, playlists), album, playlist and
  artist pages.
- Library: liked songs, downloads, recently played, playlists made in LFly, the account's YouTube
  Music playlists. The files are the phone's and the computer's own (favorites.json,
  playlists.json, history.json in Documents/library), so a library moves between them as it is.
- Long press on a song: like, play next, add to the queue, add to a playlist, download.
- Downloads in 1 MiB Range chunks, four at a time (googlevideo throttles one long request); a
  downloaded song plays from its file.
- YouTube Music sign-in on Google's own page in a WKWebView; the session lives in the Keychain and
  the liked songs join the favourites.
- Player: AVPlayer with LFly's queue, the next song resolved ahead, lock screen and Control Center,
  background audio; mini player as the tab bar accessory; full player with the blurred cover,
  the heart and synced lyrics.

## Test runs in the simulator

The simulator takes no taps from a script, so launch arguments drive it:
`xcrun simctl launch <device> org.sonora.lfly -autoplay "query" -showPlayer YES -showLyrics YES
-like YES -download YES -tab library|search -artist "query"`.

Next: LFly Connect with the phone and the computer (library sync), Automix, the DJ.
