# usage: battery.sh <label> -> appends battery of A and B to pass5/battery-log.txt; exit 3 if A < 40 %
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh
mkdir -p docs/benchmarks/raw/final-audit/pass6
rc=0
for p in "192.168.242.89 A" "192.168.242.23 B"; do set -- $p
  b=$("$ADB" -s $1:5555 shell 'dumpsys battery' | tr -d '\r')
  lvl=$(echo "$b" | grep -E "^\s*level:" | grep -o '[0-9]*'); ac=$(echo "$b" | grep -E "AC powered" | awk '{print $NF}'); usb=$(echo "$b" | grep -E "USB powered" | awk '{print $NF}'); wl=$(echo "$b" | grep -E "Wireless powered" | awk '{print $NF}'); st=$(echo "$b" | grep -E "^\s*status:" | awk '{print $NF}')
  echo "$(date +%T) $1 phone $2 battery level $lvl AC $ac USB $usb wireless $wl status $st" | tee -a docs/benchmarks/raw/final-audit/pass6/battery-log.txt
  [ "$2" = A ] && [ -n "$lvl" ] && [ "$lvl" -lt 40 ] && rc=3
done
exit $rc
