# usage: battery6A.sh <label> [C]: appends battery of Phone A (and optionally Phone C) to pass6/battery-log.txt; exit 3 if A < 45 %. Never touches Phone B.
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh >/dev/null
mkdir -p docs/benchmarks/raw/final-audit/pass6
rc=0
list="192.168.242.89 A"; [ "$2" = C ] && list="$list 192.168.242.76 C"
set -- $list
while [ $# -ge 2 ]; do ip=$1; n=$2; shift 2
  b=$("$ADB" -s $ip:5555 shell 'dumpsys battery' | tr -d '\r')
  lvl=$(echo "$b" | grep -E "^\s*level:" | grep -o '[0-9]*'); ac=$(echo "$b" | grep -E "AC powered" | awk '{print $NF}'); usb=$(echo "$b" | grep -E "USB powered" | awk '{print $NF}'); wl=$(echo "$b" | grep -E "Wireless powered" | awk '{print $NF}'); st=$(echo "$b" | grep -E "^\s*status:" | awk '{print $NF}')
  echo "$(date +%T) $ip phone $n battery level $lvl AC $ac USB $usb wireless $wl status $st" | tee -a docs/benchmarks/raw/final-audit/pass6/battery-log.txt
  [ "$n" = A ] && [ -n "$lvl" ] && [ "$lvl" -lt 45 ] && rc=3
done
exit $rc
