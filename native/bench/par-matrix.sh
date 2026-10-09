#!/usr/bin/env bash
# PC-side: for each -np value start llama-server on the phone, then fire N concurrent 128-token chat
# requests (parallel.sh) and log thermal state + server RSS around each run.
# Usage: native/bench/par-matrix.sh <adb-serial> <bin-dir> <model> <threads> "<np:N,N,N ...>"   e.g. "1:1,4 2:1,2 4:1,2,4"
set -u
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SER="$1"; BIN="$2"; MODEL="$3"; T="$4"; PLAN="$5"; EXTRA="${6:-}"
PORT=18080; D=/data/local/tmp/llama
for item in $PLAN; do
  np="${item%%:*}"; ns="${item#*:}"; ctx=$((2048*np))
  $ADB -s "$SER" shell "kill \$(pidof libllama-server.so) 2>/dev/null; true" </dev/null >/dev/null 2>&1
  sleep 2
  nohup $ADB -s "$SER" shell "cd $D; exec $BIN/libllama-server.so -m $MODEL --host 127.0.0.1 --port $PORT -t $T -c $ctx -np $np --reasoning off $EXTRA > server_par.log 2>&1" </dev/null >/dev/null 2>&1 &
  $ADB -s "$SER" shell "until curl -s http://127.0.0.1:$PORT/health | grep -q ok; do sleep 1; done" </dev/null
  $ADB -s "$SER" shell "cd $D; curl -s -o /dev/null http://127.0.0.1:$PORT/v1/chat/completions -H 'Content-Type: application/json' -d @req_chat_nothink.json" </dev/null   # warm the weights
  for n in ${ns//,/ }; do
    echo "== np=$np c=$ctx threads=$T concurrent=$n $EXTRA"
    $ADB -s "$SER" shell "cd $D; sh thermal-log.sh 1 1" </dev/null
    $ADB -s "$SER" shell "cd $D; sh parallel.sh $PORT $n 128; echo \"server RSS/HWM: \$(grep -E 'VmRSS|VmHWM' /proc/\$(pidof libllama-server.so)/status | tr -s ' ' | tr '\n' ' ')\"; sh thermal-log.sh 1 1" </dev/null
  done
done
$ADB -s "$SER" shell "kill \$(pidof libllama-server.so) 2>/dev/null; true" </dev/null >/dev/null 2>&1
