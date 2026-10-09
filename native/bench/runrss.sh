#!/system/bin/sh
# Phone-side helper: run a command, sample /proc/<pid>/status once a second, record peak RSS,
# plus thermal / cpufreq-cap context before and after, and mean cpu0/cpu6 clock during the run.
# Usage: [KEEPAWAKE=1] runrss.sh <outprefix> <cmd> [args...]   -> <outprefix>.out .err .rss
# .rss: rc=<exit> VmHWM_kB=<peak RSS incl. mmapped file pages> RssAnonMax_kB=<peak anonymous>
#       avgf0=<mean cpu0 kHz> avgf6=<mean cpu6 kHz> PRE: ... POST: ...
# COOL_MC / COOL_MAX: wait for the skin temp to drop before starting (cold-start protocol).
# KEEPAWAKE=1 sends KEYCODE_WAKEUP every 5 s so the screen stays on (OriginOS lifts the cpufreq cap when awake).
out=$1; shift
Z=/sys/class/thermal
snap() {
  skin=""; for z in $Z/thermal_zone*; do [ "$(cat $z/type)" = quiet_therm ] && skin=$(cat $z/temp); done
  cl0=""; cl1=""
  for d in $Z/cooling_device*; do
    case "$(cat $d/type)" in cpu-cluster0) cl0=$(cat $d/cur_state);; cpu-cluster1) cl1=$(cat $d/cur_state);; esac
  done
  st=$(dumpsys thermalservice | grep "Thermal Status" | head -1 | tr -d ' \r' | cut -d: -f2)
  echo "skin_mC=$skin status=$st cool0=$cl0 cool1=$cl1 max0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) max6=$(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_max_freq)"
}
# COOL_MC=<milli-C> waits (max COOL_MAX s) until the skin sensor is at or below that temperature.
w=0
# Thermal guard: COOL_STATUS_MAX=1 waits (polling every 60 s, max COOL_MAX s) until Thermal Status <= 1.
if [ -n "$COOL_STATUS_MAX" ]; then
  while [ $w -lt ${COOL_MAX:-3600} ]; do
    st=$(dumpsys thermalservice | grep "Thermal Status" | head -1 | tr -d ' \r' | cut -d: -f2)
    [ -n "$st" ] && [ "$st" -le "$COOL_STATUS_MAX" ] && break
    sleep 60; w=$((w+60))
  done
fi
if [ -n "$COOL_MC" ]; then
  while [ $w -lt ${COOL_MAX:-900} ]; do
    sk=""; for z in $Z/thermal_zone*; do [ "$(cat $z/type)" = quiet_therm ] && sk=$(cat $z/temp); done
    [ -n "$sk" ] && [ "$sk" -le "$COOL_MC" ] && break
    sleep 10; w=$((w+10))
  done
fi
pre=$(snap)
"$@" > "$out.out" 2> "$out.err" &
pid=$!
if [ "$KEEPAWAKE" = 1 ]; then
  ( while kill -0 $pid 2>/dev/null; do input keyevent KEYCODE_WAKEUP; sleep 5; done ) &
fi
hwm=0; anon=0; n=0; s0=0; s6=0
while kill -0 $pid 2>/dev/null; do
  h=$(grep VmHWM /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  a=$(grep RssAnon /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  [ -n "$h" ] && hwm=$h
  [ -n "$a" ] && [ "$a" -gt "$anon" ] && anon=$a
  f0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq); f6=$(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq)
  n=$((n+1)); s0=$((s0+f0)); s6=$((s6+f6))
  sleep 1
done
wait $pid; rc=$?
post=$(snap)
[ $n -eq 0 ] && n=1
echo "rc=$rc cool_wait_s=$w VmHWM_kB=$hwm RssAnonMax_kB=$anon avgf0=$((s0/n)) avgf6=$((s6/n)) PRE: $pre POST: $post" > "$out.rss"
