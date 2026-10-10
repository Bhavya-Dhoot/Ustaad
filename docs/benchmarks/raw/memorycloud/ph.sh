#!/usr/bin/env bash
# usage: ph.sh <A|C> <shell command...>   (phone B is never addressed from here)
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
case "$1" in
  A) S=192.168.242.89:5555 ;;
  C) S=192.168.242.76:5555 ;;
  *) echo "only A or C"; exit 2 ;;
esac
shift
"$ADB" -s "$S" shell "$@" </dev/null
