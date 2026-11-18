#!/bin/sh
# Runs LFly in a headless KWin (never on the real screen: the user plays games on this PC) with
# its own config, data and cache, and takes a screenshot.
#
#   tools/headless.sh OUT.png SECONDS [ENV=VALUE ...]
#   e.g. tools/headless.sh /path/np.png 25 LFLY_AUTOPLAY="Daft Punk Get Lucky" LFLY_ROUTE=nowplaying
#
# The config is a copy of the real one made at the first run (tools/.headless/config; delete it to
# copy again): test runs must never write the listener's own settings, library or cache.
# Env: W, H (screen size, default 1100x720), TO (KWin timeout, default SECONDS + 40).
set -e
OUT="$1"; SECS="$2"; shift 2
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.headless"
mkdir -p "$ROOT/data" "$ROOT/cache" "$ROOT/kwin"
if [ ! -d "$ROOT/config/lfly" ]; then
  mkdir -p "$ROOT/config/lfly"
  [ -d "$HOME/.config/lfly/state" ] && cp -r "$HOME/.config/lfly/state" "$ROOT/config/lfly/"
fi
INNER="$ROOT/inner.sh"
{
  echo '#!/bin/sh'
  echo "export XDG_CONFIG_HOME='$ROOT/config' XDG_DATA_HOME='$ROOT/data' XDG_CACHE_HOME='$ROOT/cache'"
  echo "cd '$HERE/..'"
  printf 'env RUST_LOG=info LFLY_MUTE=1 LFLY_SIZE=%sx%s' "${W:-1100}" "${H:-720}"
  for kv in "$@"; do printf " '%s'" "$kv"; done
  echo " ./target/release/lfly > '$OUT.log' 2>&1 &"
  echo 'APP=$!'
  echo "sleep $SECS"
  echo "spectacle -b -n -f -o '$OUT' >/dev/null 2>&1"
  echo 'kill $APP'
} > "$INNER"
chmod +x "$INNER"
unset DISPLAY
export KWIN_COMPOSE=O2
XDG_CONFIG_HOME="$ROOT/kwin" timeout "${TO:-$((SECS + 40))}" dbus-run-session -- kwin_wayland --virtual \
  --width "${W:-1100}" --height "${H:-720}" --socket lfly-headless --no-lockscreen --no-global-shortcuts \
  --no-kactivities --exit-with-session "$INNER" 2>/dev/null || true
echo "screenshot: $OUT, log: $OUT.log"
