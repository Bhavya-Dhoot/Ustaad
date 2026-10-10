#!/usr/bin/env bash
# cleanup3.sh: stop the Direct group on A, B, C, bring the base app to the front, report model servers and rpc state
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for p in A B C; do
  bash $D/mc3.sh $p POST /link/direct/stop | head -c 90; echo
done
sleep 5
for p in A B C; do
  echo "== $p"
  bash $D/mc3.sh $p GET /models/state | python -I -c "import sys,json; r=sys.stdin.read().split('\nHTTP')[0]; d=json.loads(r); print({k:(v['state'],v['file'],v.get('rssMb')) for k,v in d['roles'].items()})"
  bash $D/mc3.sh $p GET /memcloud/rpc/state | python -I -c "import sys,json; r=sys.stdin.read().split('\nHTTP')[0]; d=json.loads(r); print('rpc', d['state'], 'lendingTo', d.get('lendingTo'))"
  bash $D/ph3.sh $p "am start -n com.swarmos.app/com.swarmos.app.MainActivity >/dev/null 2>&1; dumpsys window | grep mCurrentFocus | head -1; pidof librpc-server.so"
done
