#!/usr/bin/env python3
"""Runs the built-in bench32 batch on one phone and prints one line. Used for before/after comparisons on the same phone.

Usage: python scripts/bench-batch.py <phone-ip> <mode: local|swarm> <tag> <out-dir> [--slots N]
--slots N first sets the phone's scheduler capacity through the debug route POST /v1/sched/debug/capacity (debug builds).
Calls are signed by scripts/swarm-call.sh (development secret unless SWARM_SECRET is set). The raw batch result is saved as
<out-dir>/batch-<tag>.json. Thermal status is read over adb before and after (adb serial = <phone-ip>:5555).
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, mode, tag, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
slots = int(sys.argv[sys.argv.index("--slots") + 1]) if "--slots" in sys.argv else None
os.makedirs(out, exist_ok=True)


def call(method, path, body=None):
    """Signed node API call, the same recipe as scripts/swarm-call.sh: HMAC-SHA256 over method, path, timestamp, body."""
    import hashlib
    import hmac
    import random
    import urllib.error
    import urllib.request
    secret = os.environ.get("SWARM_SECRET", "swarmos-dev-secret")
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(secret.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    headers = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
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


if slots is not None:
    caps = call("POST", "/v1/sched/debug/capacity", {"slots": slots})
    print(f"capacity set to {slots}: caps now capacity={caps.get('capacity')} running={caps.get('running')}", flush=True)
t_before = thermal()
start = call("POST", "/v1/sched/batch", {"builtin": "bench32", "mode": mode})
if "id" not in start:
    sys.exit(f"batch did not start: {start}")
t0 = time.time()
while True:
    time.sleep(3)
    d = call("GET", f"/v1/sched/batch/{start['id']}")
    if d.get("state") == "done" or time.time() - t0 > 900:
        break
with open(os.path.join(out, f"batch-{tag}.json"), "w", encoding="utf-8") as f:
    json.dump(d, f, ensure_ascii=False, indent=1)
per = "; ".join(f"{p['nodeId'][:4]} {p['jobs']} jobs {p['decodeTokPerSec']:.1f} tok/s decode" for p in d.get("perPhone", []))
print(f"{tag}: {d.get('done')}/{d.get('total')} done, {d.get('failed')} failed, wall {d.get('wallMs', 0) / 1000:.1f} s, {d.get('totalTokens')} tokens, "
      f"aggregate {d.get('aggTokPerSec', 0):.2f} tok/s | {per} | thermal {t_before} -> {thermal()}", flush=True)
