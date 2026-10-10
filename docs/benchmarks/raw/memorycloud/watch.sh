#!/usr/bin/env bash
# watch.sh <seconds> [phases...]  poll C's /memcloud/state every 3 s, print a summary line block when the phase or step changes,
# stop at the first of the given phases (default READY FAILED DEGRADED) or after <seconds>.
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
SECS=${1:-120}; shift
STOP="${*:-READY FAILED DEGRADED}"
END=$(( $(date +%s) + SECS ))
last=""
while [ "$(date +%s)" -lt "$END" ]; do
  out=$(bash $D/mc.sh C GET /memcloud/state | python -I $D/sum.py)
  ph=$(echo "$out" | head -1 | sed 's/phase=\([A-Z_]*\).*/\1/')
  key="$(echo "$out" | head -1 | cut -c1-40)$(echo "$out" | grep -c .)"
  echo "== $(date +%H:%M:%S)"
  echo "$out"
  for s in $STOP; do [ "$ph" = "$s" ] && exit 0; done
  sleep 3
done
echo TIMEOUT
