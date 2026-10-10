#!/usr/bin/env python3
"""Phone A only. Rounds of: am force-stop, one am start, then time (a) the first answer of a signed GET /v1/sched/busy and
(b) chat READY (give up at 120 s). If no answer within 30 s: capture dumps, then a second am start and note what happens.
usage: restart_rounds.py <out-dir> <rounds>"""
import hashlib, hmac, json, os, random, subprocess, sys, time, urllib.error, urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
IP, SER, PKG = "192.168.242.89", "192.168.242.89:5555", "com.swarmos.app"
out, rounds = sys.argv[1], int(sys.argv[2])
os.makedirs(out, exist_ok=True)
SECRET = os.environ["SWARM_SECRET"]
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def adb(*args, timeout=60):
    t = time.time()
    p = subprocess.run([ADB, "-s", SER, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "").strip(), time.time() - t


def call(path, timeout=2.0):
    ts = str(int(time.time() * 1000))
    sig = hmac.new(SECRET.encode(), f"GET\n{path}\n{ts}\n".encode(), hashlib.sha256).hexdigest()
    h = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    try:
        with urllib.request.urlopen(urllib.request.Request(f"http://{IP}:8787{path}", headers=h, method="GET"), timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return 0, {"_err": type(e).__name__}


def chat_state():
    s, b = call("/v1/models/state", 3)
    r = (b.get("roles") or {}).get("chat") or {}
    return s, r.get("state"), r.get("backend"), r.get("pid")


def thermal():
    o, _ = adb("shell", "dumpsys thermalservice | grep 'Thermal Status' | head -1")
    return o.split()[-1] if o else "?"


def wait_ready(limit):
    t0 = time.time()
    while time.time() - t0 < limit:
        s, st, be, pid = chat_state()
        e = (call("/v1/models/state", 3)[1].get("roles") or {}).get("embed", {}).get("state")
        if s == 200 and st == "READY" and e == "READY":
            return round(time.time() - t0, 1)
        time.sleep(2)
    return None


DUMPS = [
    ("activities", "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|com.swarmos'"),
    ("services", "dumpsys activity services com.swarmos.app | head -40"),
    ("power", "dumpsys power | grep -E 'mWakefulness|mHoldingDisplay'"),
    ("window", "dumpsys window | grep -E 'mDreamingLockscreen|mCurrentFocus|isKeyguardShowing'"),
    ("ps", "ps -A | grep -E 'swarmos|llama'"),
]


def capture(r, tag):
    txt = [f"# round {r} {tag} at {time.strftime('%H:%M:%S')}"]
    for name, cmd in DUMPS:
        o, _ = adb("shell", cmd, timeout=60)
        txt.append(f"## {cmd}\n{o}\n")
    with open(os.path.join(out, f"round{r}-dumps-{tag}.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(txt))


summary = []
for r in range(1, rounds + 1):
    rec = {"round": r}
    rd = wait_ready(180)
    rec["readyBefore_s"] = rd
    rec["thermalBefore"] = thermal()
    rec["focusBefore"], _ = adb("shell", "dumpsys window | grep mCurrentFocus")
    rec["pidBefore"], _ = adb("shell", f"pidof {PKG}")
    fs_out, fs_dt = adb("shell", "am", "force-stop", PKG)
    t0 = time.time()
    st_out, st_dt = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    rec.update(forceStopCmd_s=round(fs_dt, 2), amStartCmd_s=round(st_dt, 2), amStartOut=st_out.splitlines()[-1:] , t0=time.strftime("%H:%M:%S", time.localtime(t0)))
    t_api = t_chat = t_second = t_api2 = None
    captured = False
    while time.time() - t0 < 120:
        now = time.time() - t0
        if t_api is None:
            s, b = call("/v1/sched/busy", 1.5)
            if s == 200:
                t_api = round(time.time() - t0, 1)
                rec["busyAtFirstAnswer"] = b
                if t_second is not None:
                    t_api2 = t_api
        if t_api is not None and t_chat is None:
            s, st, be, pid = chat_state()
            if s == 200 and st == "READY":
                t_chat = round(time.time() - t0, 1)
                rec["chatBackend"] = be
                break
        if t_api is None and not captured and now >= 30:
            captured = True
            capture(r, "before-second-start")
            ts = time.time()
            o2, _ = adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
            t_second = round(ts - t0, 1)
            rec["secondStartAt_s"] = t_second
            rec["secondStartOut"] = o2.splitlines()[-2:]
        time.sleep(0.5)
    rec.update(apiAnswers_s=t_api, chatReady_s=t_chat, faultSeen=captured)
    if captured:
        rec["apiAfterSecondStart_s_from_t0"] = t_api2
        rec["apiAfterSecondStart_s_from_second"] = None if t_api2 is None else round(t_api2 - t_second, 1)
        capture(r, "after")
    rec["thermalAfter"] = thermal()
    summary.append(rec)
    with open(os.path.join(out, f"round{r}.json"), "w", encoding="utf-8") as f:
        json.dump(rec, f, ensure_ascii=False, indent=1)
    print(f"round {r}: api {t_api} s, chat READY {t_chat} s, fault {captured}"
          + (f", second am start at {t_second} s, api after it {t_api2}" if captured else ""), flush=True)
    if t_chat is None:  # give it more time before the next round
        extra = wait_ready(180)
        print(f"  waited for READY before next round: {extra}", flush=True)
with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=1)
