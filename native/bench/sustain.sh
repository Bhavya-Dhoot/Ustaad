#!/system/bin/sh
# Phone-side sustained-load test against a running llama-server (default 127.0.0.1:8080).
# Back-to-back 128-token generations (ignore_eos, fresh slot each time so context does not grow),
# logging tok/s per request, and every 30 s thermal status, skin temp, cpu0/prime temps, cpu freqs.
# Usage: sh sustain.sh <minutes> <logfile> [port]
MIN=${1:-10}; LOG=${2:-/data/local/tmp/llama/sustain.log}; PORT=${3:-8080}
END=$(( $(date +%s) + MIN*60 ))
: > "$LOG"; rm -f "$LOG.abort"
( while [ $(date +%s) -lt $END ]; do
    ts=$(date +%s)
    st=$(dumpsys thermalservice | grep "Thermal Status" | head -1 | tr -d '\r')
    skin=$(for z in /sys/class/thermal/thermal_zone*; do [ "$(cat $z/type)" = quiet_therm ] && cat $z/temp; done)
    cpu=$(for z in /sys/class/thermal/thermal_zone*; do [ "$(cat $z/type)" = cpu-1-0-0 ] && cat $z/temp; done)
    f0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq); f6=$(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq)
    case "$st" in *": 4"*|*": 5"*|*": 6"*) echo ABORT_THERMAL >> "$LOG"; touch "$LOG.abort";; esac
    echo "T $ts $st skin_mC=$skin cpu1-0-0_mC=$cpu f0=$f0 f6=$f6" >> "$LOG"
    sleep 30
  done ) &
TPID=$!
while [ $(date +%s) -lt $END ] && [ ! -e "$LOG.abort" ]; do
  r=$(curl -s -m 120 http://127.0.0.1:$PORT/completion -H 'Content-Type: application/json' \
      -d '{"prompt":"Write a long story about a robot who learns to paint.","n_predict":128,"ignore_eos":true,"temperature":0.8,"cache_prompt":false}')
  p=$(echo "$r" | grep -o '"predicted_per_second":[0-9.]*' | head -1 | cut -d: -f2)
  q=$(echo "$r" | grep -o '"prompt_per_second":[0-9.]*' | head -1 | cut -d: -f2)
  echo "R $(date +%s) tg_tok_s=$p pp_tok_s=$q" >> "$LOG"
done
kill $TPID 2>/dev/null
echo DONE >> "$LOG"
