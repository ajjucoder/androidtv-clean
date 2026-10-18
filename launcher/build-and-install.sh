#!/usr/bin/env bash
# Build the launcher APK with the plain SDK tools (no Gradle) and install it on the TV as home.
# Usage: ./build-and-install.sh [TV_IP]
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"
TV_IP="${1:-${TV_IP:-}}"
[ -n "$TV_IP" ] || { echo "Usage: $0 <tv-ip>  (or set TV_IP)"; exit 1; }
export ANDROID_SERIAL="$TV_IP:5555"
rm -rf build && mkdir -p build/classes
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version 28
javac -nowarn --release 11 -cp "$JAR" -d build/classes $(find src -name '*.java') 2>&1 | grep -v -E 'warning|^1 warning|^Note' || true
"$BT/d8" --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
KS=debug.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias home \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Home" >/dev/null 2>&1
"$BT/apksigner" sign --ks $KS --ks-pass pass:android --out build/tvhome.apk build/aligned.apk
adb connect "$ANDROID_SERIAL" >/dev/null
adb install -r build/tvhome.apk
adb shell cmd package set-home-activity --user 0 com.parents.tvhome/.MainActivity
# Stock Google TV launcher (ads, rows). Disabled only after ours is in. leanbacklauncher stays as fallback.
adb shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null
adb shell input keyevent KEYCODE_HOME
echo "Installed. Press Home on the remote."
