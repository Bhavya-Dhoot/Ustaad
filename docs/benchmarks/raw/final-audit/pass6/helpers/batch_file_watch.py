#!/usr/bin/env python3
"""Watches the batch file of a swarm batch on the coordinator phone: finds the new batches/batch-<id>.json (run-as, read only), polls the
batch status, and reads the file right after the status stops being 'running'. usage: batch_file_watch.py <coordinator-ip> <out-dir> [timeout-s]"""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, out = sys.argv[1], sys.argv[2]
tmo = float(sys.argv[3]) if len(sys.argv) > 3 else 300
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
def adb(*a):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *a], capture_output=True, text=True, timeout=30, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "")
def call(path):
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"GET\n{path}\n{ts}\n".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    try:
        with urllib.request.urlopen(urllib.request.Request(f"http://{ip}:8787{path}", headers=h), timeout=4) as r: return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e: return e.code, {}
    except Exception as e: return 0, {"_err": type(e).__name__}
def ls():
    return set(x.strip() for x in adb("shell", "run-as com.swarmos.app ls files/batches 2>/dev/null").split() if x.strip())
log = open(os.path.join(out, "watch.log"), "w", encoding="utf-8", buffering=1)
def L(m):
    l = f"{time.strftime('%H:%M:%S')} {m}"; log.write(l + "\n"); print(l, flush=True)
pre = ls(); L(f"files before: {len(pre)}")
t0 = time.time(); new = None
while time.time() - t0 < tmo and new is None:
    cur = ls() - pre
    if cur: new = sorted(cur)[0]
    else: time.sleep(0.4)
if not new: L("no new batch file"); sys.exit(2)
bid = new.replace("batch-", "").replace(".json", "")
L(f"new file {new} (id {bid}) seen {time.time() - t0:.1f} s after the watcher started")
first = adb("shell", f"run-as com.swarmos.app cat files/batches/{new}")
try: fj = json.loads(first); L(f"first read of the file: state={fj.get('state')} done={fj.get('done')} total={fj.get('total')}")
except Exception as e: fj = None; L(f"first read not JSON: {first[:80]!r}")
open(os.path.join(out, "file-first-read.json"), "w").write(first)
st = {}
while time.time() - t0 < tmo:
    s, st = call(f"/v1/sched/batch/{bid}")
    if s == 200 and st.get("state") and st.get("state") != "running": break
    time.sleep(0.2)
t_done = time.time()
txt = adb("shell", f"run-as com.swarmos.app cat files/batches/{new}")
t_read = time.time()
try: d = json.loads(txt); L(f"status {st.get('state')} seen; file read {t_read - t_done:.2f} s later: state={d.get('state')} done={d.get('done')} failed={d.get('failed')} total={d.get('total')}")
except Exception: d = None; L(f"file at done not JSON: {txt[:80]!r}")
open(os.path.join(out, "file-at-done.json"), "w").write(txt)
json.dump(st, open(os.path.join(out, "status-at-done.json"), "w"), indent=1)
json.dump({"id": bid, "statusState": st.get("state"), "fileState": None if d is None else d.get("state"), "fileReadSecondsAfterStatusDone": round(t_read - t_done, 2)}, open(os.path.join(out, "summary.json"), "w"))
