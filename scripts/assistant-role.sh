#!/usr/bin/env bash
# Chooses which app the TV's remote mic button talks to.
#
# The remote hands its mic audio to whoever holds the Assistant role
# (docs/atv-remote-assist-protocol.md §1). Muse also needs the microphone permission and app-op,
# because unlike Google's assistant it gets no exception (§5).
#
# Usage:
#   scripts/assistant-role.sh muse     make Muse the assistant
#   scripts/assistant-role.sh google   give it back to Google Assistant
#   scripts/assistant-role.sh          show who holds it now
# TV_ADB=host:port is your TV (set it in mise.local.toml).
set -euo pipefail

TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
ROLE="android.app.role.ASSISTANT"
MUSE="dev.tvlauncher"
GOOGLE="com.google.android.katniss"

adb connect "$TV" >/dev/null
adb -s "$TV" get-state >/dev/null 2>&1 || { echo "TV $TV is not reachable over adb" >&2; exit 1; }
tv() { adb -s "$TV" shell "$@"; }

case "${1:-}" in
  muse)
    tv pm grant "$MUSE" android.permission.RECORD_AUDIO
    tv appops set "$MUSE" RECORD_AUDIO allow
    # TCL blocks the system from (re)starting apps for a bind unless this TCL-only app-op is
    # allowed; without it the assistant service stays unbound after a reinstall or cold boot.
    tv appops set "$MUSE" APP_AUTO_START allow
    # Via Google first, so the remote service always sees a change and looks up Muse's UID
    # again (it caches it, and the first lookup fails if this app wasn't visible yet).
    tv cmd role add-role-holder "$ROLE" "$GOOGLE"
    tv cmd role add-role-holder "$ROLE" "$MUSE" ;;
  google)
    tv cmd role add-role-holder "$ROLE" "$GOOGLE" ;;
  "") ;;
  *) echo "usage: $0 [muse|google]" >&2; exit 2 ;;
esac

echo "Role holder: $(tv dumpsys role | grep -A1 "name=$ROLE\$" | sed -n 's/.*holders=//p')"
echo "Assistant:   $(tv settings get secure assistant)"
echo "Voice input: $(tv settings get secure voice_interaction_service)"
