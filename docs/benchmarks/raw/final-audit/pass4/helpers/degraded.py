#!/usr/bin/env python3
"""Item 3: a degraded split that is left alone. B drives, A lends, small model. Logs every call with its time.
usage: degraded.py <out-dir>"""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
B, A = "192.168.242.23", "192.168.242.89"
PKG, FILE = "com.swarmos.app", "Qwen3.5-4B-Q4_K_M.gguf"
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
T0 = time.time()
LOG = open(os.path.join(out, "calls.log"), "w", encoding="utf-8", buffering=1)
EV = {}


def now():
    return time.strftime("%H:%M:%S")


def log(msg):
    line = f"{now()} +{time.time() - T0:7.1f}s  {msg}"
    LOG.write(line + "\n")
    print(line, flush=True)


def call(ip, method, path, body=None, timeout=20):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"_raw": raw[:200]}
    except Exception as e:
        return 0, {"_err": type(e).__name__}


def adb(ip, *a):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *a], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def mc(ip):
    s, d = call(ip, "GET", "/v1/memcloud/state")
    log(f"GET {ip} /v1/memcloud/state -> {s} phase={d.get('phase')} released={d.get('released')} lost={d.get('lostPhone')} fail={d.get('failReason')} notices={d.get('notices')}")
    return d


def models(ip):
    s, d = call(ip, "GET", "/v1/models/state")
    r = d.get("roles", {}) if isinstance(d, dict) else {}
    c, e, p = r.get("chat", {}), r.get("embed", {}), r.get("rpc", {})
    log(f"GET {ip} /v1/models/state -> {s} chat={c.get('state')}/{c.get('backend')} embed={e.get('state')} rpc={p.get('state')} restarts={c.get('restarts')}")
    return c, e, p


def busy(ip):
    s, d = call(ip, "GET", "/v1/sched/busy")
    log(f"GET {ip} /v1/sched/busy -> {s} state={d.get('state')} jobsDone={d.get('jobsDone')} failed={d.get('jobsFailed')} cancelled={d.get('jobsCancelled')} tokens={d.get('tokens')}")
    return d


def rel(t):
    return None if t is None else round(t - T0, 1)


# 1. start the split, B driving, A lending
body = {"file": FILE, "peers": ["Phone A"]}
t_start = time.time()
s, b = call(B, "POST", "/v1/memcloud/start", body)
log(f"POST {B} /v1/memcloud/start {json.dumps(body)} -> {s} {json.dumps(b)[:160]}")
EV["startCall"] = {"status": s, "t": rel(t_start)}
d = {}
while time.time() - t_start < 420:
    d = mc(B)
    if d.get("phase") in ("READY", "FAILED", "DEGRADED"):
        break
    time.sleep(2)
t_ready = time.time()
EV["splitPhase"] = d.get("phase")
EV["splitReadySecondsFromStart"] = round(t_ready - t_start, 1)
EV["splitLoadMs"] = d.get("loadMs")
if d.get("phase") != "READY":
    log("split did not reach READY; stopping the test")
    json.dump(EV, open(os.path.join(out, "timeline.json"), "w"), indent=1)
    sys.exit(1)
lend = call(A, "GET", "/v1/memcloud/rpc/state")[1]
log(f"GET {A} /v1/memcloud/rpc/state -> lendingTo={lend.get('lendingTo')}")
models(A)
time.sleep(3)

# 2. force-stop Ustaad on A, leave it
t_fs = time.time()
o = adb(A, "shell", "am", "force-stop", PKG)
log(f"ADB {A} am force-stop {PKG} -> {o!r}  (t0 of the degraded phase)")
EV["forceStopAt"] = now()
t_deg = t_rel = t_chat = t_first_fill = t_fill60 = None
busy_at_release = None
jobs_at_chat = None
mc_at = {}
last_phase = None
deadline = None
n = 0
while True:
    n += 1
    d = mc(B)
    ph = d.get("phase")
    if ph != last_phase:
        mc_at.setdefault(ph, []).append(rel(time.time()))
        json.dump(d, open(os.path.join(out, f"memcloud-state-{ph}-{n}.json"), "w"), indent=1)
        last_phase = ph
    if ph == "DEGRADED" and t_deg is None:
        t_deg = time.time()
        log(f"==== DEGRADED seen {t_deg - t_fs:.1f} s after the force-stop")
    if d.get("released") is True and t_rel is None:
        t_rel = time.time()
        deadline = t_rel + 360
        EV["phaseAtRelease"] = ph
        json.dump(d, open(os.path.join(out, "memcloud-state-at-release.json"), "w"), indent=1)
        log(f"==== released=true seen {t_rel - t_fs:.1f} s after the force-stop"
            + (f", {t_rel - t_deg:.1f} s after DEGRADED" if t_deg else "") + f"; phase {ph}")
    c, e, p = models(B)
    bz = busy(B)
    if t_chat is None and c.get("state") == "READY":
        t_chat = time.time()
        jobs_at_chat = bz.get("jobsDone")
        log(f"==== B chat READY seen {t_chat - t_fs:.1f} s after the force-stop" + (f", {t_chat - t_rel:.1f} s after released" if t_rel else ", before released"))
    if t_chat is not None and t_first_fill is None and (bz.get("jobsDone") or 0) > (jobs_at_chat or 0):
        t_first_fill = time.time()
        first_done = bz.get("jobsDone")
        log(f"==== first filler job counted {t_first_fill - t_fs:.1f} s after the force-stop; phase {ph}; busy {bz.get('state')}")
    if t_first_fill is not None and t_fill60 is None and time.time() - t_first_fill >= 60:
        t_fill60 = time.time()
        EV["jobsDoneAtFirstFiller"] = first_done
        EV["jobsDoneAfter60s"] = bz.get("jobsDone")
        EV["jobsFailedAfter60s"] = bz.get("jobsFailed")
        EV["phaseAfter60s"] = ph
        log(f"==== 60 s after the first filler job: jobsDone {first_done} -> {bz.get('jobsDone')}, failed {bz.get('jobsFailed')}, phase {ph}")
        break
    if t_rel is None and time.time() - t_fs > 900:
        log("released never became true within 900 s of the force-stop; giving up")
        break
    if deadline is not None and time.time() > deadline:
        log("6 minutes after released: giving up")
        break
    time.sleep(5)

EV.update({
    "t0_forceStop_clock": EV["forceStopAt"],
    "degradedSecondsAfterForceStop": None if t_deg is None else round(t_deg - t_fs, 1),
    "releasedSecondsAfterForceStop": None if t_rel is None else round(t_rel - t_fs, 1),
    "releasedSecondsAfterDegraded": None if (t_rel is None or t_deg is None) else round(t_rel - t_deg, 1),
    "chatReadySecondsAfterForceStop": None if t_chat is None else round(t_chat - t_fs, 1),
    "chatReadySecondsAfterReleased": None if (t_chat is None or t_rel is None) else round(t_chat - t_rel, 1),
    "firstFillerSecondsAfterForceStop": None if t_first_fill is None else round(t_first_fill - t_fs, 1),
    "firstFillerSecondsAfterReleased": None if (t_first_fill is None or t_rel is None) else round(t_first_fill - t_rel, 1),
    "phaseTransitions": mc_at,
})

# 3. stop the split on B, start Ustaad on A again, wait for the pair
s, b = call(B, "POST", "/v1/memcloud/stop", {})
log(f"POST {B} /v1/memcloud/stop {{}} -> {s} {json.dumps(b)[:160]}")
t_st = time.time()
t_am = time.time()
o = adb(A, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
log(f"ADB {A} am start -> {o.splitlines()[-1:]}")
t_api = t_both = None
while time.time() - t_am < 180:
    s2, _ = call(A, "GET", "/v1/sched/busy", timeout=3)
    if s2 == 200 and t_api is None:
        t_api = time.time()
        log(f"A API answers {t_api - t_am:.1f} s after am start")
    cA, eA, _ = models(A) if t_api else ({}, {}, {})
    cB, eB, pB = models(B)
    m = mc(B)
    if cA.get("state") == "READY" and eA.get("state") == "READY" and cB.get("state") == "READY" and eB.get("state") == "READY" and m.get("phase") == "IDLE":
        t_both = time.time()
        log(f"both phones back in the expected state {t_both - t_am:.1f} s after am start (B memcloud IDLE)")
        break
    time.sleep(4)
EV["amStartToApiSeconds"] = None if t_api is None else round(t_api - t_am, 1)
EV["amStartToBothReadySeconds"] = None if t_both is None else round(t_both - t_am, 1)
EV["finalMemcloudB"] = mc(B)
EV["finalMemcloudA"] = mc(A)
json.dump(EV, open(os.path.join(out, "timeline.json"), "w"), indent=1)
print(json.dumps(EV, indent=1))
