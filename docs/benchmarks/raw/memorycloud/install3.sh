#!/usr/bin/env bash
# install3.sh: install the debug APK on A, B, C and launch each with its name and setupDone
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK=H:/UStaaf/.claude/worktrees/agent-ac34ad0bc1b4ddd46/app/app/build/outputs/apk/debug/app-debug.apk
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for p in A B C; do
  case "$p" in A) S=192.168.242.89:5555 ;; B) S=192.168.242.23:5555 ;; C) S=192.168.242.76:5555 ;; esac
  echo "== $p install: $($ADB -s $S install -r $APK 2>&1 | tail -1)"
  bash $D/ph3.sh $p "input keyevent KEYCODE_WAKEUP; am start -n com.swarmos.app/com.swarmos.app.MainActivity --es name 'Phone $p' --ez setupDone true" | tail -1
done
