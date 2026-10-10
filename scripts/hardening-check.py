#!/usr/bin/env python3
"""Fault-injection checks of the gate 9 hardening on one phone (debug build). Each check prints PASS or FAIL with what was seen.

Usage: python scripts/hardening-check.py <phone-ip> <out-dir> <steps>
steps: g GPU start time, three reloads (OpenCL program cache); m memory preflight (a load of the 18.6 GB file must be refused
quickly; run only on a phone that has that file in the app's models folder); t truncated stream (kill the chat server
mid-answer: the call must fail, not return a short answer as complete); h hung server (freeze the chat server: the app must
notice and restart it); c two GPU deaths inside two minutes fall back to the CPU build (then the GPU is restored).
Signals are sent with `run-as com.swarmos.app` over adb (serial <ip>:5555). Signed calls use the development secret.
"""
import hashlib
import hmac
import json
import os
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, out, steps = sys.argv[1], sys.argv[2], sys.argv[3]
SER, PKG = f"{ip}:5555", "com.swarmos.app"
CHAT, BIG = "Qwen3.5-4B-Q4_K_M.gguf", "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
os.makedirs(out, exist_ok=True)


def adb(*args, timeout=60):
    p = subprocess.run([ADB, "-s", SER, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def call(method, path, body=None, timeout=200):
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


def chat_role():
    return call("GET", "/v1/models/state")[1].get("roles", {}).get("chat", {})


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def wait_state(wanted, seconds, role=chat_role):
    t0 = time.time()
    r = role()
    while time.time() - t0 < seconds:
        r = role()
        if r.get("state") in wanted:
            break
        time.sleep(0.5)
    return r, time.time() - t0


def show(tag, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {tag}  {detail}", flush=True)


def save(name, obj):
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def ensure_ready(seconds=120):
    r = chat_role()
    if r.get("state") != "READY":
        call("POST", "/v1/models/load", {"role": "chat", "file": CHAT})
        r, _ = wait_state(("READY", "FAILED"), seconds)
    return r


def signal(sig, pid):
    return adb("shell", "run-as", PKG, "kill", f"-{sig}", str(pid))


def long_chat(tag, result, max_tokens=400):
    result[tag] = call("POST", "/v1/sched/chat", {"jobId": f"hard-{tag}-{int(time.time())}", "request": {
        "messages": [{"role": "user", "content": "Write a detailed 300-word explanation of how a refrigerator works."}], "maxTokens": max_tokens, "temperature": 0}})


print(f"INFO  phone {ip}  thermal {thermal()}  chat {({k: chat_role().get(k) for k in ('state', 'backend', 'loadMs', 'restarts')})}", flush=True)

if "g" in steps:
    times = []
    for i in range(3):
        call("POST", "/v1/models/stop", {"role": "chat"})
        time.sleep(3)
        call("POST", "/v1/models/load", {"role": "chat", "file": CHAT})
        r, dt = wait_state(("READY", "FAILED"), 180)
        times.append((r.get("state"), r.get("backend"), r.get("loadMs")))
        time.sleep(4)
    cache = adb("shell", "run-as", PKG, "sh", "-c", "ls files/cl-cache 2>/dev/null | wc -l; du -sk files/cl-cache 2>/dev/null")
    save("g-gpu-start.json", {"reloads": times, "cache": cache})
    show("g-gpu-start", all(t[0] == "READY" and t[1] == "GPU" for t in times), f"three reloads (state, backend, loadMs): {times}; program cache files and kB: {cache!r}; thermal {thermal()}")

if "m" in steps:
    ensure_ready()
    mem = adb("shell", "grep", "MemAvailable", "/proc/meminfo")
    t0 = time.time()
    s, b = call("POST", "/v1/models/load", {"role": "chat", "file": BIG})
    r, dt = wait_state(("READY", "FAILED", "STOPPED"), 60)
    took = time.time() - t0
    reason = (r.get("lastError") or r.get("reason") or json.dumps(b))[:260]
    save("m-memory-preflight.json", {"status": s, "body": b, "role": r, "seconds": took, "mem": mem})
    show("m-memory-preflight", r.get("state") != "READY" and took < 30 and "memory" in reason.lower(), f"{mem}; load of the 18.6 GB file: HTTP {s}, chat {r.get('state')} after {took:.1f} s: {reason}")
    back = ensure_ready()
    print(f"INFO  small model back: {back.get('state')} on {back.get('backend')}", flush=True)

if "t" in steps:
    r0 = ensure_ready()
    res = {}
    th = threading.Thread(target=long_chat, args=("trunc", res))
    th.start()
    time.sleep(6)
    pid = chat_role().get("pid")
    k = signal(9, pid)
    th.join(120)
    s, b = res.get("trunc", (None, {}))
    text = (b.get("text") or "") if isinstance(b, dict) else ""
    save("t-truncated.json", {"status": s, "body": b, "pid": pid, "kill": k})
    r1, dt = wait_state(("READY",), 90)
    show("t-truncated-stream", s != 200, f"killed pid {pid} six seconds into a long answer: HTTP {s}, {json.dumps(b, ensure_ascii=False)[:230]}"
         f"{' (a 200 with ' + str(len(text)) + ' characters would mean a cut answer was passed as complete)' if s == 200 else ''}")
    show("t-restart", r1.get("state") == "READY", f"chat {r1.get('state')} on {r1.get('backend')} {dt:.0f} s later, restarts {r1.get('restarts')}, notice {r1.get('notice')!r}")
    time.sleep(125)  # let the restart window pass so later steps start clean

if "h" in steps:
    r0 = ensure_ready()
    pid = r0.get("pid")
    t0 = time.time()
    k = signal("STOP", pid)
    seen = None
    while time.time() - t0 < 120:
        r = chat_role()
        if r.get("pid") != pid or r.get("state") != "READY":
            seen = (round(time.time() - t0, 1), r.get("state"), r.get("lastError") or r.get("notice"))
            break
        time.sleep(1)
    r1, dt = wait_state(("READY",), 120)
    signal("CONT", pid)  # harmless if the process is gone
    save("h-hung.json", {"pid": pid, "kill": k, "first_change": seen, "after": r1})
    show("h-hung-server", seen is not None and r1.get("state") == "READY" and r1.get("pid") != pid,
         f"froze pid {pid}: first change after {seen}; then {r1.get('state')} on {r1.get('backend')} with pid {r1.get('pid')}, restarts {r1.get('restarts')}")
    time.sleep(125)

if "c" in steps:
    r0 = ensure_ready()
    kills = []
    for i in range(2):
        pid = chat_role().get("pid")
        kills.append((pid, signal(9, pid)))
        r, dt = wait_state(("READY", "FAILED"), 120, role=lambda p=pid: (lambda x: x if x.get("pid") != p else {"state": "WAIT"})(chat_role()))
        time.sleep(3)
    r1, dt = wait_state(("READY", "FAILED"), 120)
    save("c-cpu-fallback.json", {"kills": kills, "after": r1})
    show("c-cpu-fallback", r1.get("state") == "READY" and r1.get("backend") == "CPU", f"two kills {kills}: chat {r1.get('state')} on {r1.get('backend')}, restarts {r1.get('restarts')}, notice {r1.get('notice')!r}")
    # restore the GPU: the owner's switch clears the remembered failure
    call("POST", "/v1/models/gpu", {"enabled": False})
    call("POST", "/v1/models/gpu", {"enabled": True})
    call("POST", "/v1/models/stop", {"role": "chat"})
    time.sleep(3)
    call("POST", "/v1/models/load", {"role": "chat", "file": CHAT})
    r2, dt = wait_state(("READY", "FAILED"), 180)
    show("c-gpu-restored", r2.get("state") == "READY" and r2.get("backend") == "GPU", f"after switching the GPU off and on: chat {r2.get('state')} on {r2.get('backend')}, loadMs {r2.get('loadMs')}")

print(f"INFO  end  thermal {thermal()}  chat {({k: chat_role().get(k) for k in ('state', 'backend', 'restarts')})}", flush=True)
