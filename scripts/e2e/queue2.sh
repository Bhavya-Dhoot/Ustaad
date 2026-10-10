#!/usr/bin/env bash
# Per-phone device queue (markers phone-<A|B|C>-owner.txt in the scratchpad folder).
#   scripts/e2e/queue2.sh acquire <letters> [max-minutes]   e.g. acquire BC 60. Waits (checks every 2 minutes) until every listed
#                                                         phone's marker is absent and no standalone llama / rpc / bench process
#                                                         (owner shell) runs on it, then writes "e2e" into each marker.
#   scripts/e2e/queue2.sh release <letters>                 delete the markers that say e2e
#   scripts/e2e/queue2.sh status <letters>                  print who holds what, change nothing
# Letters: A = 192.168.242.89, B = 192.168.242.23, C = 192.168.242.76 (adb <ip>:5555).
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
SP="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad"
ip_of() { case "$1" in A) echo 192.168.242.89 ;; B) echo 192.168.242.23 ;; C) echo 192.168.242.76 ;; *) echo "bad letter $1" >&2; exit 2 ;; esac; }
cmd="${1:-}"; letters="${2:-}"; max="${3:-60}"

busy_reason() {
  local l
  for l in $(echo "$letters" | grep -o .); do
    [ -f "$SP/phone-$l-owner.txt" ] && echo "phone $l marker held by '$(cat "$SP/phone-$l-owner.txt")'"
    local p; p=$("$ADB" -s "$(ip_of "$l"):5555" shell 'ps -A -o USER,PID,ARGS' </dev/null 2>/dev/null | tr -d '\r' \
      | awk '$1 == "shell" && ($0 ~ /llama/ || $0 ~ /rpc-server/ || $0 ~ /bench/) && $0 !~ /ps -A/ { print }')
    [ -n "$p" ] && echo "phone $l standalone process: $(echo "$p" | head -1 | cut -c1-120)"
  done
}

case "$cmd" in
  acquire)
    waited=0
    while :; do
      why=$(busy_reason)
      if [ -z "$why" ]; then
        sleep 5
        free=1; for l in $(echo "$letters" | grep -o .); do [ -f "$SP/phone-$l-owner.txt" ] && free=0; done
        if [ $free = 1 ]; then for l in $(echo "$letters" | grep -o .); do echo "e2e" > "$SP/phone-$l-owner.txt"; done; echo "acquired $letters at $(date +%T) after $waited min"; exit 0; fi
      else echo "$(date +%T) waiting ($waited of $max min): $(echo "$why" | tr '\n' ';')"; fi
      [ "$waited" -ge "$max" ] && { echo "queue did not free within $max min"; exit 1; }
      sleep 120; waited=$((waited + 2))
    done ;;
  release)
    for l in $(echo "$letters" | grep -o .); do
      f="$SP/phone-$l-owner.txt"
      if [ -f "$f" ] && [ "$(cat "$f")" = e2e ]; then rm -f "$f"; echo "released $l"; else echo "$l marker is '$( [ -f "$f" ] && cat "$f" || echo absent)', not ours; left alone"; fi
    done ;;
  status) why=$(busy_reason); echo "${why:-free}" ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
