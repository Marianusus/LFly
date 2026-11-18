#!/bin/sh
# Builds the Rust core for iPhone and the simulator into LFlyCore.xcframework and writes the Swift
# bindings to LFly/Generated. Run on a Mac with Xcode (the iOS SDKs come with it), from ios/.
set -e
cd "$(dirname "$0")"
export IPHONEOS_DEPLOYMENT_TARGET=26.0
# Xcode without `xcode-select -s` (which needs an administrator): point the tools at it directly.
if [ -z "$DEVELOPER_DIR" ] && [ -d /Applications/Xcode.app ]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
rustup target add aarch64-apple-ios aarch64-apple-ios-sim >/dev/null
cd core
cargo build --release --lib --target aarch64-apple-ios
cargo build --release --lib --target aarch64-apple-ios-sim
# The bindings come from a host build of the same library (they do not depend on the target).
cargo build --release --lib
cargo run --release --bin uniffi-bindgen -- generate --library target/release/liblfly_ios.dylib --language swift --out-dir ../LFly/Generated
cd ..
# The C header and module map for Xcode (the Swift file stays in Generated/).
HEADERS=core/target/headers
rm -rf "$HEADERS" LFlyCore.xcframework
mkdir -p "$HEADERS"
cp LFly/Generated/lfly_iosFFI.h "$HEADERS/"
cp LFly/Generated/lfly_iosFFI.modulemap "$HEADERS/module.modulemap"
rm LFly/Generated/lfly_iosFFI.h LFly/Generated/lfly_iosFFI.modulemap
xcodebuild -create-xcframework \
  -library core/target/aarch64-apple-ios/release/liblfly_ios.a -headers "$HEADERS" \
  -library core/target/aarch64-apple-ios-sim/release/liblfly_ios.a -headers "$HEADERS" \
  -output LFlyCore.xcframework
echo "LFlyCore.xcframework and LFly/Generated/lfly_ios.swift are ready; now: xcodegen"
