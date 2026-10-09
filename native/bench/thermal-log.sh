#!/system/bin/sh
# Phone-side: print one thermal/cpufreq line every N seconds for M samples.
# Usage: sh thermal-log.sh <interval_s> <samples>
Z=/sys/class/thermal
for i in $(seq 1 ${2:-40}); do
  skin=""; for z in $Z/thermal_zone*; do [ "$(cat $z/type)" = quiet_therm ] && skin=$(cat $z/temp); done
  cl0=""; cl1=""
  for d in $Z/cooling_device*; do
    case "$(cat $d/type)" in cpu-cluster0) cl0=$(cat $d/cur_state);; cpu-cluster1) cl1=$(cat $d/cur_state);; esac
  done
  st=$(dumpsys thermalservice | grep "Thermal Status" | head -1 | tr -d ' \r' | cut -d: -f2)
  echo "$(date +%T) skin_mC=$skin status=$st cool0=$cl0 cool1=$cl1 max0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) max6=$(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_max_freq) awake=$(dumpsys power | grep -c 'mWakefulness=Awake')"
  sleep ${1:-15}
done
