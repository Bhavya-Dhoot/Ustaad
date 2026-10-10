#!/usr/bin/env python3
"""Two-phone checks of the MemoryCloud hardening (gate 9): a driver and one lender, the small model split in two.

Usage: python scripts/memcloud-check.py <driver-ip> <lender-ip> <lender-name> <out-dir> <steps>
steps: v a start with bad `layers` is refused; n a lender refuses a loan it has no memory for; s split start, ask, a stale stop
from a third party is refused, cache clear is refused while lending, stop, both phones get their models back;
k the driver's app is force-stopped two seconds into a start: the lender must have its models back by itself.
Signed calls use the development secret; force-stop goes over adb (serial <ip>:5555).
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
driver, lender, lender_name, out, steps = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
FILE, PKG = "Qwen3.5-4B-Q4_K_M.gguf", "com.swarmos.app"
os.makedirs(out, exist_ok=True)


def call(ip, method, path, body=None, timeout=120):
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
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return 0, {"_err": f"{type(e).__name__}: {e}"}


def adb(ip, *args):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def roles(ip):
    r = call(ip, "GET", "/v1/models/state")[1].get("roles", {})
    return {k: r.get(k, {}).get("state") for k in ("chat", "embed", "rpc")}


def mc(ip):
    return call(ip, "GET", "/v1/memcloud/state")[1]


def wait_phase(ip, wanted, seconds):
    t0 = time.time()
    d = mc(ip)
    while time.time() - t0 < seconds:
        d = mc(ip)
        if d.get("phase") in wanted:
            break
        time.sleep(1)
    return d, time.time() - t0


def wait_models(ip, seconds):
    t0 = time.time()
    r = roles(ip)
    while time.time() - t0 < seconds:
        r = roles(ip)
        if r.get("chat") == "READY" and r.get("embed") == "READY":
            break
        time.sleep(1)
    return r, time.time() - t0


def show(tag, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {tag}  {detail}", flush=True)


def save(name, obj):
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


print(f"INFO  driver {driver} {roles(driver)}  lender {lender} {roles(lender)}", flush=True)

if "v" in steps:
    s, b = call(driver, "POST", "/v1/memcloud/start", {"file": FILE, "peers": [lender_name], "layers": [0, 32]})
    save("v-bad-layers.json", [s, b])
    show("v-bad-layers", s == 400, f"layers [0, 32]: HTTP {s} {json.dumps(b, ensure_ascii=False)[:200]}; driver phase {mc(driver).get('phase')}")

if "n" in steps:
    s, b = call(lender, "POST", "/v1/memcloud/rpc/start", {"needMb": 999999})
    after = roles(lender)
    save("n-lender-memory.json", [s, b, after])
    show("n-lender-memory", s in (400, 409, 507) and after.get("chat") == "READY", f"loan asking for 999999 MB: HTTP {s} {json.dumps(b, ensure_ascii=False)[:200]}; lender models afterwards {after}")

if "s" in steps:
    s, b = call(driver, "POST", "/v1/memcloud/start", {"file": FILE, "peers": [lender_name]})
    d, dt = wait_phase(driver, ("READY", "FAILED", "DEGRADED"), 420)  # a cold lender receives 1.6 GB; through an access point that takes minutes
    lend = call(lender, "GET", "/v1/memcloud/rpc/state")[1]
    show("s-load", d.get("phase") == "READY", f"start HTTP {s}; phase {d.get('phase')} after {dt:.0f} s (loadMs {d.get('loadMs')}); lender lendingTo {lend.get('lendingTo')}, lender models {roles(lender)}; notices {d.get('notices')}")
    if d.get("phase") == "READY":
        call(driver, "POST", "/v1/memcloud/ask", {"prompt": "Explain how a heat pump works, in about 60 words.", "maxTokens": 80})
        g, t0 = {}, time.time()
        while time.time() - t0 < 90:
            g = mc(driver).get("generation") or {}
            if g.get("running") is False and (g.get("tokens") or 0) > 0:
                break
            time.sleep(2)
        show("s-ask", (g.get("tokens") or 0) > 0, f"{g.get('tokens')} tokens, first token {g.get('ttftMs')} ms, {g.get('decodeTokPerSec')} tok/s")
        s1, b1 = call(lender, "POST", "/v1/memcloud/rpc/stop", {"loan": "not-the-real-token"})
        lend2 = call(lender, "GET", "/v1/memcloud/rpc/state")[1]
        show("s-stale-stop", s1 == 409 and lend2.get("lendingTo") == lend.get("lendingTo"), f"stop with a wrong loan token from the PC: HTTP {s1} {json.dumps(b1, ensure_ascii=False)[:160]}; lender still lendingTo {lend2.get('lendingTo')}")
        s2, b2 = call(lender, "POST", "/v1/memcloud/rpc/cache/clear", {})
        show("s-cache-clear-refused", s2 == 409, f"cache clear while lending: HTTP {s2} {json.dumps(b2, ensure_ascii=False)[:120]}")
        save("s-split.json", {"state": d, "generation": g, "lender": lend, "stale_stop": [s1, b1], "cache_clear": [s2, b2]})
    call(driver, "POST", "/v1/memcloud/stop", {})
    rd, td = wait_models(driver, 90)
    rl, tl = wait_models(lender, 90)
    show("s-restore", rd.get("chat") == "READY" and rl.get("chat") == "READY" and rl.get("rpc") in ("STOPPED", None),
         f"after stop: driver {rd} in {td:.0f} s, lender {rl} in {tl:.0f} s; driver notices {mc(driver).get('notices')}")

if "k" in steps:
    before = roles(lender)
    s, b = call(driver, "POST", "/v1/memcloud/start", {"file": FILE, "peers": [lender_name]})
    time.sleep(2.0)
    lend_mid = call(lender, "GET", "/v1/memcloud/rpc/state")[1]
    mid = roles(lender)
    k = adb(driver, "shell", "am", "force-stop", PKG)
    t0 = time.time()
    r, t = {}, 0
    while time.time() - t0 < 60:
        r = roles(lender)
        if r.get("chat") == "READY" and r.get("embed") == "READY" and r.get("rpc") in ("STOPPED", None):
            break
        time.sleep(1)
    took = time.time() - t0
    lend_after = call(lender, "GET", "/v1/memcloud/rpc/state")[1]
    save("k-driver-killed.json", {"before": before, "mid": mid, "lend_mid": lend_mid, "after": r, "seconds": took, "lend_after": lend_after})
    show("k-driver-killed", r.get("chat") == "READY" and r.get("embed") == "READY" and not lend_after.get("lendingTo"),
         f"lender two seconds into the start: {mid}, lendingTo {lend_mid.get('lendingTo')}; driver app force-stopped; lender {r} after {took:.0f} s, lendingTo {lend_after.get('lendingTo')}")
    print("INFO  relaunch driver:", adb(driver, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")[:60], flush=True)
    rd, td = wait_models(driver, 120)
    show("k-driver-recovered", rd.get("chat") == "READY", f"driver after relaunch: {rd} in {td:.0f} s; phase {mc(driver).get('phase')}; notices {mc(driver).get('notices')}")

print(f"INFO  end  driver {roles(driver)}  lender {roles(lender)}", flush=True)
