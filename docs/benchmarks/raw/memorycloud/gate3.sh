#!/usr/bin/env bash
# gate3.sh: wait up to N seconds (default 600) until A, B and C all read Thermal Status 0 or 1
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
END=$(( $(date +%s) + ${1:-600} ))
while [ "$(date +%s)" -lt "$END" ]; do
  ok=1; line=""
  for p in A B C; do
    th=$(bash $D/ph3.sh $p "dumpsys thermalservice | grep 'Thermal Status' | head -1" | tr -d '\r' | sed 's/.*: //')
    line="$line $p=$th"
    if [ -z "$th" ] || [ "$th" -gt 1 ]; then ok=0; fi
  done
  echo "$(date +%H:%M:%S)$line"
  if [ $ok = 1 ]; then echo GATE_OPEN; exit 0; fi
  sleep 20
done
echo GATE_TIMEOUT
