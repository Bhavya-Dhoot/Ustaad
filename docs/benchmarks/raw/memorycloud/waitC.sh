#!/usr/bin/env bash
# waitC.sh: wait (up to ~9 min) until phone C answers adb and its 1-minute load average is below 6.
export MSYS_NO_PATHCONV=1
ADB=/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for i in $(seq 1 14); do
  $ADB connect 192.168.242.76:5555 >/dev/null 2>&1
  out=$(timeout 20 bash $D/ph.sh C "uptime; pidof com.swarmos.app; dumpsys thermalservice | grep 'Thermal Status' | head -1; ps -A -o PID,USER,NAME | grep -E 'libllama|librpc'" 2>&1 | tr '\n' ' ')
  echo "$(date +%H:%M:%S) $out"
  la=$(echo "$out" | sed -n 's/.*load average: \([0-9.]*\),.*/\1/p')
  if [ -n "$la" ] && [ "$(echo "$la < 6" | bc 2>/dev/null || echo 0)" = 1 ]; then echo CALM; exit 0; fi
  sleep 30
done
echo NOT_CALM
exit 1
