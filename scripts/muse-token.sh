#!/usr/bin/env bash
# Syncs the Muse Gadget SDK token to the launcher on the TV, so nobody types it with a remote.
#
# The token lives in the macOS Keychain (service "tv-launcher-muse-sdk-token"). It goes to the TV
# over adb on stdin, so it never appears in this script's arguments, shell history or output.
# On the TV, only the adb shell (DUMP permission) can deliver it; the launcher stores it
# encrypted with an Android Keystore key.
#
# Usage:
#   scripts/muse-token.sh            send the token (asks once and saves it to the Keychain)
#   scripts/muse-token.sh --status   is a token stored on the TV? (never prints it)
#   scripts/muse-token.sh --clear    remove the token from the TV
#   scripts/muse-token.sh --forget   remove the token from the Mac's Keychain
#   scripts/muse-token.sh --unpair   forget the TV's Muse pairing (keeps the token); pair again after
# TV_ADB=host:port is your TV (set it in mise.local.toml).
set -euo pipefail

TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
SERVICE="tv-launcher-muse-sdk-token"
RECEIVER="dev.tvlauncher/.muse.MuseTokenReceiver"
ACTION_PREFIX="dev.tvlauncher.muse"
# mgst_ + 43 canonical base64url chars (same rule as MuseTokenFormat.kt).
TOKEN_RE='^mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$'

tv_result() { # prints the broadcast's result data, e.g. "saved"
  sed -n 's/.*data="\([^"]*\)".*/\1/p'
}

connect() {
  adb connect "$TV" >/dev/null
  adb -s "$TV" get-state >/dev/null 2>&1 || { echo "TV $TV is not reachable over adb" >&2; exit 1; }
}

case "${1:-}" in
  --status)
    connect
    echo "Token on TV: $(adb -s "$TV" shell am broadcast -n "$RECEIVER" -a "$ACTION_PREFIX.TOKEN_STATUS" | tv_result)"
    exit 0 ;;
  --clear)
    connect
    echo "TV: $(adb -s "$TV" shell am broadcast -n "$RECEIVER" -a "$ACTION_PREFIX.CLEAR_TOKEN" | tv_result)"
    exit 0 ;;
  --unpair)
    connect
    echo "TV: $(adb -s "$TV" shell am broadcast -n "$RECEIVER" -a "$ACTION_PREFIX.CLEAR_PAIRING" | tv_result)"
    exit 0 ;;
  --forget)
    security delete-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1 && echo "Removed from Keychain." || echo "Nothing in Keychain."
    exit 0 ;;
  "") ;;
  *) sed -n '2,18p' "$0" >&2; exit 2 ;;
esac

if ! security find-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1; then
  echo "No Muse SDK token in the Keychain yet. Get one at https://gadgets.muse.ai/settings/sdk-tokens"
  echo "Paste it at the Keychain prompt (input is hidden; it asks twice):"
  # With no value after -w, `security` prompts for it itself, so it never touches argv.
  security add-generic-password -a "$USER" -s "$SERVICE" -l "TV Launcher Muse SDK token" -w
fi

token="$(security find-generic-password -a "$USER" -s "$SERVICE" -w)"
if [[ ! "$token" =~ $TOKEN_RE ]]; then
  unset token
  echo "The Keychain token doesn't look like an SDK token (mgst_…). Run with --forget and try again." >&2
  exit 1
fi

connect
# The token travels on stdin; the TV-side shell reads it into a variable for the broadcast.
result="$(printf '%s\n' "$token" | adb -s "$TV" shell \
  "read -r t; am broadcast -n $RECEIVER -a $ACTION_PREFIX.SET_TOKEN --es token \"\$t\"; t=" | tv_result)"
unset token
case "$result" in
  saved) echo "Muse SDK token saved on the TV." ;;
  *) echo "TV rejected the token: ${result:-no reply (is the launcher installed?)}" >&2; exit 1 ;;
esac
