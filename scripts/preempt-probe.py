#!/usr/bin/env python3
"""Sends one real chat job to a phone through the signed node API and prints how long it took, with the keep-busy state
before and after. Used to measure what filler work costs a real request (gate 9).

Usage: python scripts/preempt-probe.py <phone-ip> <tag> [max_tokens]
"""
import hashlib
import hmac
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

ip, tag = sys.argv[1], sys.argv[2]
max_tokens = int(sys.argv[3]) if len(sys.argv) > 3 else 48


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
        return e.code, {"_raw": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return 0, {"_err": f"{type(e).__name__}: {e}"}


_, before = call("GET", "/v1/sched/busy")
t0 = time.time()
status, d = call("POST", "/v1/sched/chat", {"jobId": f"preempt-{tag}-{int(t0)}", "request": {
    "messages": [{"role": "user", "content": "Name the planets of the solar system in order from the sun."}], "maxTokens": max_tokens, "temperature": 0}})
wall = time.time() - t0
_, after = call("GET", "/v1/sched/busy")
st = d.get("stats", {})
gen = st.get("genMs", 0) / 1000
print(f"{tag}: HTTP {status}, wall {wall:.2f} s, model time {(st.get('promptMs', 0) + st.get('genMs', 0)) / 1000:.2f} s "
      f"(prompt {st.get('promptMs', 0):.0f} ms, {st.get('completionTokens')} tokens, {st.get('completionTokens', 0) / gen if gen else 0:.1f} tok/s), "
      f"so {wall - (st.get('promptMs', 0) + st.get('genMs', 0)) / 1000:.2f} s outside the model | busy before: {before.get('state')} cancelled={before.get('jobsCancelled')} "
      f"| after: {after.get('state')} cancelled={after.get('jobsCancelled')}", flush=True)
