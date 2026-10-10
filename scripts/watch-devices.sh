#!/usr/bin/env bash
# Watches which phones adb can see and exits when that set changes (so the caller is told).
# It only parses `adb devices`: it never runs a shell command on any phone, so it is safe while a phone is reserved.
# It tries `adb connect` only for the addresses given as arguments (the phones this session may use).
# Usage: bash scripts/watch-devices.sh [ip:port ...]
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
snapshot() { "$ADB" devices </dev/null 2>/dev/null | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}' | sort | tr '\n' ' '; }
base="$(snapshot)"
echo "watching; baseline: [$base]"
changed=0
while :; do
  sleep 20
  for a in "$@"; do
    case " $base " in *" $a "*) ;; *) "$ADB" connect "$a" </dev/null >/dev/null 2>&1 ;; esac
  done
  now="$(snapshot)"
  if [ "$now" != "$base" ]; then
    changed=$((changed + 1))
    # three polls in a row must agree before it counts (a wireless link can blink)
    if [ "$changed" -ge 3 ]; then echo "$(date +%H:%M:%S) changed: was [$base] now [$now]"; exit 0; fi
  else
    changed=0
  fi
done
