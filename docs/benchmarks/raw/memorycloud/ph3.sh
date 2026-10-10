#!/usr/bin/env bash
# usage: ph3.sh <A|B|C> <shell command...>
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
case "$1" in
  A) S=192.168.242.89:5555 ;;
  B) S=192.168.242.23:5555 ;;
  C) S=192.168.242.76:5555 ;;
  *) echo "A, B or C"; exit 2 ;;
esac
shift
"$ADB" -s "$S" shell "$@" </dev/null
