#!/usr/bin/env bash
# queue.sh: poll every 2 min (up to 60 min) until phone-A-owner.txt and phone-C-owner.txt are both gone.
S=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad
for i in $(seq 1 30); do
  a=gone; c=gone; b=gone
  [ -f $S/phone-A-owner.txt ] && a=$(cat $S/phone-A-owner.txt | head -1)
  [ -f $S/phone-C-owner.txt ] && c=$(cat $S/phone-C-owner.txt | head -1)
  [ -f $S/phone-B-owner.txt ] && b=$(cat $S/phone-B-owner.txt | head -1)
  echo "$(date +%H:%M:%S) A=$a B=$b C=$c"
  if [ "$a" = gone ] && [ "$c" = gone ]; then echo QUEUE_FREE; exit 0; fi
  sleep 120
done
echo QUEUE_TIMEOUT
exit 1
