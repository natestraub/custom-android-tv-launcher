#!/usr/bin/env bash
# Installs the release build on the TV and makes it the home screen.
# Run through `mise run install` (which builds first). TV_ADB=host:port is your TV.
set -euo pipefail

TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
PKG="dev.tvlauncher"
A11Y="$PKG/$PKG.SettingsKeyService"
tv() { adb -s "$TV" shell "$@"; }

adb connect "$TV" >/dev/null
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
# Full ahead-of-time compile: no JIT warm-up on cold start.
tv cmd package compile -m speed -f "$PKG"
tv cmd package set-home-activity --user 0 "$PKG/.LauncherActivity"
# The key-filter accessibility service: the remote's Settings button from any app, and
# screenshots for Muse. Added to whatever services are already on.
current="$(tv settings get secure enabled_accessibility_services | tr -d '\r')"
case "$current" in
  *"$A11Y"*) ;;
  null|"") tv settings put secure enabled_accessibility_services "$A11Y" ;;
  *) tv settings put secure enabled_accessibility_services "$current:$A11Y" ;;
esac
tv settings put secure accessibility_enabled 1
tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Installed; the launcher is the home screen."
