#!/usr/bin/env python3
"""Phone A only. Pass 6 items b, c, d. Read-only polling of /v1/models/state and /v1/sched/busy; kills as hardening-check step c does.
usage: gpu_phase.py <b|c|d> <out-dir> [limit-minutes]
  b: two kills of the GPU chat server -> CPU fallback; then watch (limit default 14 min after CPU READY) for the automatic GPU try. No restore unless it fails.
  c: the same fallback; watch limit (default 13 min) for any change (the one try is used); then restore with the step c calls.
  d: with chat on the GPU and READY > 3 min, kill the chat server until FAILED ('Ustaad starts it again at HH:mm'), then watch for the revival."""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
IP, SER, PKG = "192.168.242.89", "192.168.242.89:5555", "com.swarmos.app"
CHAT = "Qwen3.5-4B-Q4_K_M.gguf"
mode, out = sys.argv[1], sys.argv[2]
limit_min = float(sys.argv[3]) if len(sys.argv) > 3 else {"b": 14, "c": 13, "d": 9}[mode]
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
T0 = time.time()
LOG = open(os.path.join(out, "events.log"), "w", encoding="utf-8", buffering=1)
ROWS = open(os.path.join(out, "samples.jsonl"), "w", encoding="utf-8", buffering=1)
EV = {"mode": mode}


def clock():
    return time.strftime("%H:%M:%S")


def log(msg):
    line = f"{clock()} +{time.time() - T0:7.1f}s  {msg}"
    LOG.write(line + "\n")
    print(line, flush=True)


def adb(*a, timeout=60):
    p = subprocess.run([ADB, "-s", SER, *a], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip()


def call(method, path, body=None, timeout=8):
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{IP}:8787{path}", data=text.encode() if body is not None else None, headers=h, method=method)
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


LAST = {}


def sample(tag=""):
    """One read-only poll; logs a line when something changed. Returns the chat dict and busy dict."""
    s1, st = call("GET", "/v1/models/state")
    s2, bz = call("GET", "/v1/sched/busy")
    c = (st.get("roles") or {}).get("chat", {}) if isinstance(st, dict) else {}
    row = {"t": round(time.time() - T0, 1), "clock": clock(), "http": [s1, s2], "chat": c.get("state"), "backend": c.get("backend"), "pid": c.get("pid"),
           "restarts": c.get("restarts"), "reason": c.get("reason"), "notice": c.get("notice"), "lastError": (c.get("lastError") or "")[-160:] if c.get("lastError") else None,
           "loadMs": c.get("loadMs"), "busy": bz.get("state"), "jobsDone": bz.get("jobsDone"), "jobsFailed": bz.get("jobsFailed"), "tag": tag}
    ROWS.write(json.dumps(row, ensure_ascii=False) + "\n")
    key = (row["chat"], row["backend"], row["pid"], (row["notice"] or "")[:90], (row["reason"] or "")[:90], row["busy"])
    if key != LAST.get("key"):
        log(f"CHANGE chat={row['chat']}/{row['backend']} pid={row['pid']} restarts={row['restarts']} busy={row['busy']} jobsDone={row['jobsDone']} jobsFailed={row['jobsFailed']}"
            f" reason={row['reason']!r} notice={(row['notice'] or '')[:160]!r}")
        LAST["key"] = key
    return c, bz, row


def kill_chat(tag):
    s, st = call("GET", "/v1/models/state")
    c = (st.get("roles") or {}).get("chat", {})
    pid = c.get("pid")
    o = adb("shell", "run-as", PKG, "kill", "-9", str(pid))
    log(f"KILL -9 chat pid {pid} ({c.get('backend')}, state {c.get('state')}) [{tag}] -> {o!r}")
    return pid


def wait_new(pid, seconds, want=("READY", "FAILED")):
    """Poll every second until the chat pid is not `pid` and the state is in want."""
    t0 = time.time()
    c = {}
    while time.time() - t0 < seconds:
        c, bz, row = sample("kill-wait")
        if c.get("pid") != pid and c.get("state") in want:
            return c
        time.sleep(1)
    return c


def force_fallback():
    c0, _, _ = sample("before")
    if not (c0.get("state") == "READY" and c0.get("backend") == "GPU"):
        log(f"precondition failed: chat is {c0.get('state')}/{c0.get('backend')}, not READY on the GPU")
        EV["error"] = "precondition: chat not READY on GPU"
        return None
    t1 = time.time()
    for i in (1, 2):
        pid = kill_chat(f"forced fallback kill {i} of 2")
        c = wait_new(pid, 120)
        log(f"after kill {i}: chat {c.get('state')}/{c.get('backend')} pid {c.get('pid')} restarts {c.get('restarts')}")
        if i == 1:
            time.sleep(3)
    # wait for the final READY (CPU expected)
    t0 = time.time()
    while time.time() - t0 < 120:
        c, bz, row = sample("await-cpu-ready")
        if c.get("state") == "READY":
            break
        time.sleep(1)
    EV["twoKillsSeconds"] = round(time.time() - t1, 1)
    EV["cpuReadyClock"] = clock()
    EV["cpuReadyBackend"] = c.get("backend")
    EV["cpuReadyNotice"] = c.get("notice")
    EV["cpuReadyLoadMs"] = c.get("loadMs")
    EV["cpuReadyAt"] = time.time()
    log(f"CPU READY: backend {c.get('backend')} notice {c.get('notice')!r} (two kills inside {time.time() - t1:.0f} s)")
    json.dump(call("GET", "/v1/models/state")[1], open(os.path.join(out, "state-at-cpu-ready.json"), "w"), indent=1)
    return c


def restore_calls():
    """hardening-check step c restore calls."""
    for p, b in (("/v1/models/gpu", {"enabled": False}), ("/v1/models/gpu", {"enabled": True}), ("/v1/models/stop", {"role": "chat"})):
        s, r = call("POST", p, b, timeout=30)
        log(f"POST {p} {json.dumps(b)} -> {s}")
    time.sleep(3)
    s, r = call("POST", "/v1/models/load", {"role": "chat", "file": CHAT}, timeout=30)
    log(f"POST /v1/models/load chat -> {s}")
    t0 = time.time()
    c = {}
    while time.time() - t0 < 150:
        c, bz, row = sample("restore")
        if c.get("state") in ("READY", "FAILED"):
            break
        time.sleep(2)
    log(f"restore result: chat {c.get('state')}/{c.get('backend')} loadMs {c.get('loadMs')} notice {c.get('notice')!r}")
    EV["restoreResult"] = {"state": c.get("state"), "backend": c.get("backend"), "loadMs": c.get("loadMs"), "notice": c.get("notice"), "at": clock()}
    return c


def focus():
    return adb("shell", "dumpsys window | grep mCurrentFocus; dumpsys power | grep mWakefulness=").replace("\n", " ")


def watch(limit_s, until=None, fine_from=None, fine_to=None):
    """Poll every 15 s (every 3 s between fine_from and fine_to seconds); `until(c, bz)` ends the watch."""
    t0 = time.time()
    last_focus = 0
    while time.time() - t0 < limit_s:
        el = time.time() - t0
        c, bz, row = sample("watch")
        if time.time() - last_focus > 60:
            f = focus()
            ROWS.write(json.dumps({"clock": clock(), "focus": f}) + "\n")
            last_focus = time.time()
        if until and until(c, bz, el):
            return True, el
        fine = fine_from is not None and fine_from <= el <= fine_to
        time.sleep(3 if fine else 15)
    return False, time.time() - t0


if mode in ("b", "c"):
    c = force_fallback()
    if c is None:
        json.dump(EV, open(os.path.join(out, "summary.json"), "w"), indent=1, default=str)
        sys.exit(2)
    t_cpu = EV["cpuReadyAt"]
    log(f"watching for {limit_min} min after CPU READY; read-only polls only, no model request")
    state = {"gpu_seen": None, "not_ready_since": None, "not_ready_total": 0.0, "first_change": None}

    def until_b(c, bz, el_unused):
        now = time.time()
        if c.get("state") != "READY" or c.get("backend") != "CPU":
            if state["first_change"] is None:
                state["first_change"] = (clock(), now - t_cpu, c.get("state"), c.get("backend"))
                log(f"FIRST CHANGE after the CPU READY: {state['first_change']}")
                if mode == "c":
                    return True
            if state["not_ready_since"] is None and c.get("state") != "READY":
                state["not_ready_since"] = now
        if state["not_ready_since"] is not None and c.get("state") == "READY":
            state["not_ready_total"] = now - state["not_ready_since"]
            state["not_ready_since"] = None
        if c.get("state") == "READY" and c.get("backend") == "GPU" and state["first_change"] is not None:
            EV["gpuReadyClock"] = clock()
            EV["gpuReadyAfterCpuReadySeconds"] = round(now - t_cpu, 1)
            EV["gpuReadyNotice"] = c.get("notice")
            return mode == "b"
        return False

    # loop with fine polling by time since CPU READY
    t_end = t_cpu + limit_min * 60
    last_focus = 0
    ok = False
    while time.time() < t_end:
        since = time.time() - t_cpu
        c, bz, row = sample("watch")
        if time.time() - last_focus > 60:
            ROWS.write(json.dumps({"clock": clock(), "focus": focus()}) + "\n")
            last_focus = time.time()
        if until_b(c, bz, since):
            ok = True
            break
        time.sleep(3 if 9.5 * 60 <= since <= 13.5 * 60 else 15)
    EV["watchSecondsAfterCpuReady"] = round(time.time() - t_cpu, 1)
    EV["firstChange"] = state["first_change"]
    EV["notReadySecondsInSwitch"] = state["not_ready_total"] or None
    if mode == "b":
        if ok:
            # let it settle a little and record busy afterwards (about 3 more minutes of 15 s polls)
            log("GPU READY reached; recording 3 more minutes of Keep busy")
            t1 = time.time()
            while time.time() - t1 < 180:
                sample("after-gpu")
                time.sleep(15)
            EV["result"] = "PASS"
        else:
            log("no automatic GPU try within the limit: recording full state and restoring by hand")
            json.dump(call("GET", "/v1/models/state")[1], open(os.path.join(out, "state-at-timeout.json"), "w"), indent=1)
            json.dump(call("GET", "/v1/sched/busy")[1], open(os.path.join(out, "busy-at-timeout.json"), "w"), indent=1)
            EV["result"] = "FAIL"
            restore_calls()
    else:  # c
        EV["result"] = "PASS" if state["first_change"] is None else "FAIL"
        log(f"c: {'no change in chat state for the whole wait' if state['first_change'] is None else 'chat changed: ' + str(state['first_change'])}")
        json.dump(call("GET", "/v1/models/state")[1], open(os.path.join(out, "state-at-end-of-wait.json"), "w"), indent=1)
        restore_calls()

elif mode == "d":
    c, bz, row = sample("start")
    if not (c.get("state") == "READY" and c.get("backend") == "GPU"):
        log("precondition failed: chat not READY on GPU")
        EV["error"] = "precondition"
        json.dump(EV, open(os.path.join(out, "summary.json"), "w"), indent=1, default=str)
        sys.exit(2)
    # READY for more than 3 minutes: the load time is not known, so require 180 s of continuous READY from now
    log("waiting 180 s of continuous READY on the GPU before the first kill")
    t0 = time.time()
    while time.time() - t0 < 180:
        c, bz, row = sample("pre-wait")
        if c.get("state") != "READY":
            t0 = time.time()
        time.sleep(15)
    reasons = []
    failed = None
    for k in range(1, 11):
        c, bz, row = sample("loop")
        if c.get("state") == "FAILED":
            failed = c
            break
        if c.get("state") != "READY":
            c = wait_new(None, 150)
            if c.get("state") == "FAILED":
                failed = c
                break
        pid = kill_chat(f"kill {k}")
        c = wait_new(pid, 150)
        # let the status settle for a few seconds and record the reason text
        time.sleep(2)
        c2, bz2, row2 = sample("after-kill")
        reasons.append({"kill": k, "pid": pid, "state": c2.get("state"), "backend": c2.get("backend"), "reason": c2.get("reason"), "notice": c2.get("notice"), "restarts": c2.get("restarts"), "clock": clock()})
        log(f"after kill {k}: chat {c2.get('state')}/{c2.get('backend')} restarts {c2.get('restarts')} reason={c2.get('reason')!r} notice={(c2.get('notice') or '')[:140]!r}")
        if c2.get("state") == "FAILED":
            failed = c2
            break
        time.sleep(2)
    EV["kills"] = reasons
    if failed is None:
        EV["result"] = "FAIL"
        log("chat never reached FAILED within 10 kills")
    else:
        t_fail = time.time()
        EV["failedClock"] = clock()
        EV["failedReason"] = failed.get("reason")
        EV["failedNotice"] = failed.get("notice")
        log(f"FAILED reached: reason={failed.get('reason')!r}")
        json.dump(call("GET", "/v1/models/state")[1], open(os.path.join(out, "state-at-failed.json"), "w"), indent=1)
        json.dump(call("GET", "/v1/sched/busy")[1], open(os.path.join(out, "busy-at-failed.json"), "w"), indent=1)
        # revival: watch up to limit_min after FAILED
        got = {"starting": None, "ready": None}

        def until_d(c, bz, el):
            if c.get("state") == "STARTING" and got["starting"] is None:
                got["starting"] = (clock(), time.time() - t_fail)
            if c.get("state") == "READY":
                got["ready"] = (clock(), time.time() - t_fail, c.get("backend"), c.get("notice"))
                return True
            return False

        ok, el = watch(limit_min * 60, until_d)
        EV["revivalStarting"] = got["starting"]
        EV["revivalReady"] = got["ready"]
        EV["result"] = "PASS" if ok else "FAIL"
        if ok:
            log("revived; recording 2 more minutes of Keep busy")
            t1 = time.time()
            while time.time() - t1 < 120:
                sample("after-revival")
                time.sleep(15)
        c, bz, row = sample("end")
        if c.get("backend") != "GPU" or c.get("state") != "READY":
            log("chat is not READY on the GPU at the end: restoring with the step c calls")
            restore_calls()

json.dump(EV, open(os.path.join(out, "summary.json"), "w"), indent=1, default=str)
log("summary: " + json.dumps({k: v for k, v in EV.items() if k != "cpuReadyAt"}, default=str)[:900])
