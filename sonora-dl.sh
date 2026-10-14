#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# sonora-dl  —  Download any YouTube song and push it instantly to Sonora
# Usage:
#   sonora-dl "panzerkraken"               ← search query
#   sonora-dl "https://youtu.be/xxxx"      ← direct URL
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

YTDLP="${HOME}/.local/bin/yt-dlp"
DOWNLOAD_DIR="${HOME}/SonoraDownloads"
DEVICE_MUSIC_DIR="/sdcard/Android/data/org.sonora.android/files/music"
ADB="adb"
SONORA_PKG="org.sonora.android"

# ── Validate tools ────────────────────────────────────────────────────────────
if [ ! -x "${YTDLP}" ]; then
  echo "❌  yt-dlp not found at ${YTDLP}. Install it first."
  exit 1
fi
if ! command -v "${ADB}" &>/dev/null; then
  echo "❌  adb not found. Install Android platform-tools."
  exit 1
fi

QUERY="${*:?Usage: sonora-dl <youtube-url-or-search-query>}"

mkdir -p "${DOWNLOAD_DIR}"

echo "📥  Downloading: ${QUERY}"

# ── Download best audio, convert to MP3 ──────────────────────────────────────
"${YTDLP}" \
  --default-search "ytsearch1" \
  -x \
  --audio-format mp3 \
  --audio-quality 0 \
  --embed-thumbnail \
  --embed-metadata \
  --parse-metadata "%(uploader)s:%(meta_artist)s" \
  -o "${DOWNLOAD_DIR}/%(uploader)s - %(title)s.%(ext)s" \
  "${QUERY}" 2>&1 | tail -n 20

# ── Find the newly downloaded file(s) ────────────────────────────────────────
mapfile -t NEW_FILES < <(find "${DOWNLOAD_DIR}" -name "*.mp3" -newer "${DOWNLOAD_DIR}" -printf "%f\n" 2>/dev/null || \
                          find "${DOWNLOAD_DIR}" -name "*.mp3" -maxdepth 1)

if [ ${#NEW_FILES[@]} -eq 0 ]; then
  echo "⚠️   No new file found in ${DOWNLOAD_DIR}. Check yt-dlp output above."
  exit 1
fi

# ── Push each file to device ──────────────────────────────────────────────────
DEVICE_SERIAL=$(adb devices | awk '/device$/{print $1}' | head -n1)
if [ -z "${DEVICE_SERIAL}" ]; then
  echo "❌  No Android device found via adb. Connect your phone."
  exit 1
fi

for FILE in "${NEW_FILES[@]}"; do
  FULL_PATH="${DOWNLOAD_DIR}/${FILE}"
  echo "📤  Pushing: ${FILE}"
  "${ADB}" -s "${DEVICE_SERIAL}" push "${FULL_PATH}" "${DEVICE_MUSIC_DIR}/${FILE}"
  echo "✅  Pushed to device: ${DEVICE_MUSIC_DIR}/${FILE}"
done

# ── Broadcast rescan so Sonora picks up the file immediately ─────────────────
echo "🔄  Triggering Sonora local music rescan…"
"${ADB}" -s "${DEVICE_SERIAL}" shell \
  "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
   -d 'file://${DEVICE_MUSIC_DIR}'" &>/dev/null || true

echo ""
echo "🎵  Done! Open Sonora → Library → Scaricati to play your song."
