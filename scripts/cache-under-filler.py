#!/usr/bin/env python3
"""Does filler work push a real conversation out of the model server's prompt cache? (final audit, 2026-10-11)

Usage: python scripts/cache-under-filler.py <phone-ip> <out.jsonl> [wait-seconds ...]   (default waits: 0 30 90)
For each wait: asks one long question that the server has not seen (a fresh marker in its first line), waits that long with
Keep busy left as it is (so filler runs in between), asks the identical question again, and records the prompt time of both
asks and how many filler jobs ran in between. A second ask served from the cache reads its prompt in a fraction of the time.
The pairing secret is read from the debug app over adb and kept in memory (SWARM_SECRET overrides). Changes nothing on the phone.
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
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, out = sys.argv[1], sys.argv[2]
waits = [float(x) for x in sys.argv[3:]] or [0.0, 30.0, 90.0]
SECRET = os.environ.get("SWARM_SECRET") or (re.search(r"[0-9a-f]{64}", subprocess.run(
    [ADB, "-s", ip + ":5555", "shell", "run-as com.swarmos.app cat files/datastore/swarm.preferences_pb"], capture_output=True, timeout=40
).stdout.decode("utf-8", "replace")) or [None])[0] or "swarmos-dev-secret"
BODY = (
    "A small town library keeps a ledger of every book it lends. Each line of the ledger has the date, the name of the reader, "
    "the title, the shelf it came from and the day it is due back. On the first Monday of each month the librarian counts the "
    "lines of the month before, sorts them by shelf, and writes down which shelf was used most and which was not used at all. "
    "Books that are more than two weeks late are listed on a separate page with the reader's street, so that a volunteer can "
    "walk past and ask for them. In winter the list is longer, because people read more and walk less. The ledger of last year "
    "had four thousand one hundred and twelve lines, of which three hundred and six were late returns, and the shelf used most "
    "was travel. The librarian wants to know how many lines a month that is on average, and what share of them were late. "
    "Answer with the two numbers only."
)


def call(method, path, body=None):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {"_raw": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return 0, {"_err": type(e).__name__}


def ask(prompt, tag):
    status, d = call("POST", "/v1/sched/chat", {"jobId": f"cache-{tag}-{int(time.time() * 1000)}", "request": {
        "messages": [{"role": "user", "content": prompt}], "maxTokens": 8, "temperature": 0}})
    st = d.get("stats", {}) if isinstance(d, dict) else {}
    return {"http": status, "promptMs": st.get("promptMs"), "promptTokens": st.get("promptTokens"), "genMs": st.get("genMs"), "tokens": st.get("completionTokens")}


with open(out, "a", encoding="utf-8") as f:
    for w in waits:
        marker = f"Ledger note {random.randint(100000, 999999)}."
        prompt = marker + " " + BODY
        b0 = call("GET", "/v1/sched/busy")[1]
        first = ask(prompt, "a")
        b1 = call("GET", "/v1/sched/busy")[1]
        time.sleep(w)
        b2 = call("GET", "/v1/sched/busy")[1]
        second = ask(prompt, "b")
        row = {"clock": time.strftime("%H:%M:%S"), "phone": ip, "waitS": w, "busyState": b0.get("state"), "hammer": b0.get("hammer"),
               "fillerJobsBetween": (b2.get("jobsDone") or 0) - (b1.get("jobsDone") or 0), "first": first, "second": second}
        f.write(json.dumps(row) + "\n")
        f.flush()
        print(f"{row['clock']} wait {w:>4.0f} s, filler jobs in between {row['fillerJobsBetween']:>3}: prompt {first['promptMs']} ms ({first['promptTokens']} tokens) then {second['promptMs']} ms", flush=True)
        time.sleep(8)
