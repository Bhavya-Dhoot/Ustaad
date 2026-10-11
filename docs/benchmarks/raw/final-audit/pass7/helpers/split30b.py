#!/usr/bin/env python3
"""Pass 7 step 3: the 30B split, C drives, A lends. Logs every call and phase change with its time. Phone B is never addressed.
usage: split30b.py <out-dir> [ready-limit-minutes]"""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
C, A = "192.168.242.76", "192.168.242.89"
NAME = {C: "C", A: "A"}
BIG = "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
out = sys.argv[1]
ready_limit = float(sys.argv[2]) * 60 if len(sys.argv) > 2 else 40 * 60
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
T0 = time.time()
LOG = open(os.path.join(out, "events.log"), "w", encoding="utf-8", buffering=1)
SAMP = open(os.path.join(out, "samples.jsonl"), "w", encoding="utf-8", buffering=1)
EV = {}


def clock():
    return time.strftime("%H:%M:%S")


def log(msg):
    line = f"{clock()} +{time.time() - T0:7.1f}s  {msg}"
    LOG.write(line + "\n")
    print(line, flush=True)


def call(ip, method, path, body=None, timeout=6):
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
            return e.code, {"_raw": raw[:400]}
    except Exception as e:
        return 0, {"_err": type(e).__name__}


def adb(ip, *a):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *a], capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "")


def roles(ip):
    s, d = call(ip, "GET", "/v1/models/state")
    r = d.get("roles", {}) if isinstance(d, dict) else {}
    out_ = {}
    for k in ("chat", "embed", "rpc"):
        x = r.get(k, {})
        out_[k] = {"state": x.get("state"), "backend": x.get("backend"), "pid": x.get("pid"), "reason": x.get("reason"),
                   "lastError": (x.get("lastError") or "")[:120] or None, "notice": x.get("notice"), "restarts": x.get("restarts")}
    out_["http"] = s
    return out_


def sample(tag=""):
    row = {"t": round(time.time() - T0, 1), "clock": clock(), "tag": tag}
    for ip in (C, A):
        n = NAME[ip]
        sh, h = call(ip, "GET", "/v1/health")
        sb, b = call(ip, "GET", "/v1/sched/busy")
        r = roles(ip)
        row[n] = {"ramAvailMb": h.get("ramAvailMb"), "thermal": h.get("thermalStatus"), "battery": h.get("batteryPct"), "busy": b.get("state"), "jobsDone": b.get("jobsDone"),
                  "jobsFailed": b.get("jobsFailed"), "chat": r["chat"]["state"], "chatBackend": r["chat"]["backend"], "embed": r["embed"]["state"], "rpc": r["rpc"]["state"],
                  "http": [sh, sb, r["http"]]}
    SAMP.write(json.dumps(row) + "\n")
    return row


def mc():
    s, d = call(C, "GET", "/v1/memcloud/state", timeout=8)
    return s, d


def save(name, obj):
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


# 0. before
for ip in (C, A):
    save(f"before-models-{NAME[ip]}.json", call(ip, "GET", "/v1/models/state")[1])
    save(f"before-busy-{NAME[ip]}.json", call(ip, "GET", "/v1/sched/busy")[1])
save("before-memcloud-C.json", mc()[1])
sample("before")
log_start_epoch = time.time()
EV["logStartEpoch"] = log_start_epoch

# 1. start
body = {"file": BIG, "peers": ["Phone A"]}
t_start = time.time()
s, b = call(C, "POST", "/v1/memcloud/start", body, timeout=30)
EV["startClock"] = clock()
EV["startHttp"] = s
save("start-response.json", {"status": s, "body": b})
log(f"POST {C} /v1/memcloud/start {json.dumps(body)} -> HTTP {s}")
log("start response body: " + json.dumps(b)[:1500])

refused = s not in (200, 202)
if refused:
    EV["result"] = "refused"
    EV["refusal"] = {"status": s, "body": b}
    time.sleep(30)
    s2 = sample("30s after the refusal")
    log("30 s after the refusal: " + json.dumps({k: s2[k] for k in ("C", "A")}))
    save("after30s-memcloud-C.json", mc()[1])
    for ip in (C, A):
        save(f"after30s-models-{NAME[ip]}.json", call(ip, "GET", "/v1/models/state")[1])
    save("summary.json", EV)
    sys.exit(0)

# 2. poll the load
last_key = None
last_sample = 0
last_full = 0
phase = None
t_ready = None
final = None
nchg = 0
while time.time() - t_start < ready_limit:
    s, d = mc()
    steps = d.get("steps") or []
    key = (d.get("phase"), len(steps), steps[-1] if steps else None, (d.get("log") or "")[-100:], d.get("failReason"), tuple(d.get("notices") or []))
    if key != last_key:
        nchg += 1
        log(f"PHASE {d.get('phase')} steps={len(steps)} last_step={steps[-1] if steps else None!r} loadMs={d.get('loadMs')} failReason={d.get('failReason')!r} notices={d.get('notices')} log_tail={(d.get('log') or '')[-140:]!r}")
        save(f"memcloud-state-{nchg:03d}-{d.get('phase')}.json", d)
        last_key = key
    phase = d.get("phase")
    if time.time() - last_sample >= 30:
        r = sample("load")
        log(f"SAMPLE C ram={r['C']['ramAvailMb']} th={r['C']['thermal']} busy={r['C']['busy']} chat={r['C']['chat']} | A ram={r['A']['ramAvailMb']} th={r['A']['thermal']} busy={r['A']['busy']} chat={r['A']['chat']} rpc={r['A']['rpc']}")
        last_sample = time.time()
    if phase in ("READY", "FAILED", "DEGRADED", "IDLE") and (phase != "IDLE" or time.time() - t_start > 15):
        final = d
        break
    time.sleep(10)
EV["phaseAtEnd"] = phase
EV["loadSeconds"] = round(time.time() - t_start, 1)
if final is None:
    EV["result"] = "timeout"
    log(f"no READY within {ready_limit / 60:.0f} min; last phase {phase}")
else:
    EV["result"] = phase
    EV["loadMs"] = final.get("loadMs")
    save("memcloud-state-final-load.json", final)
    log(f"load ended: phase {phase} after {time.time() - t_start:.0f} s; loadMs {final.get('loadMs')}")

# 3. questions
asks = []
if phase == "READY":
    EV["readyClock"] = clock()
    sample("ready")
    for i, prompt in enumerate(["Explain how a heat pump works, in about 60 words.", "Name three uses of a phone with no internet, one line each."], 1):
        s, b = call(C, "POST", "/v1/memcloud/ask", {"prompt": prompt, "maxTokens": 80}, timeout=60)
        log(f"ask {i}: POST /v1/memcloud/ask -> {s}")
        g, t0 = {}, time.time()
        while time.time() - t0 < 420:
            g = mc()[1].get("generation") or {}
            if g.get("running") is False and (g.get("tokens") or 0) > 0:
                break
            time.sleep(2)
        r = sample(f"ask{i}")
        asks.append({"ask": i, "status": s, "tokens": g.get("tokens"), "ttftMs": g.get("ttftMs"), "decodeTokPerSec": g.get("decodeTokPerSec"), "seconds": round(time.time() - t0, 1),
                     "busyC": r["C"]["busy"], "busyA": r["A"]["busy"], "text": (g.get("text") or "")[:160]})
        log(f"ask {i}: {json.dumps(asks[-1])}")
        save(f"generation-{i}.json", g)
    EV["asks"] = asks

# 4. record the state at the end of the load / before the stop (also for failures)
pre_stop = {n: roles(ip) for ip, n in NAME.items()}
save("roles-before-stop.json", pre_stop)
save("memcloud-before-stop-C.json", mc()[1])
save("rpc-state-before-stop-A.json", call(A, "GET", "/v1/memcloud/rpc/state")[1])

# 5. stop and time the restore on both phones
t_stop = time.time()
EV["stopClock"] = clock()
s, b = call(C, "POST", "/v1/memcloud/stop", {}, timeout=30)
log(f"POST {C} /v1/memcloud/stop -> {s} {json.dumps(b)[:200]}")
seen = {"A": {}, "C": {}}
changes = {}
failed_roles = []
end_ok_since = None
while time.time() - t_stop < 15 * 60:
    el = time.time() - t_stop
    cur = {}
    for ip, n in NAME.items():
        r = roles(ip)
        cur[n] = r
        for role in ("chat", "embed", "rpc"):
            st = r[role]["state"]
            k = (n, role, st, r[role]["backend"] if role == "chat" else None)
            if changes.get((n, role)) != k:
                changes[(n, role)] = k
                log(f"RESTORE {n} {role} -> {st}" + (f"/{r[role]['backend']}" if role == "chat" else "") + (f" reason={r[role]['reason']!r}" if r[role]["reason"] else "")
                    + (f" lastError={r[role]['lastError']!r}" if st == "FAILED" else "") + f"  ({el:.0f} s after the stop)")
            if st == "FAILED":
                failed_roles.append({"phone": n, "role": role, "at": round(el, 1), "reason": r[role]["reason"], "lastError": r[role]["lastError"]})
        # first times
        if r["rpc"]["state"] in ("STOPPED", None) and "rpcGone" not in seen[n] and not (n == "C" and False):
            seen[n]["rpcGone"] = round(el, 1)
        if r["chat"]["state"] == "READY" and r["chat"]["backend"] == "GPU" and "chatGpuReady" not in seen[n]:
            seen[n]["chatGpuReady"] = round(el, 1)
        if r["embed"]["state"] == "READY" and "embedReady" not in seen[n]:
            seen[n]["embedReady"] = round(el, 1)
    s2, d2 = mc()
    if d2.get("phase") and changes.get("phaseC") != d2.get("phase"):
        changes["phaseC"] = d2.get("phase")
        log(f"RESTORE C memcloud phase -> {d2.get('phase')}  ({el:.0f} s after the stop) notices={d2.get('notices')} pendingRestore={d2.get('pendingRestore')}")
    allok = all(cur[n]["chat"]["state"] == "READY" and cur[n]["chat"]["backend"] == "GPU" and cur[n]["embed"]["state"] == "READY" and cur[n]["rpc"]["state"] in ("STOPPED", None) for n in cur)
    if allok and d2.get("phase") == "IDLE":
        end_ok_since = end_ok_since or time.time()
        if time.time() - end_ok_since > 5:
            break
    else:
        end_ok_since = None
    if int(el) % 30 == 0:
        sample("restore")
    time.sleep(1)
EV["restore"] = seen
EV["failedRoles"] = failed_roles[:20]
EV["restoreSeconds"] = round(time.time() - t_stop, 1)
EV["restoreClean"] = bool(end_ok_since)
log("restore summary: " + json.dumps(seen) + f"; failed roles seen: {len(failed_roles)}")
save("summary.json", EV)
log("done")
