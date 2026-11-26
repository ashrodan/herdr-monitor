#!/bin/sh
# Install the Summary mod into Herdr, and optionally add its blocks to a layout.
#   scripts/install-mod.sh                  copy the mod, check it
#   scripts/install-mod.sh --layout NAME    also add the bar and the panel to layouts/NAME.json
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
APP=${HERDR_APP_HOME:-$HOME/.config/herdr-app}
DEST="$APP/mods/summary"
mkdir -p "$DEST"
cp "$HERE"/herdr-mod/summary/*.js "$HERE"/herdr-mod/summary/mod.json "$HERE"/herdr-mod/summary/*.svg "$DEST"/
herdr-app check "$DEST"
# The panel's chat runs herdr-ask (a mod's exec finds it on the PATH).
mkdir -p "$HOME/.local/bin"
ln -sf "$HERE/bin/herdr-ask.js" "$HOME/.local/bin/herdr-ask"
if [ "${1:-}" = "--layout" ] && [ -n "${2:-}" ]; then
  node "$HERE/scripts/mod-layout.js" "$APP/layouts/$2.json"
fi
