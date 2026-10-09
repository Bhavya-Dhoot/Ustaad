#!/usr/bin/env bash
# Device queue for heavy work on phones A and C (docs: plans/briefs/_common.md, last addendum).
#   scripts/e2e/queue.sh acquire <ipA> <ipC> [max-minutes]   wait until the marker file is absent and no standalone llama / rpc
#                                                          process (owner shell, started over adb) runs on either phone, then write "e2e"
#                                                          into the marker. Checks every 2 minutes; exit 1 when max-minutes (default 90) pass.
#   scripts/e2e/queue.sh release                            delete the marker when it says e2e
#   scripts/e2e/queue.sh status <ipA> <ipC>                 print who holds the marker and the standalone processes, change nothing
# Only the two addresses given are used (adb <ip>:5555). Phone B is refused.
set -u
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
MARKER="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/base-id-owner.txt"
cmd="${1:-}"; shift || true

phones() {
  for ip in "$@"; do
    case "${ip%%:*}" in 192.168.242.23) echo "refusing phone B" >&2; exit 2 ;; esac
  done
}

# prints one line per standalone process (owner shell) whose command line mentions llama or rpc
standalone() { # ip
  "$ADB" -s "${1%%:*}:5555" shell 'ps -A -o USER,PID,ARGS' </dev/null 2>/dev/null | tr -d '\r' \
    | awk '$1 == "shell" && ($0 ~ /llama/ || $0 ~ /rpc-server/ || $0 ~ /bench/) && $0 !~ /ps -A/ { print }'
}

busy_reason() { # ipA ipC -> prints why not free, nothing when free
  [ -f "$MARKER" ] && echo "marker held by '$(cat "$MARKER")'"
  for ip in "$@"; do
    local p; p=$(standalone "$ip")
    [ -n "$p" ] && echo "standalone process on $ip: $(echo "$p" | head -2 | tr '\n' ';')"
  done
}

case "$cmd" in
  acquire)
    phones "$@"
    max="${3:-90}"; waited=0
    while :; do
      why=$(busy_reason "$1" "$2")
      if [ -z "$why" ]; then
        # re-check once after a short pause so two waiters cannot both take it
        sleep 5
        [ -f "$MARKER" ] || { echo "e2e" > "$MARKER"; echo "acquired at $(date +%T) after $waited min"; exit 0; }
      else
        echo "$(date +%T) waiting ($waited of $max min): $why"
      fi
      [ "$waited" -ge "$max" ] && { echo "queue did not free within $max min"; exit 1; }
      sleep 120; waited=$((waited + 2))
    done ;;
  release)
    if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "e2e" ]; then rm -f "$MARKER"; echo released
    else echo "marker is '$( [ -f "$MARKER" ] && cat "$MARKER" || echo absent)', not ours; left alone"; fi ;;
  status)
    phones "$@"
    why=$(busy_reason "$1" "$2"); echo "${why:-free}" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
