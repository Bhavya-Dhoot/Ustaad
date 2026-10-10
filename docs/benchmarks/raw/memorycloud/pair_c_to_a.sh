#!/usr/bin/env bash
# Phone C (base app, port 8787) adopts phone A's current pairing payload (debug unsigned route) through the deep link.
# Only A and C are addressed. Usage: pair_c_to_a.sh [dev]   ("dev" puts C back on the debug dev secret)
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
if [ "${1:-}" = dev ]; then
  JSON='{"name":"Phone C","nodeId":"82d2676d","ips":["192.168.242.76"],"port":8787,"secret":"swarmos-dev-secret","p2p":null}'
else
  JSON="$(curl -s -m 10 http://192.168.242.89:8787/v1/link/pairing-payload)"
fi
D64="$(printf '%s' "$JSON" | base64 -w0 | tr '+/' '-_' | tr -d '=')"
bash $D/ph.sh C am start -a android.intent.action.VIEW -d "'swarmos://pair?d=$D64'" -n com.swarmos.app/com.swarmos.app.MainActivity
