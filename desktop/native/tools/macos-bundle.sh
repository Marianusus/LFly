#!/bin/sh
# Wraps the release binary into LFly.app and a .dmg (macOS only, run from desktop/native after
# `cargo build --release`). Signed ad hoc, which Apple Silicon needs to run it at all; there is no
# Apple Developer ID, so on first open macOS asks to confirm (System Settings → Privacy & Security
# → "Open Anyway", or `xattr -dr com.apple.quarantine /Applications/LFly.app`).
set -e
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)
OUT=target/release/macos
APP="$OUT/LFly.app"
rm -rf "$OUT"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp target/release/lfly "$APP/Contents/MacOS/lfly"
cp ../app/src-tauri/icons/icon.icns "$APP/Contents/Resources/icon.icns"
sed "s/VERSION/$VERSION/g" macos/Info.plist > "$APP/Contents/Info.plist"
codesign --force --deep --sign - "$APP"
# The .dmg shows the app next to a link to Applications, to drag it over. hdiutil sizes an image
# from -srcfolder too small at times ("No space left on device"): the size is given with room.
STAGE="$OUT/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
SIZE=$(du -sm "$STAGE" | cut -f1)
hdiutil create -volname LFly -srcfolder "$STAGE" -ov -format UDZO -size "$((SIZE + 64))m" "$OUT/LFly-$VERSION-macos-arm64.dmg"
echo "$OUT/LFly-$VERSION-macos-arm64.dmg"
