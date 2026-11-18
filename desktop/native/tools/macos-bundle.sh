#!/bin/sh
# Wraps the release binary into LFly.app and a .dmg (macOS only, run from desktop/native after
# `cargo build --release` and `cargo build --release -p lfly-web`). Signed ad hoc, which Apple Silicon needs to run it at all; there is no
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
# The web helper for Spotify's sign-in (cargo build --release -p lfly-web), when built.
if [ -x target/release/lfly-web ]; then cp target/release/lfly-web "$APP/Contents/MacOS/lfly-web"; fi
cp ../app/src-tauri/icons/icon.icns "$APP/Contents/Resources/icon.icns"
sed "s/VERSION/$VERSION/g" macos/Info.plist > "$APP/Contents/Info.plist"
# The local certificate from macos-signing-setup.sh when this Mac has it (permissions then stay
# from one build to the next), else ad hoc.
SIGN=-
if security find-certificate -c "LFly Local Signing" >/dev/null 2>&1; then SIGN="LFly Local Signing"; fi
codesign --force --deep --sign "$SIGN" "$APP"
# The .dmg shows the app next to a link to Applications, to drag it over. hdiutil sizes an image
# from -srcfolder too small at times ("No space left on device"): the size is given with room.
STAGE="$OUT/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
SIZE=$(du -sm "$STAGE" | cut -f1)
hdiutil create -volname LFly -srcfolder "$STAGE" -ov -format UDZO -size "$((SIZE + 64))m" "$OUT/LFly-$VERSION-macos-arm64.dmg"
echo "$OUT/LFly-$VERSION-macos-arm64.dmg"
