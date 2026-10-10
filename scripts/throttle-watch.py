#!/usr/bin/env python3
"""Passive watch of one phone while keep busy runs: thermal signals against the speed of the filler work. Read-only.

Usage: python scripts/throttle-watch.py <phone-ip> <minutes> <out.jsonl>
Every 10 s it reads `dumpsys thermalservice` (Thermal Status, the temperatures and the cooling devices the thermal HAL
reports) and three signed GETs: `/v1/sched/busy`, `/v1/sched/caps` (the decode speed of the last real request, `realTps`)
and `/v1/memcloud/state` (`memPhase`), and the kernel's board and virtual thermal zones (`zones`, from
`/sys/class/thermal/thermal_zone*/temp`). It sends no job, starts no activity and changes nothing on the phone.
The pairing secret is read from the debug app's own store over adb and kept in memory only (SWARM_SECRET overrides).
Filler speed between two samples = tokens gained / seconds of generating gained (it includes prompt time, so it is lower
than the decode speed of one request, but it moves with it).
"""
import hashlib
import hmac
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, minutes, out = sys.argv[1], float(sys.argv[2]), sys.argv[3]
SER = ip + ":5555"


def adb(*args):
    try:
        p = subprocess.run([ADB, "-s", SER, *args], capture_output=True, timeout=40, stdin=subprocess.DEVNULL)
        return p.stdout.decode("utf-8", "replace").replace("\r", "")
    except Exception:
        return ""


def secret():
    s = os.environ.get("SWARM_SECRET")
    if s:
        return s
    m = re.search(r"[0-9a-f]{64}", adb("shell", "run-as com.swarmos.app cat files/datastore/swarm.preferences_pb"))
    return m.group(0) if m else "swarmos-dev-secret"


SECRET = secret()


def get(path):
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"GET\n{path}\n{ts}\n".encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(f"http://{ip}:8787{path}", headers={"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        return {"_err": type(e).__name__}


def thermal():
    d = adb("shell", "dumpsys", "thermalservice")
    row = {}
    m = re.search(r"Thermal Status: (\d+)", d)
    row["status"] = int(m.group(1)) if m else None
    hal = d.split("Current temperatures from HAL:", 1)[1] if "Current temperatures from HAL:" in d else d
    temps = {n: float(v) for v, n in re.findall(r"Temperature\{mValue=([-\d.]+), mType=\d+, mName=(\w+)", hal.split("Current cooling devices", 1)[0])}
    row["skin"], row["battery"] = temps.get("skin"), temps.get("battery")
    for group in ("GPU", "CPU", "nsp"):
        vals = [v for n, v in temps.items() if n.startswith(group) and v < 150]
        row[group.lower() + "Max"] = max(vals) if vals else None
    row["cooling"] = {n: int(float(v)) for v, n in re.findall(r"CoolingDevice\{mValue=([-\d.]+), mType=\d+, mName=([\w.-]+)", d.split("Current cooling devices", 1)[1])} if "Current cooling devices" in d else {}
    return row


SKIP = ("cpu", "qmx", "nsph", "gpuss", "mdmss")   # die sensors: the HAL groups above already carry their maxima
TYPES = dict(re.findall(r"thermal_zone(\d+)/type:(\S+)", adb("shell", "grep . /sys/class/thermal/thermal_zone*/type")))


def zones():
    """Board and virtual thermal zones by name, in C (the kernel's own list; the thermal HAL shows only a few of them)."""
    out = {}
    for n, v in re.findall(r"thermal_zone(\d+)/temp:(-?\d+)", adb("shell", "grep . /sys/class/thermal/thermal_zone*/temp 2>/dev/null")):
        name = TYPES.get(n, n)
        if not name.startswith(SKIP) and "bcl" not in name and "ibat" not in name:
            out[name] = int(v) / 1000 if abs(int(v)) > 1000 else int(v)
    return out


end, t0 = time.time() + minutes * 60, time.time()
with open(out, "a", encoding="utf-8") as f:
    while time.time() < end:
        b = get("/v1/sched/busy")
        row = {"t": round(time.time() - t0), "clock": time.strftime("%H:%M:%S"), **thermal(),
               **{k: b.get(k) for k in ("state", "jobsDone", "tokens", "secondsGenerating", "jobsFailed", "_err") if k in b},
               "realTps": get("/v1/sched/caps").get("lastDecodeTokPerSec"), "memPhase": get("/v1/memcloud/state").get("phase"), "zones": zones()}
        f.write(json.dumps(row) + "\n")
        f.flush()
        time.sleep(10)
print("done", out)
