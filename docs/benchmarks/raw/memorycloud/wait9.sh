#!/usr/bin/env bash
# waits up to ~9 min for: spike doc present AND base-id owner file gone. prints state each check.
OWN="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/base-id-owner.txt"
SPIKE="H:/UStaaf/docs/benchmarks/2026-10-10-rpc-split-spike.md"
for i in $(seq 1 12); do
  o=gone; [ -f "$OWN" ] && o=$(cat "$OWN")
  s=no; [ -f "$SPIKE" ] && s=yes
  echo "$(date +%H:%M:%S) owner=$o spike=$s"
  if [ "$o" = gone ] && [ "$s" = yes ]; then echo READY; exit 0; fi
  sleep 45
done
