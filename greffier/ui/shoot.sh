#!/bin/sh
# usage: shoot.sh name 'hash' [W,H]   (needs the static server on :8765; uses Brave headless)
B="/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"
D="$(cd "$(dirname "$0")" && pwd)"
"$B" --headless=new --disable-gpu --hide-scrollbars --window-size=${3:-1600,1000} --virtual-time-budget=${BUDGET:-15000} --screenshot="$D/shots/$1.png" "http://localhost:${PORT:-8765}/${PAGE:-index.html}#$2" >/dev/null 2>&1
ls -la "$D/shots/$1.png"
