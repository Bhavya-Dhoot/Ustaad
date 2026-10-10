#!/usr/bin/env bash
# snap.sh <label> [phones...]: thermal status, skin temperatures, MemAvailable, SwapFree and standalone llama processes per phone
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
L="${1:-snap}"; shift
PH="${*:-A B C}"
for p in $PH; do
  out=$(bash $D/ph3.sh $p "dumpsys thermalservice | grep -E 'Thermal Status|mType=3, mName=skin' | head -3 | tr -d '\t' | cut -c1-60; grep -E 'MemAvailable|SwapFree' /proc/meminfo | tr -s ' '; ps -A -o USER,NAME | grep -E 'libllama|librpc' | grep ' shell\|^shell' | head -3" 2>&1 | tr '\n' ';')
  echo "$(date +%H:%M:%S) $L $p: $out"
done
