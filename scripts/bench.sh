#!/usr/bin/env bash
# Launcher benchmark on the TV: cold/hot start, scroll jank, idle cost, memory.
# Usage: scripts/bench.sh   (TV_ADB=host:port to target another TV)
set -euo pipefail
TV="${TV_ADB:?Set TV_ADB to the TV address, e.g. 192.168.1.50:5555 (see README)}"
PKG=dev.tvlauncher
a() { adb -s "$TV" "$@"; }
home() { a shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r'; }
median() { sort -n | awk '{v[NR]=$1} END {print v[int((NR+1)/2)]}'; }
gfx() {
  a shell dumpsys gfxinfo "$PKG" | tr -d '\r' |
    grep -E "Total frames|Janky frames:|^50th percentile|^90th percentile|^99th percentile|50th gpu|90th gpu" | sed 's/^/  /'
}

adb connect "$TV" >/dev/null
echo "uptime: $(a shell uptime | tr -d '\r')"

echo "== cold start, launcher not running (ms, 5 runs)"
cold=$(for _ in 1 2 3 4 5; do
  a shell am force-stop "$PKG"; sleep 2
  home | awk '/TotalTime/ {print $2}'
done)
echo "  runs: $(echo $cold)   median: $(echo "$cold" | median)"

echo "== hot start, Home from YouTube (ms, 3 runs)"
hot=$(for _ in 1 2 3; do
  a shell monkey -p com.google.android.youtube.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 6
  home | awk '/TotalTime/ {print $2}'
done)
echo "  runs: $(echo $hot)   median: $(echo "$hot" | median)"

sleep 3
echo "== scroll: 15 right + 15 left through the app row"
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
a shell input keyevent $(printf 'KEYCODE_DPAD_RIGHT %.0s' {1..15})
a shell input keyevent $(printf 'KEYCODE_DPAD_LEFT %.0s' {1..15})
sleep 1
gfx

echo "== idle 10 s on home (wallpaper pan/zoom running)"
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
cpu=$(a shell "top -b -d 10 -n 2 -p \$(pidof $PKG)" | tr -d '\r' | awk -v p="$PKG" '$0 ~ p {v=$9} END {print v}')
gfx
echo "  launcher CPU: ${cpu}% of one core"

echo "== memory"
a shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS|Graphics:|Java Heap:|Native Heap:" | sed 's/^ */  /'

echo "== system"
a shell 'grep -E "MemAvailable|SwapTotal|SwapFree" /proc/meminfo; grep -E "^pswpout" /proc/vmstat' | tr -d '\r' | sed 's/^/  /'
