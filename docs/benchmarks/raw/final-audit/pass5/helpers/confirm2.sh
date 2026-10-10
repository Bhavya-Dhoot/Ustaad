# usage: confirm2.sh <outdir> : models/state, memcloud state, busy on both phones after a direction
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh
for p in "192.168.242.89 A" "192.168.242.23 B"; do set -- $p
  python scripts/phone-probe.py $1 get /v1/models/state > $OUTD/models-state-$2.json
  echo "$2: $(python "C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/modelsum.py" < $OUTD/models-state-$2.json | cut -c1-420)"
  python scripts/phone-probe.py $1 get /v1/memcloud/state > $OUTD/memcloud-state-$2.json
  python -c "import json; d=json.load(open('$OUTD/memcloud-state-$2.json')); print('   memcloud', d.get('phase'), 'notices', d.get('notices'), 'pendingRestore', d.get('pendingRestore'), 'released', d.get('released'))"
  python scripts/phone-probe.py $1 get /v1/sched/busy | cut -c1-170
done
