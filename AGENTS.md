# For AI agents setting this up on someone's TV

Goal: give the user's Android TV the androidtv-clean home screen, screensaver and debloat, safely and reversibly.

## Rules

- Run `./backup.sh <tv-ip>` before changing anything. Never commit or share `backup/`, because it holds Wi-Fi names and account details.
- Disable, never uninstall. Use `pm disable-user --user 0` only. Never use `pm uninstall` on system apps.
- `debloat.txt` was written for a Sony Bravia. On another brand, list packages with `adb shell pm list packages` and only disable a package when you know what it does. Show the user the list and get a yes first.
- Never disable these:
  - Settings, the keyboard and the Play Store.
  - Voice search (`com.google.android.katniss`) and Chromecast (`com.google.android.apps.mediashell`).
  - System update apps and anything audio, HDMI/CEC, input or tuner related (`com.sony.dtv.tvx`, `*.audiooutput`, `*.configsettings`).
- Ask before closing an app the user is watching, waking a TV that is off, or rebooting.
- Don't ask for or store streaming passwords or profile PINs.

## Steps

1. Run `adb connect <tv-ip>:5555`, accept the prompt on the TV, then run `./backup.sh <tv-ip>`.
2. Ask which apps the user watches. Set `DOCK` and `BOX_SOCKET` in `launcher/src/com/parents/tvhome/MainActivity.java`.
3. Run `launcher/build-and-install.sh <tv-ip>`, press Home and take a screenshot to check it: `adb exec-out screencap -p > shot.png`.
4. Go through `debloat.txt` with the user, then run `./disable.sh <tv-ip>`.
5. Optional: run `adb shell settings put secure screensaver_components com.parents.tvhome/.Ambient`.
6. Check that each main app still opens and plays with sound.

## Tips

- Sony settings pages are often blank in screenshots. Read them with `adb shell uiautomator dump /sdcard/ui.xml`, and check where focus is before pressing OK.
- Undo everything with `./restore.sh <tv-ip>`.
