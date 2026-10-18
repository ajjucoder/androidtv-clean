#!/usr/bin/env bash
# Undo everything: re-enable all debloated packages, bring back the stock Sony/Google home screen,
# reset animation speed and allow background apps again. Usage: ./restore.sh [TV_IP]
set -uo pipefail
cd "$(dirname "$0")"
TV_IP="${1:-${TV_IP:-}}"
[ -n "$TV_IP" ] || { echo "Usage: $0 <tv-ip>  (or set TV_IP)"; exit 1; }
export ANDROID_SERIAL="$TV_IP:5555"
adb connect "$ANDROID_SERIAL" >/dev/null
for p in $(grep -vE '^\s*(#|$)' debloat.txt | awk '{print $1}') com.google.android.tvlauncher com.google.android.leanbacklauncher; do
  printf '%-45s ' "$p"; adb shell pm enable "$p" 2>&1 | tail -1
done
[ -f background.txt ] && for p in $(grep -vE '^\s*(#|$)' background.txt | awk '{print $1}'); do
  adb shell cmd appops set "$p" RUN_IN_BACKGROUND allow
done
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 1.0; done
adb shell settings put system sound_effects_enabled 1
adb shell pm uninstall com.parents.tvhome >/dev/null 2>&1
adb shell input keyevent KEYCODE_HOME
echo "Restored stock setup."
