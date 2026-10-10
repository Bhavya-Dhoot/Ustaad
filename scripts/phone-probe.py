#!/usr/bin/env python3
"""Small signed probes of one phone for before/after comparisons (gate 9). Development secret unless SWARM_SECRET is set.

  phone-probe.py <ip> state                 chat and embed server state: backend, memory, time to ready, restarts
  phone-probe.py <ip> reload <role>         stop then load the default model of <role> (chat|embed), print time to READY
  phone-probe.py <ip> busy [seconds] [out]  sample GET /v1/sched/busy and thermal status every 15 s for <seconds> (default 900)
  phone-probe.py <ip> get <path>            signed GET, print JSON
  phone-probe.py <ip> post <path> <json>    signed POST, print JSON
Thermal status is read over adb (serial <ip>:5555).
"""
import hashlib
import hmac
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, cmd = sys.argv[1], sys.argv[2]


def call(method, path, body=None, timeout=120):
    secret = os.environ.get("SWARM_SECRET", "swarmos-dev-secret")
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(secret.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    headers = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, "_raw": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return {"_err": f"{type(e).__name__}: {e}"}


def thermal():
    p = subprocess.run([ADB, "-s", f"{ip}:5555", "shell", "dumpsys", "thermalservice"], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    for line in p.stdout.replace("\r", "").splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def roles():
    return call("GET", "/v1/models/state").get("roles", {})


def show_state():
    for name, r in roles().items():
        keep = {k: r.get(k) for k in ("state", "backend", "file", "rssMb", "loadMs", "restarts", "notice", "lastError") if r.get(k) not in (None, "", 0) or k == "state"}
        print(name, keep)
    print("thermal", thermal())


if cmd == "state":
    show_state()
elif cmd == "reload":
    role = sys.argv[3]
    before = roles().get(role, {})
    print("before:", {k: before.get(k) for k in ("state", "backend", "file", "rssMb", "loadMs")}, "thermal", thermal())
    print("stop:", str(call("POST", "/v1/models/stop", {"role": role}))[:160])
    time.sleep(3)
    t0 = time.time()
    print("load:", str(call("POST", "/v1/models/load", {"role": role, "file": before.get("file")}))[:160])
    while time.time() - t0 < 180:
        r = roles().get(role, {})
        if r.get("state") in ("READY", "FAILED"):
            break
        time.sleep(0.5)
    print(f"{role}: {r.get('state')} on {r.get('backend')} after {time.time() - t0:.1f} s by the PC clock; the app reports loadMs={r.get('loadMs')}, rssMb={r.get('rssMb')}")
elif cmd == "busy":
    seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 900
    out = sys.argv[4] if len(sys.argv) > 4 else None
    rows, t0 = [], time.time()
    while time.time() - t0 < seconds:
        b = call("GET", "/v1/sched/busy")
        chat = roles().get("chat", {})  # memory and restarts of the chat server, for runs long enough to show growth
        row = {"t": round(time.time() - t0), "thermal": thermal(), "chatRssMb": chat.get("rssMb"), "chatRestarts": chat.get("restarts"), **b}
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False)[:300], flush=True)
        time.sleep(15)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
elif cmd == "get":
    print(json.dumps(call("GET", sys.argv[3]), ensure_ascii=False)[:3000])
elif cmd == "post":
    print(json.dumps(call("POST", sys.argv[3], json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}), ensure_ascii=False)[:3000])
