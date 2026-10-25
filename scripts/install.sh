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
# Fire TV ignores the default Home; the accessibility service covers Amazon's (FireTvHome).
# Home presses are read from the system log, which needs READ_LOGS from a fresh process.
fire_tv=false
if tv pm has-feature amazon.hardware.fire_tv | grep -q true; then
  fire_tv=true
  tv pm grant "$PKG" android.permission.READ_LOGS
  tv am force-stop "$PKG"
fi
# The key-filter accessibility service: the remote's Settings button from any app, and
# screenshots for Muse. Added to whatever services are already on.
current="$(tv settings get secure enabled_accessibility_services | tr -d '\r')"
others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -x -e "$A11Y" -e null | paste -sd: - || true)"
# Re-adding it rebinds the service (and, after the force-stop above, starts the app afresh).
if [ -n "$others" ]; then
  tv settings put secure enabled_accessibility_services "$others"
  tv settings put secure enabled_accessibility_services "$others:$A11Y"
else
  tv settings delete secure enabled_accessibility_services >/dev/null
  tv settings put secure enabled_accessibility_services "$A11Y"
fi
tv settings put secure accessibility_enabled 1
tv am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
if $fire_tv; then
  echo "Installed; on this Fire TV the launcher covers Amazon's home whenever it comes up."
else
  echo "Installed; the launcher is the home screen."
fi
