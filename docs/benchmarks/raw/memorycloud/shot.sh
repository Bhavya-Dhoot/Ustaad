#!/usr/bin/env bash
# shot.sh <A|B|C> <out.png>: print the focused window, bring the app to front, screenshot
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
case "$1" in A) S=192.168.242.89:5555 ;; B) S=192.168.242.23:5555 ;; C) S=192.168.242.76:5555 ;; esac
bash $D/ph3.sh "$1" "am start -n com.swarmos.app/com.swarmos.app.MainActivity >/dev/null 2>&1; sleep 1; dumpsys window | grep mCurrentFocus | head -1"
$ADB -s $S exec-out screencap -p > "$2"
ls -la "$2" | cut -c20-80
