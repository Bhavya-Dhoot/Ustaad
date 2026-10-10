# usage: gateAB.sh <label> -- A and B: chat+embed READY, rpc STOPPED, thermal<=2 (reads via PC; no call to any other phone)
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh
LOG=docs/benchmarks/raw/final-audit/pass4/gate-log.txt
mkdir -p docs/benchmarks/raw/final-audit/pass4
t0=$(date +%s)
while :; do
  ok=1; line="$(date +%T) $1"
  for ip in 192.168.242.89 192.168.242.23; do
    s=$(python scripts/phone-probe.py $ip state 2>&1 | tr '\n' ' ')
    th=$(echo "$s" | grep -o 'thermal [0-9]' | grep -o '[0-9]')
    c=$(echo "$s" | grep -c "chat {'state': 'READY'"); e=$(echo "$s" | grep -c "embed {'state': 'READY'"); r=$(echo "$s" | grep -c "rpc {'state': 'STOPPED'")
    line="$line | ${ip##*.}: chat_ready=$c embed_ready=$e rpc_stopped=$r thermal=${th:-?}"
    [ "$c" = 1 ] && [ "$e" = 1 ] && [ "$r" = 1 ] && [ -n "$th" ] && [ "$th" -le 2 ] || ok=0
  done
  echo "$line" >> $LOG; echo "$line"
  [ $ok = 1 ] && break
  [ $(( $(date +%s) - t0 )) -gt 900 ] && { echo GATE-TIMEOUT; break; }
  sleep 8
done
