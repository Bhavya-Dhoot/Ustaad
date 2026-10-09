#!/system/bin/sh
# Phone-side helper: run a command, sample /proc/<pid>/status once a second, record peak RSS.
# Usage: runrss.sh <outprefix> <cmd> [args...]   -> <outprefix>.out .err .rss
# .rss line: rc=<exit> VmHWM_kB=<peak total RSS incl. mmapped file pages> RssAnonMax_kB=<peak anonymous>
out=$1; shift
"$@" > "$out.out" 2> "$out.err" &
pid=$!
hwm=0; anon=0
while kill -0 $pid 2>/dev/null; do
  h=$(grep VmHWM /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  a=$(grep RssAnon /proc/$pid/status 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
  [ -n "$h" ] && hwm=$h
  [ -n "$a" ] && [ "$a" -gt "$anon" ] && anon=$a
  sleep 1
done
wait $pid
echo "rc=$? VmHWM_kB=$hwm RssAnonMax_kB=$anon" > "$out.rss"
