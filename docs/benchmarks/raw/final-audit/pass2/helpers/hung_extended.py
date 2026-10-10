#!/usr/bin/env python3
"""Phone A only. Diagnostic beyond scripts/hardening-check.py step h: freeze the chat server (SIGSTOP via run-as, as the script
does) and watch for up to <limit> s whether the app notices (pid or state change); then SIGCONT. usage: hung_extended.py <out> <limit>"""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
IP, SER, PKG = "192.168.242.89", "192.168.242.89:5555", "com.swarmos.app"
out, limit = sys.argv[1], int(sys.argv[2])
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]


def adb(*a):
    p = subprocess.run([ADB, "-s", SER, *a], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def call(path, timeout=4):
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"GET\n{path}\n{ts}\n".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    try:
        with urllib.request.urlopen(urllib.request.Request(f"http://{IP}:8787{path}", headers=h), timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except Exception as e:
        return {"_err": type(e).__name__}


def chat():
    return (call("/v1/models/state").get("roles") or {}).get("chat") or {}


r0 = chat()
pid = r0.get("pid")
busy0 = call("/v1/sched/busy")
t0 = time.time()
k = adb("shell", "run-as", PKG, "kill", "-STOP", str(pid))
rows, first = [], None
while time.time() - t0 < limit:
    r = chat()
    b = call("/v1/sched/busy", 3)
    t = round(time.time() - t0, 1)
    rows.append([t, r.get("state"), r.get("pid"), b.get("state"), b.get("jobsDone"), b.get("jobsFailed"), b.get("jobsCancelled")])
    if r.get("pid") != pid or r.get("state") != "READY":
        first = rows[-1]
        break
    time.sleep(2)
cont = adb("shell", "run-as", PKG, "kill", "-CONT", str(pid))
after = chat()
json.dump({"pid": pid, "busyBefore": busy0, "kill": k, "limit": limit, "firstChange": first, "rows": rows, "cont": cont, "after": {k2: after.get(k2) for k2 in ("state", "pid", "restarts", "backend", "notice")}},
          open(os.path.join(out, "hung-extended.json"), "w", encoding="utf-8"), indent=1)
print(f"froze pid {pid}; first change {first}; after: {after.get('state')} pid {after.get('pid')} restarts {after.get('restarts')}")
