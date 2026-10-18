#!/usr/bin/env bash
# Disable every package in debloat.txt (reversible). Usage: ./disable.sh [TV_IP]
set -uo pipefail
cd "$(dirname "$0")"
TV_IP="${1:-${TV_IP:-}}"
[ -n "$TV_IP" ] || { echo "Usage: $0 <tv-ip>  (or set TV_IP)"; exit 1; }
export ANDROID_SERIAL="$TV_IP:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
for p in $(grep -vE '^\s*(#|$)' debloat.txt | awk '{print $1}'); do
  printf '%-45s ' "$p"; adb shell pm disable-user --user 0 "$p" 2>&1 | tail -1
done
