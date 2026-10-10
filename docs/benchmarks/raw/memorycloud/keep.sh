#!/usr/bin/env bash
# keep.sh: every 10 s wake phones A and C and bring the base app to the front (OriginOS freezes/kills a backgrounded app).
# Sends a key event and an activity start only; changes no setting. Stops when mc/keep.stop exists. Phone B is never addressed.
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
sleep 12
rm -f "$D/keep.stop"
while [ ! -f "$D/keep.stop" ]; do
  for p in A C; do
    timeout 8 bash $D/ph.sh $p "input keyevent KEYCODE_WAKEUP; am start -n com.swarmos.app/com.swarmos.app.MainActivity" >/dev/null 2>&1
  done
  sleep 10
done
echo keep-stopped
