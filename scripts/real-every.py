#!/usr/bin/env python3
"""One real 48-token request to each phone every <period> seconds, for <rounds> rounds: the real-work side of a keep-busy run.

Usage: python scripts/real-every.py <rounds> <period-s> <out.log> <tag>=<phone-ip> [<tag>=<phone-ip> ...]
Each request is `scripts/preempt-probe.py` (one signed chat job, timing and the keep-busy state before and after). The
pairing secret is read from the first phone's debug app over adb and handed on in the environment, never printed.
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
rounds, period, out = int(sys.argv[1]), float(sys.argv[2]), sys.argv[3]
phones = [a.split("=", 1) for a in sys.argv[4:]]
here = os.path.dirname(os.path.abspath(__file__))
env = dict(os.environ)
if "SWARM_SECRET" not in env:
    raw = subprocess.run([ADB, "-s", phones[0][1] + ":5555", "shell", "run-as com.swarmos.app cat files/datastore/swarm.preferences_pb"], capture_output=True, timeout=40).stdout.decode("utf-8", "replace")
    m = re.search(r"[0-9a-f]{64}", raw)
    if m:
        env["SWARM_SECRET"] = m.group(0)
t0 = time.time()
with open(out, "a", encoding="utf-8") as f:
    for i in range(rounds):
        time.sleep(max(0.0, t0 + i * period - time.time()))
        for tag, ip in phones:
            try:
                p = subprocess.run([sys.executable, os.path.join(here, "preempt-probe.py"), ip, f"{tag}-{i + 1}"], capture_output=True, text=True, timeout=240, env=env, stdin=subprocess.DEVNULL)
                line = (p.stdout.strip().splitlines() or [p.stderr.strip()[-200:]])[-1]
            except Exception as e:
                line = f"{tag}-{i + 1}: no answer ({type(e).__name__})"
            f.write(f"{time.strftime('%H:%M:%S')} {line}\n")
            f.flush()
