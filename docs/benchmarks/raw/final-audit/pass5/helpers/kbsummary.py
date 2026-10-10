import json, sys, collections
for n in sys.argv[1:]:
    rows=[json.loads(l) for l in open(n,encoding='utf-8') if l.strip()]
    st=collections.Counter(r.get('state') for r in rows)
    first,last=rows[0],rows[-1]
    dt=(last['secondsGenerating']-first['secondsGenerating'])
    wall=last['t']-first['t'] if last['t']!=first['t'] else None
    th=collections.Counter(r.get('status') for r in rows)
    rt=[r['realTps'] for r in rows if r.get('realTps')]
    print(n.split('/')[-1], 'samples',len(rows),'span_s',wall,'states',dict(st),'thermalStatus',dict(th),
          'jobsDone',first['jobsDone'],'->',last['jobsDone'],'jobsFailed',first['jobsFailed'],'->',last['jobsFailed'],
          'generating_s',round(dt,1),'share',None if not wall else round(dt/wall,3),'memPhase',set(r.get('memPhase') for r in rows))
