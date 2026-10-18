#!/usr/bin/env bash
# Save the TV's current state before changing anything: settings, package lists and the home app.
# Stays on your computer in backup/ (git-ignored: it holds Wi-Fi names and account details).
# Usage: ./backup.sh <tv-ip>
set -uo pipefail
cd "$(dirname "$0")"
TV_IP="${1:-${TV_IP:-}}"
[ -n "$TV_IP" ] || { echo "Usage: $0 <tv-ip>  (or set TV_IP)"; exit 1; }
export ANDROID_SERIAL="$TV_IP:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
mkdir -p backup
for ns in global secure system; do adb shell settings list $ns > backup/settings-$ns.txt; done
adb shell pm list packages -f > backup/packages-all.txt
adb shell pm list packages -d > backup/packages-disabled.txt
adb shell pm list packages -3 > backup/packages-3rdparty.txt
adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN > backup/home.txt
adb shell getprop > backup/getprop.txt
echo "Saved to backup/"
