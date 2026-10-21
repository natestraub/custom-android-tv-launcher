#!/usr/bin/env bash
# Sends the Meta Model API key (Muse Voice Transcribe: realtime speech-to-text for the
# remote's mic) from the macOS Keychain to the launcher on the TV.
#
# Like muse-token.sh, the key goes to the TV over adb on stdin, so it never appears in this
# script's arguments, shell history or output. On the TV only the adb shell (DUMP permission)
# can deliver it; the launcher stores it encrypted with an Android Keystore key.
#
# Usage:
#   scripts/meta-asr-key.sh            send the key (asks once and saves it to the Keychain)
#   scripts/meta-asr-key.sh --status   is a key stored on the TV? (never prints it)
#   scripts/meta-asr-key.sh --clear    remove the key from the TV
#   scripts/meta-asr-key.sh --forget   remove the key from the Mac's Keychain
# TV_ADB=host:port is your TV (set it in mise.local.toml).
set -euo pipefail

TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
SERVICE="tv-launcher-meta-model-api-key"
RECEIVER="dev.tvlauncher/.muse.MuseTokenReceiver"
ACTION_PREFIX="dev.tvlauncher.muse"

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
    echo "Key on TV: $(adb -s "$TV" shell am broadcast -n "$RECEIVER" -a "$ACTION_PREFIX.ASR_KEY_STATUS" | tv_result)"
    exit 0 ;;
  --clear)
    connect
    echo "TV: $(adb -s "$TV" shell am broadcast -n "$RECEIVER" -a "$ACTION_PREFIX.CLEAR_ASR_KEY" | tv_result)"
    exit 0 ;;
  --forget)
    security delete-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1 && echo "Removed from Keychain." || echo "Nothing in Keychain."
    exit 0 ;;
  "") ;;
  *) sed -n '2,15p' "$0" >&2; exit 2 ;;
esac

if ! security find-generic-password -a "$USER" -s "$SERVICE" >/dev/null 2>&1; then
  echo "No Meta Model API key in the Keychain yet. Create one at https://platform.meta.ai"
  echo "Paste it at the Keychain prompt (input is hidden; it asks twice):"
  # With no value after -w, `security` prompts for it itself, so it never touches argv.
  security add-generic-password -a "$USER" -s "$SERVICE" -l "TV Launcher Meta Model API key" -w
fi

key="$(security find-generic-password -a "$USER" -s "$SERVICE" -w)"
connect
# The key travels on stdin; the TV-side shell reads it into a variable for the broadcast.
result="$(printf '%s\n' "$key" | adb -s "$TV" shell \
  "read -r t; am broadcast -n $RECEIVER -a $ACTION_PREFIX.SET_ASR_KEY --es token \"\$t\"; t=" | tv_result)"
unset key
case "$result" in
  saved) echo "Meta API key saved on the TV." ;;
  *) echo "TV rejected the key: ${result:-no reply (is the launcher installed?)}" >&2; exit 1 ;;
esac
