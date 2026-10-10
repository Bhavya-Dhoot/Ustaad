# usage: gateA.sh <label> -- Phone A only: chat+embed READY, rpc STOPPED, thermal<=2
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh
LOG=docs/benchmarks/raw/final-audit/pass2/gate-log.txt
mkdir -p docs/benchmarks/raw/final-audit/pass2
t0=$(date +%s)
while :; do
  s=$(python scripts/phone-probe.py 192.168.242.89 state 2>&1 | tr '\n' ' ')
  th=$(echo "$s" | grep -o 'thermal [0-9]' | grep -o '[0-9]')
  c=$(echo "$s" | grep -c "chat {'state': 'READY'"); e=$(echo "$s" | grep -c "embed {'state': 'READY'"); r=$(echo "$s" | grep -c "rpc {'state': 'STOPPED'")
  line="$(date +%T) $1 | A: chat_ready=$c embed_ready=$e rpc_stopped=$r thermal=${th:-?}"
  echo "$line" >> $LOG; echo "$line"
  [ "$c" = 1 ] && [ "$e" = 1 ] && [ "$r" = 1 ] && [ -n "$th" ] && [ "$th" -le 2 ] && break
  [ $(( $(date +%s) - t0 )) -gt 600 ] && { echo GATE-TIMEOUT; break; }
  sleep 8
done
