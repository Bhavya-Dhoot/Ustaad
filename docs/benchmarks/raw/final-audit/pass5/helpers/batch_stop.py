#!/usr/bin/env python3
"""Item 5: a 96-job swarm batch started on A; about 5 s in press "Stop Ustaad" on A's screen; watch B's running count for 20+ s;
press "Start Ustaad"; wait for chat READY on A; read the batch's status. usage: batch_stop.py <out-dir>"""
import hashlib, hmac, json, os, random, re, subprocess, sys, time, urllib.error, urllib.request
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
A, B = "192.168.242.89", "192.168.242.23"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
T0 = time.time()
LOG = open(os.path.join(out, "calls.log"), "w", encoding="utf-8", buffering=1)
def log(m):
    l = f"{time.strftime('%H:%M:%S')} +{time.time() - T0:6.1f}s  {m}"; LOG.write(l + "\n"); print(l, flush=True)
def adb(ip, *a, timeout=60):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *a], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "")
def call(ip, method, path, body=None, timeout=8):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None: h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try: return e.code, json.loads(raw)
        except ValueError: return e.code, {"_raw": raw[:200]}
    except Exception as e: return 0, {"_err": type(e).__name__}
def find(ip, text):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, timeout=40, stdin=subprocess.DEVNULL)
    x = p.stdout.decode("utf-8", "replace")
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        if m.group(1).strip() == text:
            a, b, c, d = map(int, m.groups()[1:])
            return (a + c) // 2, (b + d) // 2
    # content-desc variant
    for m in re.finditer(r'<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        if m.group(1).strip() == text:
            a, b, c, d = map(int, m.groups()[1:])
            return (a + c) // 2, (b + d) // 2
    return None
topics = ["a heat pump", "photosynthesis", "a transistor", "the water cycle", "a binary search", "inflation", "a vaccine", "tides", "a lens", "compound interest", "a hash table", "plate tectonics",
          "a refrigerator", "recursion", "the Doppler effect", "a battery", "DNS", "friction", "a bridge arch", "enzymes", "a compiler", "the moon phases", "a thermostat", "supply and demand"]
reqs = [{"messages": [{"role": "system", "content": "Answer in one short sentence."}, {"role": "user", "content": f"Explain {topics[i % len(topics)]} (variant {i})."}], "maxTokens": 48, "temperature": 0.0, "seed": i} for i in range(96)]

btn = find(A, "Stop Ustaad")
log(f"Stop Ustaad button found at {btn}")
if btn is None:
    log("button not found; not starting the batch"); sys.exit(2)
json.dump({"stopButton": btn}, open(os.path.join(out, "button.json"), "w"))
s0, caps_b0 = call(B, "GET", "/v1/sched/caps")
log(f"B caps before: {s0} running={caps_b0.get('running')} queued={caps_b0.get('queued')}")
ts = time.time()
s, b = call(A, "POST", "/v1/sched/batch", {"requests": reqs, "mode": "swarm"}, timeout=30)
bid = b.get("id")
log(f"POST A /v1/sched/batch (96 requests, swarm) -> {s} {b}")
if not bid: sys.exit(3)
rows = []
def snap(tag):
    sa, da = call(A, "GET", f"/v1/sched/batch/{bid}", timeout=4)
    sb, cb = call(B, "GET", "/v1/sched/caps", timeout=4)
    per = {}
    for i in (da.get("items") or []) if isinstance(da, dict) else []:
        per[i.get("nodeId")] = per.get(i.get("nodeId"), 0) + 1
    rows.append([round(time.time() - ts, 1), tag, sa, da.get("state"), da.get("done"), da.get("failed"), sb, cb.get("running"), cb.get("queued")])
    log(f"{tag}: A batch {sa} state={da.get('state')} done={da.get('done')} failed={da.get('failed')} perNode={per} | B caps {sb} running={cb.get('running')} queued={cb.get('queued')}")
    return da
while time.time() - ts < 5.0:
    snap("pre"); time.sleep(1.0)
last = snap("just before tap")
tap_t = time.time()
o = adb(A, "shell", f"input tap {btn[0]} {btn[1]}")
log(f"TAP on Stop Ustaad at ({btn[0]},{btn[1]}) -> {o.strip()!r}; {tap_t - ts:.1f} s after the batch started; items finished at the last read: {last.get('done')}")
for k in range(0, 14):  # about 28 s after the tap
    time.sleep(2.0)
    sa, da = call(A, "GET", f"/v1/sched/batch/{bid}", timeout=3)
    sb, cb = call(B, "GET", "/v1/sched/caps", timeout=4)
    sc, ph = call(B, "GET", "/v1/sched/phones", timeout=4)
    infl = {p["nodeId"][:4]: p.get("inflight") for p in ph} if isinstance(ph, list) else None
    rows.append([round(time.time() - tap_t, 1), "after tap", sa, da.get("state"), da.get("done"), da.get("failed"), sb, cb.get("running"), cb.get("queued")])
    log(f"{time.time() - tap_t:5.1f} s after the tap: A batch {sa} {da.get('state') if sa == 200 else ''} | B caps running={cb.get('running')} queued={cb.get('queued')} | B sees inflight {infl}")
sbtn = find(A, "Start Ustaad")
log(f"Start Ustaad button found at {sbtn}")
if sbtn:
    t_start = time.time()
    o = adb(A, "shell", f"input tap {sbtn[0]} {sbtn[1]}")
    log(f"TAP on Start Ustaad at {sbtn} -> {o.strip()!r}")
    t_api = t_chat = None
    while time.time() - t_start < 150:
        sa, da = call(A, "GET", "/v1/sched/busy", timeout=3)
        if sa == 200 and t_api is None:
            t_api = time.time() - t_start; log(f"A API answers {t_api:.1f} s after the Start tap")
        if t_api is not None:
            sm, dm = call(A, "GET", "/v1/models/state", timeout=4)
            ch = (dm.get("roles") or {}).get("chat", {}) if isinstance(dm, dict) else {}
            if ch.get("state") == "READY":
                t_chat = time.time() - t_start; log(f"A chat READY ({ch.get('backend')}) {t_chat:.1f} s after the Start tap"); break
        time.sleep(2)
sa, da = call(A, "GET", f"/v1/sched/batch/{bid}", timeout=8)
json.dump(da, open(os.path.join(out, f"batch-{bid}-after-restart.json"), "w"), indent=1)
log(f"batch {bid} status after the restart: HTTP {sa} state={da.get('state')} done={da.get('done')} failed={da.get('failed')} total={da.get('total')}")
json.dump(rows, open(os.path.join(out, "rows.json"), "w"))
