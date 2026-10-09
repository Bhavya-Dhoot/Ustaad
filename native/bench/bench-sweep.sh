#!/usr/bin/env bash
# PC-side driver: llama-bench thread sweep on one phone over ADB, plus peak RSS per setting.
# Usage: native/bench/bench-sweep.sh <adb-serial> <bin-dir-on-phone> <model-on-phone> <label> [reps] [configs...]
# A config is "<threads>[:<hexmask>]", e.g. 6 or 2:c0 (taskset mask; c0 = cpus 6,7; 3f = cpus 0-5).
# Output: one line per config: label threads mask pp512 tok/s +- sd, tg128 tok/s +- sd, VmHWM MB, RssAnon MB
set -u
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SER="$1"; BIN="$2"; MODEL="$3"; LABEL="$4"; REPS="${5:-3}"; shift 5 2>/dev/null || shift $#
CONFIGS=("$@"); [ ${#CONFIGS[@]} -eq 0 ] && CONFIGS=(2 4 6 8)
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
$ADB -s "$SER" push "$(cygpath -m "$HERE")/runrss.sh" /data/local/tmp/llama/runrss.sh >/dev/null </dev/null
for cfg in "${CONFIGS[@]}"; do
  t="${cfg%%:*}"; mask=""; [ "$cfg" != "$t" ] && mask="${cfg#*:}"
  pre=""; [ -n "$mask" ] && pre="taskset $mask"
  $ADB -s "$SER" shell "cd /data/local/tmp/llama; COOL_MC=${COOL_MC:-} COOL_MAX=${COOL_MAX:-900} KEEPAWAKE=${KEEPAWAKE:-0} sh runrss.sh /data/local/tmp/llama/sweep $pre $BIN/libllama-bench.so -m $MODEL -p ${PP:-512} -n ${TG:-128} -t $t -r $REPS -o json" </dev/null
  json=$($ADB -s "$SER" shell "cat /data/local/tmp/llama/sweep.out" </dev/null | tr -d '\r')
  rss=$($ADB -s "$SER" shell "cat /data/local/tmp/llama/sweep.rss" </dev/null | tr -d '\r')
  echo "$json" | python -I -c "
import sys,json,re
rss='''$rss'''
try:
    d=json.load(sys.stdin)
except Exception as e:
    print('$LABEL','t=$t','mask=$mask','PARSE_FAIL',rss); sys.exit()
pp=[x for x in d if x['n_gen']==0]; tg=[x for x in d if x['n_gen']>0]
f=lambda x:('%.2f +- %.2f'%(x[0]['avg_ts'],x[0]['stddev_ts'])) if x else 'n/a'
m=dict(re.findall(r'(VmHWM_kB|RssAnonMax_kB)=(\d+)',rss))
hw=int(m.get('VmHWM_kB',0))//1024; an=int(m.get('RssAnonMax_kB',0))//1024
pre=rss.split('PRE:')[1].split('POST:')[0].strip() if 'PRE:' in rss else ''
post=rss.split('POST:')[1].strip() if 'POST:' in rss else ''
af=dict(re.findall(r'(avgf\d)=(\d+)',rss))
print('$LABEL','threads=$t','mask=${mask:-none}','awake=${KEEPAWAKE:-0}','cool_wait_s='+dict(re.findall(r'(cool_wait_s)=(\d+)',rss)).get('cool_wait_s','0'),'pp${PP:-512}=',f(pp),'tg${TG:-128}=',f(tg),'VmHWM_MB=',hw,'RssAnon_MB=',an,'avgGHz(c0/c6)=%.2f/%.2f'%(int(af.get('avgf0',0))/1e6,int(af.get('avgf6',0))/1e6),'| PRE',pre,'| POST',post)
"
done
