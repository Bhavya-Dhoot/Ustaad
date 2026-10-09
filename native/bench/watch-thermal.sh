#!/usr/bin/env bash
# PC-side: one line per phone every N seconds (default 60): time, phone, Thermal Status, skin temp.
INT="${1:-60}"; export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
while true; do
  for h in 192.168.242.89:5555 192.168.242.23:5555 192.168.242.76:5555; do
    echo "$h $($ADB -s $h shell 'sh /data/local/tmp/llama/thermal-log.sh 1 1' </dev/null 2>&1 | tail -1 | cut -c1-110)"
  done
  sleep "$INT"
done
