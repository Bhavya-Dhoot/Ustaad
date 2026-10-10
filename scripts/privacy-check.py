#!/usr/bin/env python3
"""Checks the folder-privacy fix of gate 9 on two phones: making a folder private and killing the app at once must not
leave its files visible to a paired phone.

Usage: python scripts/privacy-check.py <owner-ip> <peer-ip> <out-dir>
The owner must hold the demo corpus (a shared folder with an electricity bill photo is expected on Phone C; with other
phones pass the question and a word of the expected title through QUESTION and EXPECT). The peer asks through the signed
peer route POST /v1/pdu/query, the same call a paired phone makes, so no third phone is involved.
Steps: baseline (peer sees the file), set the folder private and force-stop the owner app in the same second, relaunch,
peer asks again (must be withheld), set the folder shared again, peer sees it again.
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
owner, peer, out = sys.argv[1], sys.argv[2], sys.argv[3]
QUESTION = os.environ.get("QUESTION", "When is my electricity bill due?")
EXPECT = os.environ.get("EXPECT", "Electricity")
PKG = "com.swarmos.app"
os.makedirs(out, exist_ok=True)


def call(ip, method, path, body=None, node="pc"):
    secret = os.environ.get("SWARM_SECRET", "swarmos-dev-secret")
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(secret.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    headers = {"X-Swarm-Node": node, "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {"_raw": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return 0, {"_err": f"{type(e).__name__}: {e}"}


def adb(ip, *args):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def as_peer():
    """What a paired phone gets from the owner for the question: titles and the withheld count."""
    s, b = call(owner, "POST", "/v1/pdu/query", {"qid": f"priv-{int(time.time() * 1000)}", "text": QUESTION, "k": 5})
    return s, [h.get("title") for h in b.get("hits", [])], b.get("withheldPrivate"), b


def folders():
    return {f["folder"]: f["isPrivate"] for f in call(owner, "GET", "/v1/pdu/status")[1].get("folders", [])}


def wait_up(seconds=90):
    t0 = time.time()
    while time.time() - t0 < seconds:
        s, b = call(owner, "GET", "/v1/pdu/status")
        if s == 200 and b.get("chunks", 0) > 0:
            r = call(owner, "GET", "/v1/models/state")[1].get("roles", {})
            if r.get("embed", {}).get("state") == "READY":
                return time.time() - t0
        time.sleep(1)
    return None


def show(tag, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {tag}  {detail}", flush=True)


log = {}
f0 = folders()
s, titles, withheld, raw = as_peer()
log["baseline"] = [s, titles, withheld, f0]
target = next((t for t in titles if EXPECT.lower() in (t or "").lower()), None)
show("baseline", s == 200 and target is not None, f"folders {f0}; a peer's query returns {titles}, withheld {withheld}")
# the folder that holds the file: the shared pictures folder of the demo corpus
folder = next((f for f in f0 if "pictures" in f.lower() and not f0[f]), None) or next((f for f in f0 if not f0[f]), None)
print(f"INFO  folder to switch: {folder!r}", flush=True)

t0 = time.time()
s1, b1 = call(owner, "POST", "/v1/pdu/debug/share", {"folder": folder, "shared": False})
kill = adb(owner, "shell", "am", "force-stop", PKG)
gap = time.time() - t0
log["switch_and_kill"] = [s1, b1, kill, gap]
print(f"INFO  set private: HTTP {s1}; app force-stopped {gap:.2f} s after the call started", flush=True)
adb(owner, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
up = wait_up()
f1 = folders()
s, titles2, withheld2, raw2 = as_peer()
log["after_kill"] = [s, titles2, withheld2, f1, up]
leaked = [t for t in titles2 if target and t == target]
show("private-after-kill", s == 200 and f1.get(folder) is True and not leaked and (withheld2 or 0) >= 1,
     f"owner back after {up if up is None else round(up)} s; folder private: {f1.get(folder)}; a peer's query now returns {titles2}, withheld {withheld2}")

s3, b3 = call(owner, "POST", "/v1/pdu/debug/share", {"folder": folder, "shared": True})
time.sleep(1)
f2 = folders()
s, titles3, withheld3, raw3 = as_peer()
log["shared_again"] = [s3, s, titles3, withheld3, f2]
show("shared-again", s == 200 and f2.get(folder) is False and target in titles3, f"folder shared again: {f2.get(folder) is False}; a peer's query returns {titles3}, withheld {withheld3}")
with open(os.path.join(out, "privacy-check.json"), "w", encoding="utf-8") as f:
    json.dump(log, f, ensure_ascii=False, indent=1)
