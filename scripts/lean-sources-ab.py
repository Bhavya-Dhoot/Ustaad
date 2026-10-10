#!/usr/bin/env python3
"""A/B of the "lean sources" switch of the Data Union on one phone (gate 9): same questions, switch off then on, twice.

Usage: python scripts/lean-sources-ab.py <asker-ip> <out-dir>
The switch is set with the debug-only intent extra `leanSources` over adb (serial <ip>:5555). Every ask is preceded by a
different question, so the model's prompt cache holds at most the shared system text in both arms. Each answer is checked
for the fact it must contain. Peers are whatever the asker sees online; the list is printed.
"""
import hashlib
import hmac
import json
import os
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
# (question, substrings of which one must be in the answer)
QUESTIONS = [
    ("When is my electricity bill due and how much is it?", ["2,410", "2410"]),
    ("What was the total on the restaurant bill?", ["1,056", "1056"]),
    ("When is the yoga class?", ["6:30"]),
    ("How much was the cab ride?", ["312"]),
    ("दूध वाले को कितने रुपये देने हैं?", ["1200", "1,200"]),
    ("Where is the warranty bill for my laptop?", ["IMG_2231", "WARRANTY BILL", "Phone B"]),
]


def call(method, path, body=None):
    secret = os.environ.get("SWARM_SECRET", "swarmos-dev-secret")
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(secret.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    headers = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {"_raw": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return 0, {"_err": f"{type(e).__name__}: {e}"}


def adb(*args):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def set_lean(on):
    adb("shell", "am", "start", "-n", "com.swarmos.app/.MainActivity", "--ez", "leanSources", "true" if on else "false")
    time.sleep(2)


peers = call("GET", "/v1/link/peers")[1].get("peers", [])
print("INFO  peers:", [(p.get("name"), p.get("state")) for p in peers], "thermal", thermal(), flush=True)
rows = []
for run in (1, 2):
    for lean in (False, True):
        set_lean(lean)
        for q, want in QUESTIONS:
            s, d = call("POST", "/v1/pdu/ask", {"text": q, "useSaved": False})
            t = d.get("timings", {}) if isinstance(d, dict) else {}
            ans = d.get("answer") or ""
            row = dict(run=run, lean=lean, q=q, status=s, ttft=t.get("ttftMs"), total=t.get("totalMs"), sources=len(d.get("sources", [])),
                       ok=any(w in ans for w in want), incomplete=d.get("incomplete"), answer=ans, thermal=thermal(),
                       titles=[(x.get("phone"), x.get("title")) for x in d.get("sources", [])])
            rows.append(row)
            print(f"{'lean' if lean else 'full'} run {run}  ttft {row['ttft']} ms  total {row['total']} ms  sources shown {row['sources']}  fact {'ok' if row['ok'] else 'MISSING'}  "
                  f"thermal {row['thermal']}  {q[:34]!r}: {ans[:80]!r}", flush=True)
set_lean(False)
with open(os.path.join(out, "lean-sources-ab.json"), "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False, indent=1)
for lean in (False, True):
    sel = [r for r in rows if r["lean"] == lean and r["ttft"] and r["ttft"] > 0]
    print(f"SUMMARY {'lean' if lean else 'full'}: n {len(sel)}, first token median {statistics.median(r['ttft'] for r in sel):.0f} ms "
          f"(min {min(r['ttft'] for r in sel)}, max {max(r['ttft'] for r in sel)}), total median {statistics.median(r['total'] for r in sel):.0f} ms, "
          f"facts right {sum(r['ok'] for r in rows if r['lean'] == lean)} of {sum(1 for r in rows if r['lean'] == lean)}", flush=True)
