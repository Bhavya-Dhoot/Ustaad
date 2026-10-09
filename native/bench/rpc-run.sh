#!/usr/bin/env bash
# PC-side RPC split run. Waits for Thermal Status <= 1 on every phone involved (poll 60 s), starts the
# rpc-servers, runs llama-bench on the driver through runrss.sh, prints tok/s, load time, RSS of driver and
# servers, wlan0 byte deltas and thermal state, then kills the rpc-servers.
# Usage: rpc-run.sh <label> <driver-serial> <model-on-driver> "<srv1ip:port,...>" "<server serials space-separated>" \
#                   <ngl> "<extra llama-bench args>" [bin-variant=i8mm] [rpc-server args]
# Env: PP (256) TG (64) REPS (2) DRV_T (8) SRV_T (8) LOADONLY=1 (only measure load: -p 8 -n 1 -r 1) SKIPGUARD=1
set -u
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
LABEL="$1"; DRV="$2"; MODEL="$3"; RPCS="$4"; SERVERS="$5"; NGL="$6"; XARGS="$7"; VAR="${8:-i8mm}"; SRVARGS="${9:-}"
D=/data/local/tmp/llama; PP="${PP:-256}"; TG="${TG:-64}"; REPS="${REPS:-2}"; DRV_T="${DRV_T:-8}"; SRV_T="${SRV_T:-8}"
st() { $ADB -s "$1" shell 'dumpsys thermalservice | grep "Thermal Status" | head -1 | tr -d " \r" | cut -d: -f2' </dev/null | tr -d '\r\n'; }
if [ -z "${SKIPGUARD:-}" ]; then
  for s in $DRV $SERVERS; do
    while true; do v=$(st $s); [ -n "$v" ] && [ "$v" -le 1 ] && break; echo "[guard] $s status=$v, waiting 60 s"; sleep 60; done
  done
fi
thermal() { for s in $DRV $SERVERS; do echo "  thermal $s: $($ADB -s $s shell "sh $D/thermal-log.sh 1 1" </dev/null | tail -1 | cut -c1-90)"; done; }
echo "=== $LABEL  driver=$DRV rpc=$RPCS ngl=$NGL extra='$XARGS' bin=$VAR"
echo "start:"; thermal
for s in $SERVERS; do
  $ADB -s $s shell "kill \$(pidof librpc-server.so) 2>/dev/null; true" </dev/null >/dev/null 2>&1
  nohup $ADB -s $s shell "cd $D; LLAMA_CACHE=$D/cache ${NAGLE:+GGML_RPC_NAGLE=1} exec ./$VAR/librpc-server.so -H 0.0.0.0 -p 50052 -t $SRV_T -c $SRVARGS > rpc_srv.log 2>&1" </dev/null >/dev/null 2>&1 &
done
sleep 3
net() { $ADB -s "$1" shell "grep wlan0 /proc/net/dev | tr -s ' ' | cut -d' ' -f3,11" </dev/null | tr -d '\r'; }
for s in $DRV $SERVERS; do echo "  net0 $s $(net $s)"; done
if [ -n "${LOADONLY:-}" ]; then BARGS="-p 8 -n 1 -r 1"; else BARGS="-p $PP -n $TG -r $REPS"; fi
RPCARG=""; [ -n "$RPCS" ] && RPCARG="-rpc $RPCS"
T0=$(date +%s)
$ADB -s $DRV shell "cd $D; COOL_STATUS_MAX= sh runrss.sh $D/rpcrun ./$VAR/libllama-bench.so -m $MODEL $RPCARG -ngl $NGL -t $DRV_T $BARGS $XARGS -o md" </dev/null
echo "  wall_s=$(( $(date +%s) - T0 ))"
$ADB -s $DRV shell "cat $D/rpcrun.out | grep -E '^\||error|Error'; head -c 400 $D/rpcrun.err; cat $D/rpcrun.rss" </dev/null | cut -c1-400
for s in $DRV $SERVERS; do echo "  net1 $s $(net $s)"; done
for s in $SERVERS; do echo "  rpc-server $s: $($ADB -s $s shell "grep -E 'VmHWM|RssAnon' /proc/\$(pidof librpc-server.so | tr ' ' '\n' | head -1)/status | tr -s ' ' | tr '\n' ' '" </dev/null)"; done
echo "end:"; thermal
for s in $SERVERS; do $ADB -s $s shell "kill \$(pidof librpc-server.so) 2>/dev/null; true" </dev/null >/dev/null 2>&1; done
