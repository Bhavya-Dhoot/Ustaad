#!/usr/bin/env bash
# pull3.sh: start a 200-token answer on C, force-stop the base app on lender B after 6 s, time DEGRADED, screenshot C
export MSYS_NO_PATHCONV=1
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
bash $D/mc3.sh C POST /memcloud/ask '{"prompt":"Write a long story about a lighthouse keeper.","maxTokens":200}' >/dev/null
sleep 6
echo "before pull: $(bash $D/mc3.sh C GET /memcloud/state | python -I $D/sum.py | grep 'gen:' | cut -c1-110)"
T0=$(date +%s%N)
bash $D/ph3.sh B "am force-stop com.swarmos.app" >/dev/null 2>&1
T1=$(date +%s%N)
echo "force-stop command took $(( (T1 - T0) / 1000000 )) ms"
for i in $(seq 1 40); do
  out=$(bash $D/mc3.sh C GET /memcloud/state)
  ph=$(echo "$out" | python -I -c 'import sys,json; r=sys.stdin.read().split("\nHTTP")[0]; d=json.loads(r); print(d["phase"], d.get("lostPhone"), (d.get("generation") or {}).get("running"), (d.get("generation") or {}).get("tokens"))' 2>/dev/null)
  now=$(date +%s%N)
  echo "t+$(( (now - T0) / 1000000 )) ms: $ph"
  case "$ph" in DEGRADED*) break ;; esac
  sleep 0.3
done
bash $D/shot.sh C H:/UStaaf/.claude/worktrees/agent-ac34ad0bc1b4ddd46/docs/design/shots/memcloud-degraded-30b.png
