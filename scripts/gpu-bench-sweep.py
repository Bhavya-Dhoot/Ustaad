#!/usr/bin/env python3
"""Standalone llama-bench sweep on a phone's GPU, to test model-server settings before any becomes a default (gate 9).

Usage: python scripts/gpu-bench-sweep.py <phone-ip> <out-dir>
Pushes native/prebuilt/arm64-v8a-opencl/libllama-bench.so to /data/local/tmp/llama/opencl on the phone (adb serial <ip>:5555),
stops the app's own model servers for the duration (signed call), runs each variant in A-B-A order with the thermal status
before and after, and reloads the app's default models at the end. The model file must already be at
/data/local/tmp/llama/models/Qwen3.5-4B-Q4_K_M.gguf on the phone.
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
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ip, out = sys.argv[1], sys.argv[2]
SER = f"{ip}:5555"
DIR = "/data/local/tmp/llama/opencl"
MODEL = "/data/local/tmp/llama/models/Qwen3.5-4B-Q4_K_M.gguf"
os.makedirs(out, exist_ok=True)


def adb(*args, timeout=900):
    p = subprocess.run([ADB, "-s", SER, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "")


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
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, "_raw": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return {"_err": f"{type(e).__name__}: {e}"}


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice", timeout=60).splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def cool(limit=1, wait=300):
    t0 = time.time()
    while thermal() not in [str(x) for x in range(limit + 1)] and time.time() - t0 < wait:
        time.sleep(20)
    return thermal()


def bench(tag, env="", flags=""):
    t_before = cool()
    cmd = f"cd {DIR} && {env} LD_LIBRARY_PATH={DIR}:/vendor/lib64:/system/vendor/lib64 ./libllama-bench.so -m {MODEL} -ngl 99 -t 4 -o json {flags} 2>{DIR}/err-{tag}.txt"
    t0 = time.time()
    raw = adb("shell", cmd)
    dt = time.time() - t0
    with open(os.path.join(out, f"bench-{tag}.txt"), "w", encoding="utf-8") as f:
        f.write(cmd + "\n" + raw)
    rows = []
    try:
        start = raw.index("[")
        for r in json.loads(raw[start:raw.rindex("]") + 1]):
            kind = "prefill" if r.get("n_prompt", 0) > 0 and r.get("n_gen", 0) == 0 else "decode"
            n = r.get("n_prompt") or r.get("n_gen")
            rows.append(f"{kind} {n}: {r.get('avg_ts', 0):.1f} +-{r.get('stddev_ts', 0):.1f} tok/s")
    except Exception as e:
        err = adb("shell", f"tail -3 {DIR}/err-{tag}.txt", timeout=30).strip().replace("\n", " | ")
        rows.append(f"PARSE FAILED ({type(e).__name__}); stdout {raw[:120]!r}; stderr tail: {err[:300]}")
    print(f"{tag:<22} {'; '.join(rows)} | {dt:.0f} s | thermal {t_before} -> {thermal()}", flush=True)


print("push:", adb("shell", f"mkdir -p {DIR}").strip(), adb("push", os.path.join(REPO, "native", "prebuilt", "arm64-v8a-opencl", "libllama-bench.so"), f"{DIR}/libllama-bench.so").strip()[-80:])
adb("shell", f"chmod 755 {DIR}/libllama-bench.so")
print("stop app models:", str(call("POST", "/v1/models/stop", {"role": "all"}))[:120])
time.sleep(4)
P = "-p 512 -n 64 -r 3"
try:
    bench("base-1", flags=P)
    bench("ub1024-1", flags="-p 512,900 -n 0 -r 3 -ub 1024")
    bench("ub512-p900", flags="-p 900 -n 0 -r 3 -ub 512")
    bench("dp4a-1", env="GGML_OPENCL_Q4K_DENSE_DP4A=1", flags=P)
    bench("base-2", flags=P)
    bench("splitk-1", env="GGML_OPENCL_Q4K_GEMV_SPLITK=1", flags=P)
    bench("fa-on", flags=P + " -fa 1")
    bench("fa-off", flags=P + " -fa 0")
    bench("dp4a-2", env="GGML_OPENCL_Q4K_DENSE_DP4A=1", flags=P)
    bench("base-3", flags=P)
finally:
    print("reload chat:", str(call("POST", "/v1/models/load", {"role": "chat", "file": "Qwen3.5-4B-Q4_K_M.gguf"}))[:120])
    print("reload embed:", str(call("POST", "/v1/models/load", {"role": "embed", "file": "embeddinggemma-300M-Q8_0.gguf"}))[:120])
