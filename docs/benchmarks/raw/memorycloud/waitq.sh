#!/usr/bin/env bash
# waitq.sh: wait up to ~9 min for the queue waiter's output to say QUEUE_FREE
O=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/tasks/brbeunovz.output
for i in $(seq 1 18); do
  tail -1 "$O"
  if grep -q QUEUE_FREE "$O"; then echo FREE; exit 0; fi
  if grep -q QUEUE_TIMEOUT "$O"; then echo TIMEOUT; exit 1; fi
  sleep 30
done
echo STILL_WAITING
