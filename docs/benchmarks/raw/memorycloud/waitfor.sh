#!/usr/bin/env bash
# exits when the base-id owner file is gone or the spike doc exists
OWN="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/base-id-owner.txt"
SPIKE="H:/UStaaf/docs/benchmarks/2026-10-10-rpc-split-spike.md"
while true; do
  o=gone; [ -f "$OWN" ] && o=$(cat "$OWN")
  s=no; [ -f "$SPIKE" ] && s=yes
  echo "$(date +%H:%M:%S) owner=$o spike=$s"
  if [ "$s" = yes ]; then echo SPIKE_LANDED; fi
  if [ "$o" = gone ] && [ "$s" = yes ]; then exit 0; fi
  sleep 45
done
