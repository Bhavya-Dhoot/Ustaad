#!/usr/bin/env bash
# Reconnect both iQOO phones over wireless ADB and list them.
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
"$ADB" start-server >/dev/null 2>&1
"$ADB" mdns services 2>/dev/null | grep _adb._tcp | awk '{print $3}' | while read -r hp; do "$ADB" connect "$hp"; done
"$ADB" connect 192.168.242.89:5555; "$ADB" connect 192.168.242.23:5555; "$ADB" connect 192.168.242.76:5555
"$ADB" devices -l | grep -v sweetin
