#!/usr/bin/env bash
# gate.sh: wait (up to ~9 min) until phones A and C run no standalone (shell-user) llama/rpc process and Thermal Status <= 1.
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for i in $(seq 1 9); do
  ok=1; line=""
  for p in A C; do
    pids=$(bash $D/ph.sh $p "ps -A -o PID,USER,NAME | grep -E 'libllama-(bench|server|cli)|librpc-server' | grep ' shell '" | tr -d '\r' | awk '{print $1}' | tr '\n' ' ')
    th=$(bash $D/ph.sh $p "dumpsys thermalservice | grep 'Thermal Status' | head -1" | tr -d '\r' | sed 's/.*: //')
    line="$line $p[standalone='$pids' thermal=$th]"
    [ -n "$pids" ] && ok=0
    [ -z "$th" ] && ok=0
    [ -n "$th" ] && [ "$th" -gt 1 ] && ok=0
  done
  echo "$(date +%H:%M:%S)$line"
  if [ $ok = 1 ]; then echo GATE_OPEN; exit 0; fi
  sleep 60
done
echo GATE_CLOSED
exit 1
