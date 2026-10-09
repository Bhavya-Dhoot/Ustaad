#!/system/bin/sh
# Phone-side: run a command, sample memory every 2 s, kill it when anon RSS or time limits are exceeded.
# Usage: sh guarded-run.sh <outprefix> <max_anon_kB> <max_seconds> <cmd> [args...]
# Writes <outprefix>.out/.err (command output), <outprefix>.mem (timeline), <outprefix>.end (reason, rc).
out=$1; maxanon=$2; maxsec=$3; shift 3
"$@" > "$out.out" 2> "$out.err" &
pid=$!
t=0; reason=exited
: > "$out.mem"
while kill -0 $pid 2>/dev/null; do
  a=$(grep RssAnon /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  f=$(grep RssFile /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  m=$(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2)
  sw=$(grep SwapFree /proc/meminfo | tr -s ' ' | cut -d' ' -f2)
  echo "t=$t anon_kB=${a:-0} file_kB=${f:-0} MemAvailable_kB=$m SwapFree_kB=$sw" >> "$out.mem"
  if [ "${a:-0}" -gt "$maxanon" ]; then reason="killed: anon RSS ${a} kB > limit $maxanon kB"; kill $pid; break; fi
  if [ $t -ge $maxsec ]; then reason="killed: time limit ${maxsec}s"; kill $pid; break; fi
  sleep 2; t=$((t+2))
done
wait $pid 2>/dev/null; rc=$?
echo "reason=$reason rc=$rc elapsed=${t}s" > "$out.end"
