#!/usr/bin/env bash
# pull.sh: start a 200-token answer on C, force-stop the base app on worker A after 6 s, time how long until C reports DEGRADED.
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
bash $D/mc.sh C POST /memcloud/ask '{"prompt":"Write a long story about a lighthouse keeper.","maxTokens":200}' >/dev/null
sleep 6
echo "before pull: $(bash $D/mc.sh C GET /memcloud/state | python -I $D/sum.py | grep -E 'gen:' | cut -c1-90)"
T0=$(date +%s%N)
bash $D/ph.sh A "am force-stop com.swarmos.app" >/dev/null 2>&1
T1=$(date +%s%N)
echo "force-stop command took $(( (T1 - T0) / 1000000 )) ms"
for i in $(seq 1 60); do
  out=$(bash $D/mc.sh C GET /memcloud/state)
  ph=$(echo "$out" | python -I -c 'import sys,json; r=sys.stdin.read().split("\nHTTP")[0]; d=json.loads(r); print(d["phase"], d.get("lostPhone"), (d.get("generation") or {}).get("running"), (d.get("generation") or {}).get("tokens"))' 2>/dev/null)
  now=$(date +%s%N)
  echo "t+$(( (now - T0) / 1000000 )) ms: $ph"
  case "$ph" in DEGRADED*) break ;; esac
  sleep 0.3
done
