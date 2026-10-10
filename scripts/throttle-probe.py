#!/usr/bin/env python3
"""Finds which readable phone signal marks the step between the fast and the slow decode level of the chat model while the
Android thermal status stays at 2 (gate 9b). Read-only on the phone except the keep-busy switch (an `am start` with an extra).

  throttle-probe.py run <ip> [--rest-max 360] [--heat-max 1080] [--recover-max 600] [--lowduty 600]
      rest (keep busy off) -> heat (on, until two slow real requests in a row) -> recover (off, until two fast in a row)
      -> low duty (on 20 s, off 40 s, loop) ; samples every 10 s, one real 48-token request every 60 s.
      Raw lines go to docs/benchmarks/raw/gate-9b/throttle/throttle-<ip>-<time>.jsonl ; ends with keep busy on, app in front.
  throttle-probe.py once <ip>              one sample, printed, and how long it took
  throttle-probe.py analyze <file.jsonl>   per-request table, signals whose ranges do not overlap between fast and slow, shares

A request is fast at >= 12.5 tok/s and slow at <= 11.5 (the levels seen on 2026-10-10 were 13.4-13.7 and 10.6-10.9).
Every adb call carries -s <ip>:5555. Development secret unless SWARM_SECRET is set.
"""
import argparse
import hashlib
import hmac
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "docs" / "benchmarks" / "raw" / "gate-9b" / "throttle"
FAST_MIN, SLOW_MAX = 12.5, 11.5
TICK_S, REQ_EVERY_S = 10, 60

# One adb call per sample. No quotes except around the grep pattern; `read` is a shell builtin so 160 files cost no forks.
SAMPLE_CMD = (
    "echo @@tz; for d in /sys/class/thermal/thermal_zone*; do t=; read t < $d/temp 2>/dev/null; echo ${d##*thermal_zone} $t; done; "
    "echo @@cd; for d in /sys/class/thermal/cooling_device*; do t=; read t < $d/cur_state 2>/dev/null; echo ${d##*cooling_device} $t; done; "
    "echo @@cpu; for p in 0 6; do for f in scaling_max_freq scaling_cur_freq; do t=; read t < /sys/devices/system/cpu/cpufreq/policy$p/$f 2>/dev/null; echo p$p.$f $t; done; done; "
    "echo @@th; dumpsys thermalservice | grep -E 'Thermal Status|mName=skin|mName=battery'; "
    "echo @@hr0; cmd thermalservice headroom 0; echo @@hr60; cmd thermalservice headroom 60; "
    "echo @@bat; dumpsys battery | grep -E 'temperature|level|AC powered|USB powered'; "
    "echo @@pw; dumpsys power | grep mWakefulness=; echo @@end"
)
NAMES_CMD = (
    "echo @@tz; for d in /sys/class/thermal/thermal_zone*; do t=; read t < $d/type 2>/dev/null; echo ${d##*thermal_zone} $t; done; "
    "echo @@cd; for d in /sys/class/thermal/cooling_device*; do t=; read t < $d/type 2>/dev/null; echo ${d##*cooling_device} $t; done; echo @@end"
)


def adb(ser, *args, timeout=60):
    p = subprocess.run([ADB, "-s", ser, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return p.stdout.replace("\r", ""), p.stderr.replace("\r", "")


def call(ip, method, path, body=None, timeout=180):
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
        return e.code, {"_raw": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return 0, {"_err": f"{type(e).__name__}: {e}"}


def level(tok_s):
    if tok_s is None:
        return "none"
    return "fast" if tok_s >= FAST_MIN else "slow" if tok_s <= SLOW_MAX else "mid"


def sections(out):
    sec, cur = {}, None
    for line in out.splitlines():
        if line.startswith("@@"):
            cur = line[2:].strip()
            sec[cur] = []
        elif cur is not None and line.strip():
            sec[cur].append(line.strip())
    return sec


def num(s):
    try:
        return int(s)
    except ValueError:
        return None


def pairs(lines):
    d = {}
    for ln in lines:
        k, _, v = ln.partition(" ")
        d[k] = num(v.strip()) if v.strip() else None
    return d


def parse_sample(out):
    sec = sections(out)
    s = {"tz": pairs(sec.get("tz", [])), "cd": pairs(sec.get("cd", [])), "cpu": pairs(sec.get("cpu", []))}
    skin, batt = [], []
    for ln in sec.get("th", []):
        if ln.startswith("Thermal Status"):
            s["status"] = num(ln.split()[-1])
        m = re.search(r"mValue=([-\d.]+), mType=\d+, mName=(skin|battery), mStatus=(\d+)", ln)
        if m:
            (skin if m.group(2) == "skin" else batt).append((float(m.group(1)), int(m.group(3))))
    s["skin_cached"], s["skin_hal"] = (skin[0][0] if skin else None), (skin[1][0] if len(skin) > 1 else None)
    s["skin_status"] = skin[1][1] if len(skin) > 1 else None
    for key in ("hr0", "hr60"):
        m = re.search(r"([-\d.]+|NaN)\s*$", " ".join(sec.get(key, [])))
        try:
            s["headroom" + key[2:]] = float(m.group(1)) if m else None
        except ValueError:
            s["headroom" + key[2:]] = None
    for ln in sec.get("bat", []):
        if ln.startswith("temperature:"):
            s["batt_temp"] = num(ln.split()[-1]) / 10
        elif ln.startswith("level:"):
            s["batt_level"] = num(ln.split()[-1])
        elif ln.startswith("AC powered:"):
            s["ac"] = ln.split()[-1]
    s["wake"] = (sec.get("pw") or ["?"])[0].replace("mWakefulness=", "")
    return s


class Run:
    def __init__(self, ip, path):
        self.ip, self.ser, self.path = ip, f"{ip}:5555", path
        self.f = open(path, "a", encoding="utf-8")
        self.lock = threading.Lock()
        self.t0 = time.time()
        self.reqs = []  # (phase, t_start, tok_s)
        self.inflight = None
        self.phase = "init"
        self.samples = []

    def log(self, **rec):
        rec["t"] = round(time.time(), 2)
        rec["et"] = round(rec["t"] - self.t0, 1)
        with self.lock:
            self.f.write(json.dumps(rec) + "\n")
            self.f.flush()

    def adb(self, *args, timeout=60):
        try:
            return adb(self.ser, *args, timeout=timeout)
        except Exception as e:
            self.log(kind="event", phase=self.phase, what="adb_exception", detail=f"{type(e).__name__}: {e}"[:200])
            return "", str(e)

    def sample(self):
        out, err = self.adb("shell", SAMPLE_CMD)
        if "@@end" not in out:
            self.log(kind="event", phase=self.phase, what="adb_sample_failed", detail=(err or out)[:200])
            conn, _ = self.adb("connect", self.ser, timeout=20)
            self.log(kind="event", phase=self.phase, what="adb_reconnect", detail=conn.strip()[:100])
            return None
        s = parse_sample(out)
        _, busy = call(self.ip, "GET", "/v1/sched/busy", timeout=20)
        _, models = call(self.ip, "GET", "/v1/models/state", timeout=20)
        chat = (models.get("roles") or {}).get("chat", {})
        s["busy"] = {k: busy.get(k) for k in ("state", "jobsDone", "jobsCancelled", "jobsFailed", "tokens", "secondsGenerating", "share60s")}
        if "_err" in busy or "_raw" in busy:
            s["busy"]["err"] = busy.get("_err") or busy.get("_raw")
        s["chat"] = {k: chat.get(k) for k in ("state", "backend", "rssMb", "restarts")}
        s["kind"], s["phase"] = "sample", self.phase
        self.log(**s)
        self.samples.append(s)
        if s["wake"] != "Awake":
            self.adb("shell", "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.swarmos.app/.MainActivity")
            self.log(kind="event", phase=self.phase, what="woke_screen", detail=s["wake"])
        return s

    def busy(self, on):
        out, _ = self.adb("shell", f"am start -n com.swarmos.app/.MainActivity --ez keepBusy {'true' if on else 'false'}")
        self.log(kind="event", phase=self.phase, what="keep_busy_on" if on else "keep_busy_off", detail=out.strip().splitlines()[-1][:100] if out.strip() else "")

    def request(self, tag):
        def go():
            t0 = time.time()
            status, d = call(self.ip, "POST", "/v1/sched/chat", {"jobId": f"throttle-{tag}-{int(t0)}", "request": {
                "messages": [{"role": "user", "content": "Name the planets of the solar system in order from the sun."}], "maxTokens": 48, "temperature": 0}})
            st = d.get("stats", {})
            gen = st.get("genMs", 0) / 1000
            tok_s = round(st.get("completionTokens", 0) / gen, 2) if gen else None
            self.log(kind="req", phase=tag, t_start=round(t0, 2), http=status, wall_s=round(time.time() - t0, 2), tok_s=tok_s, level=level(tok_s),
                     completion_tokens=st.get("completionTokens"), prompt_ms=st.get("promptMs"), gen_ms=st.get("genMs"), err=d.get("_err") or d.get("_raw"))
            self.reqs.append((tag, t0, tok_s))
        self.inflight = threading.Thread(target=go, daemon=True)
        self.inflight.start()

    def phase_run(self, name, max_s, stop, first_req_at=15, req_every=REQ_EVERY_S, req_offset=None, on_tick=None):
        """Sample every TICK_S for up to max_s; start a real request first_req_at s in and then every req_every s. Returns why it ended."""
        self.phase = name
        self.log(kind="event", phase=name, what="phase_start", detail=f"max {max_s} s")
        start = time.time()
        next_tick, next_req = start, start + first_req_at
        while True:
            now = time.time()
            if on_tick:
                on_tick(now - start)
            if now >= next_req and not (self.inflight and self.inflight.is_alive()):
                self.request(name)
                next_req = now + req_every
            self.sample()
            if stop and stop(now - start):
                reason = "condition"
                break
            if time.time() - start >= max_s:
                reason = "timeout"
                break
            next_tick += TICK_S
            time.sleep(max(0, next_tick - time.time()))
        self.log(kind="event", phase=name, what="phase_end", detail=f"{reason} after {time.time() - start:.0f} s")
        return reason

    def phase_reqs(self, name):
        return [r for r in self.reqs if r[0] == name]

    def last_two(self, name, want):
        r = self.phase_reqs(name)
        return len(r) >= 2 and all(level(x[2]) == want for x in r[-2:])

    def final_request(self, name):
        while self.inflight and self.inflight.is_alive():
            time.sleep(1)
        self.request(name)
        self.inflight.join(timeout=200)


def run(a):
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    path = RAW_DIR / f"throttle-{a.ip}-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"
    r = Run(a.ip, path)
    names, _ = r.adb("shell", NAMES_CMD)
    nm = sections(names)
    r.log(kind="meta", args=vars(a), tz_names=pairs_str(nm.get("tz", [])), cd_names=pairs_str(nm.get("cd", [])), fast_min=FAST_MIN, slow_max=SLOW_MAX)
    print("writing", path, flush=True)

    # 1 rest
    r.phase = "rest"
    r.busy(False)

    def rest_done(el):
        sk = [s["skin_hal"] for s in r.samples if s["phase"] == "rest" and s["skin_hal"] is not None]
        last = s_last(r, "rest")
        window = [x for x in sk[-12:]]
        return el >= 120 and last is not None and last.get("status") == 0 and len(window) >= 12 and max(window) - min(window) < 0.3
    why = r.phase_run("rest", a.rest_max, rest_done, first_req_at=5, req_every=10 ** 6)
    r.final_request("rest_end")
    print("rest:", why, flush=True)

    # 2 heat
    r.busy(True)
    why = r.phase_run("heat", a.heat_max, lambda el: r.last_two("heat", "slow"))
    print("heat:", why, [(round(t - r.t0), x) for p, t, x in r.phase_reqs("heat")], flush=True)

    # 3 recover
    r.busy(False)
    why = r.phase_run("recover", a.recover_max, lambda el: r.last_two("recover", "fast"))
    print("recover:", why, [(round(t - r.t0), x) for p, t, x in r.phase_reqs("recover")], flush=True)

    # 4 low duty: on 20 s, off 40 s
    if a.lowduty > 0:
        state = {"on": None}

        def duty(el):
            want = (el % 60) < 20
            if want != state["on"]:
                state["on"] = want
                r.busy(want)
        r.phase_run("lowduty", a.lowduty, None, first_req_at=30, req_every=60, on_tick=duty)
        print("lowduty:", [(round(t - r.t0), x) for p, t, x in r.phase_reqs("lowduty")], flush=True)

    # end state: keep busy on, app in front
    r.phase = "finish"
    r.busy(True)
    time.sleep(5)
    r.sample()
    r.log(kind="event", phase="finish", what="done", detail="keep busy on")
    r.f.close()
    print("done", path, flush=True)
    analyze_file(path)


def s_last(r, phase):
    for s in reversed(r.samples):
        if s["phase"] == phase:
            return s
    return None


def pairs_str(lines):
    return {ln.split(" ", 1)[0]: (ln.split(" ", 1)[1] if " " in ln else "") for ln in lines}


def once(a):
    t0 = time.time()
    out, err = adb(f"{a.ip}:5555", "shell", SAMPLE_CMD)
    s = parse_sample(out)
    print(f"{time.time() - t0:.2f} s; zones {len(s['tz'])}, cooling devices {len(s['cd'])}, stderr {err.strip()[:80]!r}")
    print({k: v for k, v in s.items() if k not in ("tz", "cd")})
    print("cd non-zero:", {k: v for k, v in s["cd"].items() if v})


# ---------------------------------------------------------------- analysis
def flatten(s, tzn, cdn):
    d = {"status": s.get("status"), "skin_hal": s.get("skin_hal"), "skin_cached": s.get("skin_cached"), "headroom0": s.get("headroom0"),
         "headroom60": s.get("headroom60"), "batt_temp": s.get("batt_temp")}
    for k, v in s["tz"].items():
        d[f"tz{k}:{tzn.get(k, '?')}"] = v
    for k, v in s["cd"].items():
        d[f"cd{k}:{cdn.get(k, '?')}"] = v
    for k, v in s["cpu"].items():
        d[f"cpu:{k}"] = v
    return d


def analyze_file(path):
    recs = [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]
    meta = next(x for x in recs if x["kind"] == "meta")
    tzn, cdn = meta["tz_names"], meta["cd_names"]
    samples = [x for x in recs if x["kind"] == "sample"]
    reqs = sorted([x for x in recs if x["kind"] == "req"], key=lambda x: x["t_start"])
    events = [x for x in recs if x["kind"] == "event"]
    t0 = recs[0]["t"]

    def near(t):
        return min(samples, key=lambda s: abs(s["t"] - t))

    rows = []
    for q in reqs:
        s = near(q["t_start"])
        f = flatten(s, tzn, cdn)
        rows.append((q, s, f))
    print("\nrequests: phase, minute, tok/s, level | status skin headroom0 | gpu cdev, cpu-cluster0, cpu-cluster1, kgsl | cpu6 max kHz | battery C | busy state")
    cdkey = lambda name: next((k for k in rows[0][2] if k.startswith("cd") and k.endswith(":" + name)), None) if rows else None
    gpu, c0, c1, kg = cdkey("gpu"), cdkey("cpu-cluster0"), cdkey("cpu-cluster1"), cdkey("kgsl")
    for q, s, f in rows:
        print(f"{q['phase']:9s} {(q['t_start'] - t0) / 60:5.1f}  {q['tok_s']!s:>6}  {q['level']:4s} | {f['status']} {f['skin_hal']} {f['headroom0']} | "
              f"{f.get(gpu)} {f.get(c0)} {f.get(c1)} {f.get(kg)} | {f.get('cpu:p6.scaling_max_freq')} | {f['batt_temp']} | {s['busy'].get('state')}")
    fast = [f for q, s, f in rows if q["level"] == "fast"]
    slow = [f for q, s, f in rows if q["level"] == "slow"]
    print(f"\nfast requests {len(fast)}, slow {len(slow)}, mid {sum(1 for q, s, f in rows if q['level'] == 'mid')}, none {sum(1 for q, s, f in rows if q['level'] == 'none')}")
    if fast and slow:
        print("signals whose values do not overlap between the fast and the slow requests (fast range | slow range):")
        for k in fast[0]:
            fv = [x[k] for x in fast if x.get(k) is not None]
            sv = [x[k] for x in slow if x.get(k) is not None]
            if fv and sv and (max(fv) < min(sv) or max(sv) < min(fv)):
                print(f"  {k}: {min(fv)}..{max(fv)} | {min(sv)}..{max(sv)}")
    print("\nphases: wall s, generating s (secondsGenerating delta), share, thermal status seen, chat restarts seen")
    for ph in ("rest", "heat", "recover", "lowduty"):
        ss = [s for s in samples if s["phase"] == ph]
        if len(ss) < 2:
            continue
        wall = ss[-1]["t"] - ss[0]["t"]
        gen = (ss[-1]["busy"].get("secondsGenerating") or 0) - (ss[0]["busy"].get("secondsGenerating") or 0)
        jobs = (ss[-1]["busy"].get("jobsDone") or 0) - (ss[0]["busy"].get("jobsDone") or 0)
        print(f"  {ph}: {wall:.0f} s, {gen:.1f} s, {gen / wall:.3f}, jobs {jobs}, status {sorted({s.get('status') for s in ss})}, "
              f"restarts {sorted({s['chat'].get('restarts') for s in ss}, key=str)}, chat {sorted({s['chat'].get('state') for s in ss}, key=str)}")
    odd = [e for e in events if e["what"] in ("adb_sample_failed", "adb_reconnect", "woke_screen", "adb_exception")]
    print("\nanomalies:", len(odd), [(round(e["et"]), e["what"]) for e in odd][:20])
    bad = [q for q in reqs if q["http"] != 200]
    print("requests not HTTP 200:", [(q["phase"], q["http"], q["err"]) for q in bad])


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("ip")
    p.add_argument("--rest-max", type=int, default=360)
    p.add_argument("--heat-max", type=int, default=1080)
    p.add_argument("--recover-max", type=int, default=600)
    p.add_argument("--lowduty", type=int, default=600)
    p = sub.add_parser("once")
    p.add_argument("ip")
    p = sub.add_parser("analyze")
    p.add_argument("file")
    a = ap.parse_args()
    if getattr(a, "ip", None) not in (None, "192.168.242.76") and not os.environ.get("THROTTLE_ANY_PHONE"):
        sys.exit("written for Phone C (192.168.242.76) only; set THROTTLE_ANY_PHONE=1 to use another phone")
    {"run": run, "once": once, "analyze": lambda a: analyze_file(a.file)}[a.cmd](a)
