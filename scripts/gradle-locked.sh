#!/usr/bin/env bash
# Run Gradle with a machine-wide cap of 2 concurrent builds (several workers share this PC; RAM is the limit).
# Usage: bash H:/UStaaf/scripts/gradle-locked.sh <path-to-gradle-root> <gradle args...>
# Example: bash H:/UStaaf/scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug -PidSuffix=.link -PportOffset=10
set -u
ROOT="${1:?gradle root dir}"; shift
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot}"
LOCKDIR="/c/Users/DELL/AppData/Local/Temp/swarm-gradle-locks"; mkdir -p "$LOCKDIR"
slot=""
while [ -z "$slot" ]; do
  for i in 1 2; do
    d="$LOCKDIR/slot-$i"
    # steal a slot whose owner process is gone
    if [ -d "$d" ] && [ -f "$d/pid" ] && ! kill -0 "$(cat "$d/pid" 2>/dev/null)" 2>/dev/null; then rm -rf "$d"; fi
    if mkdir "$d" 2>/dev/null; then echo $$ > "$d/pid"; slot="$d"; break; fi
  done
  [ -z "$slot" ] && sleep 5
done
trap 'rm -rf "$slot"' EXIT INT TERM
cd "$ROOT" && ./gradlew --console=plain "$@"
