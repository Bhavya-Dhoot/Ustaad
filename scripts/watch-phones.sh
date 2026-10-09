#!/usr/bin/env bash
# Poll ADB every 20 s; reconnect known wireless phones; exit (and print) when the set of connected
# iQOO serials differs from the expected set for 3 polls in a row.
# Usage: scripts/watch-phones.sh "<expected serials, space separated, sorted>"
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1
serials() {
  for d in $("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}'); do
    m=$("$ADB" -s "$d" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')
    [ "$m" = "I2501" ] && "$ADB" -s "$d" shell getprop ro.serialno </dev/null 2>/dev/null | tr -d '\r'
  done | sort -u | tr '\n' ' ' | sed 's/ $//'
}
EXPECT="${1:-}"
[ -z "$EXPECT" ] && EXPECT="$(serials)"
echo "watching; baseline: [$EXPECT]"
miss=0
while true; do
  for hp in $("$ADB" mdns services 2>/dev/null | awk '/_adb\._tcp/{print $3}'); do "$ADB" connect "$hp" </dev/null >/dev/null 2>&1; done
  NOW="$(serials)"
  if [ "$NOW" != "$EXPECT" ]; then miss=$((miss+1)); else miss=0; fi
  if [ "$miss" -ge 3 ]; then echo "PHONE SET CHANGED at $(date +%H:%M:%S): was [$EXPECT] now [$NOW]"; "$ADB" devices -l; exit 0; fi
  sleep 20
done
