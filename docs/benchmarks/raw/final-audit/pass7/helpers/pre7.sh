# usage: pre7.sh <label> : for A and C: focus + wakefulness (wake and dismiss if needed), thermal, battery, models/busy summary. Waits (max 10 min) while a phone is at Thermal Status 3 or more. Never touches Phone B.
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh >/dev/null
LOG=docs/benchmarks/raw/final-audit/pass7/pre-log.txt
mkdir -p docs/benchmarks/raw/final-audit/pass7
t0=$(date +%s)
while :; do
  hot=0; line="$(date +%T) $1"
  for p in "192.168.242.89 A" "192.168.242.76 C"; do set -- $p
    ser=$1:5555
    o=$("$ADB" -s $ser shell 'dumpsys window | grep mCurrentFocus; dumpsys power | grep mWakefulness=; dumpsys thermalservice | grep "Thermal Status" | head -1; dumpsys battery' | tr -d '\r')
    front=$(echo "$o" | grep -c "mCurrentFocus=.*com.swarmos.app/com.swarmos.app.MainActivity"); awake=$(echo "$o" | grep -c "mWakefulness=Awake"); th=$(echo "$o" | grep "Thermal Status" | awk '{print $NF}')
    lvl=$(echo "$o" | grep -E "^\s*level:" | grep -o '[0-9]*'); ac=$(echo "$o" | grep "AC powered" | awk '{print $NF}')
    if [ "$front" != 1 ] || [ "$awake" != 1 ]; then bash /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/wake.sh $1; "$ADB" -s $ser shell 'am start -n com.swarmos.app/.MainActivity' >/dev/null 2>&1; line="$line | $2: NOT-IN-FRONT-OR-ASLEEP(front=$front awake=$awake) -> woke and am start"; fi
    st=$(python scripts/phone-probe.py $1 state 2>&1 | tr '\n' ' ')
    c=$(echo "$st" | grep -c "chat {'state': 'READY', 'backend': 'GPU'"); e=$(echo "$st" | grep -c "embed {'state': 'READY'"); r=$(echo "$st" | grep -c "rpc {'state': 'STOPPED'")
    line="$line | $2: front=$front awake=$awake thermal=$th battery=$lvl AC=$ac chatGPU=$c embed=$e rpcStopped=$r"
    [ -n "$th" ] && [ "$th" -ge 3 ] && hot=1
  done
  echo "$line" | tee -a $LOG
  [ $hot = 0 ] && break
  [ $(( $(date +%s) - t0 )) -gt 600 ] && { echo "HOT-WAIT-TIMEOUT" | tee -a $LOG; break; }
  sleep 20
done
