#!/usr/bin/env python3
"""Read-only: every 10 s GET /v1/health (ramAvailMb) and /v1/sched/busy on A and B for <minutes>. usage: ram_sampler.py <minutes> <out.jsonl>"""
import hashlib, hmac, json, os, random, sys, time, urllib.request
SECRET = os.environ["SWARM_SECRET"]
def call(ip, path):
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"GET\n{path}\n{ts}\n".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    try:
        with urllib.request.urlopen(urllib.request.Request(f"http://{ip}:8787{path}", headers=h), timeout=5) as r: return json.loads(r.read().decode())
    except Exception as e: return {"_err": type(e).__name__}
t0 = time.time(); out = open(sys.argv[2], "w", encoding="utf-8", buffering=1)
while time.time() - t0 < float(sys.argv[1]) * 60:
    row = {"t": round(time.time() - t0), "clock": time.strftime("%H:%M:%S")}
    for n, ip in (("A", "192.168.242.89"), ("B", "192.168.242.23")):
        h = call(ip, "/v1/health"); b = call(ip, "/v1/sched/busy")
        row[n] = {"ramAvailMb": h.get("ramAvailMb"), "state": b.get("state"), "jobsDone": b.get("jobsDone"), "jobsFailed": b.get("jobsFailed")}
    out.write(json.dumps(row) + "\n"); time.sleep(10)
