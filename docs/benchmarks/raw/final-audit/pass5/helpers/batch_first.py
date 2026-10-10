#!/usr/bin/env python3
"""Item 4: start a swarm batch (bench32) on B through the API and read its status at once. usage: batch_first.py <out-dir>"""
import hashlib, hmac, json, os, random, sys, time, urllib.error, urllib.request
B = "192.168.242.23"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
def call(method, path, body=None, timeout=20):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None: h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{B}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try: return e.code, json.loads(raw)
        except ValueError: return e.code, {"_raw": raw[:200]}
    except Exception as e: return 0, {"_err": type(e).__name__}
log = []
t0 = time.time()
s, b = call("POST", "/v1/sched/batch", {"builtin": "bench32", "mode": "swarm"})
t1 = time.time()
log.append({"call": "POST /v1/sched/batch", "status": s, "body": b, "ms": round((t1 - t0) * 1000)})
print("start:", s, b, f"{(t1 - t0) * 1000:.0f} ms", flush=True)
bid = b.get("id")
s2, st = call("GET", f"/v1/sched/batch/{bid}")
t2 = time.time()
log.append({"call": "GET status at once", "status": s2, "state": st.get("state"), "done": st.get("done"), "failed": st.get("failed"), "total": st.get("total"),
            "msAfterStartReturned": round((t2 - t1) * 1000), "keys": sorted(st.keys())[:20]})
print("status at once:", s2, st.get("state"), "done", st.get("done"), "failed", st.get("failed"), "total", st.get("total"), f"{(t2 - t1) * 1000:.0f} ms after start returned", flush=True)
json.dump(st, open(os.path.join(out, "status-at-once.json"), "w"), indent=1)
while time.time() - t0 < 300:
    s3, d = call("GET", f"/v1/sched/batch/{bid}")
    if d.get("state") and d.get("state") != "running":
        break
    time.sleep(2)
json.dump(d, open(os.path.join(out, f"batch-{bid}-final.json"), "w"), indent=1)
log.append({"call": "final", "status": s3, "state": d.get("state"), "done": d.get("done"), "failed": d.get("failed"), "wallMs": d.get("wallMs"),
            "totalTokens": d.get("totalTokens"), "aggTokPerSec": d.get("aggTokPerSec"), "perPhone": d.get("perPhone")})
json.dump(log, open(os.path.join(out, "log.json"), "w"), indent=1)
print("final:", d.get("state"), d.get("done"), "/", d.get("total"), "failed", d.get("failed"), "wall", d.get("wallMs"), "agg", d.get("aggTokPerSec"), d.get("perPhone"), flush=True)
