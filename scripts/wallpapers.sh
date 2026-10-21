#!/usr/bin/env bash
# Puts a folder of your photos on the TV as the rotating wallpaper.
#
# Each photo is resized on this computer so it just covers 2304x1296 (the screen plus 20%,
# so the slow pan and zoom never upscales), saved as JPEG, and pushed over adb into the
# launcher's own folder on the TV. The launcher crops each one to fill the screen, picks up
# changes within a minute, and shuffles through them every 60 seconds.
#
# Usage:
#   scripts/wallpapers.sh <folder>             add (or update) the photos in <folder>
#   scripts/wallpapers.sh <folder> --replace   remove the TV's current wallpapers first
#   scripts/wallpapers.sh --list               show what's on the TV
#   scripts/wallpapers.sh --clear              remove all wallpapers from the TV
# Needs ImageMagick (`magick`, which also applies photos' rotation tags) or macOS `sips`.
# TV_ADB=host:port is your TV.
set -euo pipefail

TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
DEST="/sdcard/Android/data/dev.tvlauncher/files/wallpapers"
W=2304
H=1296

tv() { adb -s "$TV" shell "$@"; }
connect() {
  adb connect "$TV" >/dev/null
  adb -s "$TV" get-state >/dev/null 2>&1 || { echo "TV $TV is not reachable over adb" >&2; exit 1; }
}

case "${1:-}" in
  --list) connect; tv ls -l "$DEST" 2>/dev/null || echo "No wallpapers on the TV yet."; exit 0 ;;
  --clear) connect; tv rm -f "$DEST/*"; echo "Removed all wallpapers from the TV."; exit 0 ;;
  ""|-h|--help) sed -n '2,17p' "$0"; exit 2 ;;
esac

SRC="$1"
[[ -d "$SRC" ]] || { echo "Not a folder: $SRC" >&2; exit 1; }
REPLACE="${2:-}"

if command -v magick >/dev/null 2>&1; then TOOL=magick
elif command -v sips >/dev/null 2>&1; then TOOL=sips
else echo "Install ImageMagick (brew install imagemagick) or run this on a Mac (sips)." >&2; exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

count=0
while IFS= read -r -d '' photo; do
  name="$(basename "${photo%.*}").jpg"
  if [[ $TOOL == magick ]]; then
    # -auto-orient applies the camera's rotation tag; WxH^ = cover, only ever shrinking.
    magick "$photo" -auto-orient -resize "${W}x${H}^>" -quality 90 "$OUT/$name"
  else
    read -r w h < <(sips -g pixelWidth -g pixelHeight "$photo" | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w, h}')
    # Scale so the photo just covers W x H (never up).
    read -r nw nh < <(awk -v w="$w" -v h="$h" -v W="$W" -v H="$H" 'BEGIN{s=W/w; if (H/h>s) s=H/h; if (s>1) s=1; printf "%d %d\n", w*s+0.5, h*s+0.5}')
    sips -s format jpeg -s formatOptions 90 -z "$nh" "$nw" "$photo" --out "$OUT/$name" >/dev/null
  fi
  count=$((count + 1))
done < <(find "$SRC" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.heic' -o -iname '*.webp' \) -print0)

[[ $count -gt 0 ]] || { echo "No photos (jpg, jpeg, png, heic, webp) in $SRC" >&2; exit 1; }

connect
tv mkdir -p "$DEST"
[[ "$REPLACE" == --replace ]] && tv rm -f "$DEST/*"
adb -s "$TV" push "$OUT/." "$DEST/" >/dev/null
echo "Pushed $count photo(s); the TV picks them up within a minute."
