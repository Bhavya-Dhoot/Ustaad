#!/usr/bin/env bash
# pss.sh <label> [phones]: PSS and RSS (MB) of the app and its llama children per phone, read as the app uid (run-as)
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
L="${1:-pss}"; shift
for p in ${*:-A B C}; do
  echo "$(date +%H:%M:%S) $L $p:"
  bash $D/ph3.sh $p 'tot=0; for pid in $(pidof com.swarmos.app libllama-server.so librpc-server.so); do n=$(cat /proc/$pid/cmdline | tr "\0" " " | sed "s#/data/app/[^ ]*/lib/arm64/##" | cut -c1-60); r=$(run-as com.swarmos.app cat /proc/$pid/smaps_rollup 2>/dev/null); pss=$(echo "$r" | grep "^Pss:" | tr -s " " | cut -d" " -f2); rss=$(echo "$r" | grep "^Rss:" | tr -s " " | cut -d" " -f2); echo "   pid $pid pss=$(( ${pss:-0} / 1024 )) MB rss=$(( ${rss:-0} / 1024 )) MB  $n"; tot=$((tot+${pss:-0})); done; echo "   total pss=$((tot/1024)) MB"' 2>&1 | tr -d '\r'
done
