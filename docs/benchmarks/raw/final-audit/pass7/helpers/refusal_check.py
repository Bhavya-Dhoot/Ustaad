#!/usr/bin/env python3
"""Pass 7 step 3, refusal path without any stop call: start the 30B split on C, wait for the planner's answer, read both phones 30 s later. usage: refusal_check.py <out-dir>"""
import hashlib, hmac, json, os, random, sys, time, urllib.error, urllib.request
C, A = "192.168.242.76", "192.168.242.89"
BIG = "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
def call(ip, method, path, body=None, timeout=8):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None: h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try: return e.code, json.loads(raw)
        except ValueError: return e.code, {"_raw": raw[:400]}
    except Exception as e: return 0, {"_err": type(e).__name__}
def L(m): print(f"{time.strftime('%H:%M:%S')} {m}", flush=True); open(os.path.join(out, "events.log"), "a", encoding="utf-8").write(f"{time.strftime('%H:%M:%S')} {m}\n")
def snap(tag):
    row = {"tag": tag, "clock": time.strftime("%H:%M:%S")}
    for ip, n in ((C, "C"), (A, "A")):
        _, st = call(ip, "GET", "/v1/models/state"); r = st.get("roles", {})
        _, b = call(ip, "GET", "/v1/sched/busy"); _, h = call(ip, "GET", "/v1/health")
        row[n] = {"chat": (r.get("chat") or {}).get("state"), "backend": (r.get("chat") or {}).get("backend"), "embed": (r.get("embed") or {}).get("state"), "rpc": (r.get("rpc") or {}).get("state"),
                  "busy": b.get("state"), "jobsDone": b.get("jobsDone"), "jobsFailed": b.get("jobsFailed"), "ramAvailMb": h.get("ramAvailMb"), "thermal": h.get("thermalStatus")}
    _, m = call(C, "GET", "/v1/memcloud/state"); row["C_memcloud"] = {"phase": m.get("phase"), "failReason": m.get("failReason"), "notices": m.get("notices"), "pendingRestore": m.get("pendingRestore")}
    _, ma = call(A, "GET", "/v1/memcloud/state"); row["A_memcloud"] = {"phase": ma.get("phase"), "pendingRestore": ma.get("pendingRestore")}
    _, rs = call(A, "GET", "/v1/memcloud/rpc/state"); row["A_rpc"] = {"lendingTo": rs.get("lendingTo"), "state": rs.get("state")}
    L(f"{tag}: {json.dumps(row)}"); return row
snap("before")
s, b = call(C, "POST", "/v1/memcloud/start", {"file": BIG, "peers": ["Phone A"]}, timeout=30)
L(f"POST start -> HTTP {s}"); json.dump({"status": s, "body": b}, open(os.path.join(out, "start-response.json"), "w"), indent=1)
t0 = time.time(); d = {}
while time.time() - t0 < 120:
    _, d = call(C, "GET", "/v1/memcloud/state")
    L(f"phase {d.get('phase')} steps {len(d.get('steps') or [])} failReason {d.get('failReason')!r}")
    if d.get("phase") in ("FAILED", "READY", "DEGRADED"): break
    time.sleep(2)
t_ans = time.time(); json.dump(d, open(os.path.join(out, "state-at-answer.json"), "w"), indent=1)
L(f"planner answered after {t_ans - t0:.1f} s: phase {d.get('phase')}")
L("full steps: " + json.dumps(d.get("steps")))
L("full failReason: " + str(d.get("failReason")))
time.sleep(30 - (time.time() - t_ans) if time.time() - t_ans < 30 else 0)
snap("30 s after the answer")
time.sleep(30)
snap("60 s after the answer (second Keep busy read)")
