#!/usr/bin/env bash
# watch3.sh <seconds> <stop phases...>: poll C /memcloud/state every 5 s; every 30 s also MemAvailable/SwapFree/thermal of A, B, C
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
SECS=${1:-120}; shift
STOP="${*:-READY FAILED DEGRADED}"
END=$(( $(date +%s) + SECS ))
i=0
while [ "$(date +%s)" -lt "$END" ]; do
  out=$(bash $D/mc3.sh C GET /memcloud/state | python -I $D/sum.py)
  ph=$(echo "$out" | head -1 | sed 's/phase=\([A-Z_]*\).*/\1/')
  echo "== $(date +%H:%M:%S)"
  echo "$out" | head -9 | cut -c1-230
  i=$((i+1))
  if [ $((i % 6)) = 1 ]; then
    for p in A B C; do
      echo "   mem $p: $(bash $D/ph3.sh $p "grep -E 'MemAvailable|SwapFree' /proc/meminfo | tr -s ' ' | tr '\n' ' '; dumpsys thermalservice | grep 'Thermal Status' | head -1" | tr -d '\r')"
    done
  fi
  for s in $STOP; do [ "$ph" = "$s" ] && exit 0; done
  sleep 5
done
echo TIMEOUT
