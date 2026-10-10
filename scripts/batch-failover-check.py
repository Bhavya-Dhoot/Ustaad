#!/usr/bin/env python3
"""How a 32-job batch spread over two phones behaves when a phone dies, joins, or the coordinating phone dies (gate 9b).

Usage: python scripts/batch-failover-check.py <coordinator-ip> <peer-ip> <out-dir> <steps>
  <steps> is a string of letters, run in order:  t throughput (one two-phone batch)   p peer dies mid-batch
                                                 j peer joins mid-batch                c coordinator dies mid-batch
  A letter repeated runs the step again ("ttpjc" = throughput twice, then p, j, c). Every step starts the batch on the coordinator (bench32, mode swarm).

Reads and writes only through the phones' own API (signed like scripts/phone-probe.py, development secret unless SWARM_SECRET is set)
and adb with -s <ip>:5555 on the two phones given (never any other phone): thermalservice, force-stop and start of com.swarmos.app.
Raw JSON per step goes to <out-dir>/step-<n>-<letter>.json, an overview to <out-dir>/summary.json, the phones' state at the end
to <out-dir>/final.json. Event times are seconds on this PC's clock; "batch t0" is the moment the start request returned.
"""
import hashlib
import hmac
import json
import os
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PKG = "com.swarmos.app"
COORD, PEER, OUT, STEPS = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
os.makedirs(OUT, exist_ok=True)
NAMES = {"c6f72247": "A(other)"}  # node id -> label; the two phones under test are added in main()
T_START = time.time()


def log(msg):
    print(f"[{time.time() - T_START:7.1f}s] {msg}", flush=True)


def call(ip, method, path, body=None, timeout=20):
    """Signed node API call, same recipe as scripts/phone-probe.py: HMAC-SHA256 over method, path, timestamp, body."""
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
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return {"_status": e.code, "_raw": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return {"_err": f"{type(e).__name__}: {e}"}


def bad(r):
    return isinstance(r, dict) and ("_err" in r or "_status" in r)


def adb(ip, *args, timeout=60):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return (p.stdout + p.stderr).replace("\r", "")


def thermal(ip):
    for line in adb(ip, "shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def foreground(ip):
    out = adb(ip, "shell", "dumpsys", "window")
    return any(PKG in l for l in out.splitlines() if "mCurrentFocus" in l)


def lab(nid):
    return NAMES.get(nid, nid)


def role_of(ip):
    return "coord" if ip == COORD else "peer"


def peers_raw(ip, timeout=8):
    return call(ip, "GET", "/v1/link/peers", timeout=timeout)


def peer_entry(resp, ip):
    if isinstance(resp, dict):
        for p in resp.get("peers", []):
            if p.get("ip") == ip:
                return p
    return None


def peers_brief(ip):
    r = peers_raw(ip)
    if bad(r):
        return {"unreachable": r.get("_err") or r.get("_status")}
    return {"self": (r.get("self") or {}).get("name"),
            "peers": [{k: p.get(k) for k in ("name", "ip", "state", "rttMs", "ageMs", "misses", "awayCount", "thermalStatus")} for p in r.get("peers", [])]}


def models(ip):
    r = call(ip, "GET", "/v1/models/state", timeout=6)
    if bad(r):
        return {"api": False}
    roles = r.get("roles", {})
    return {"api": True, "chat": (roles.get("chat") or {}).get("state"), "backend": (roles.get("chat") or {}).get("backend"),
            "embed": (roles.get("embed") or {}).get("state"), "restarts": (roles.get("chat") or {}).get("restarts")}


def caps(ip, timeout=4):
    r = call(ip, "GET", "/v1/sched/caps", timeout=timeout)
    if bad(r):
        return {"api": False}
    return {"api": True, **{k: r.get(k) for k in ("capacity", "running", "queued", "thermalStatus", "ramAvailMb")}}


def snap(label):
    """Thermal status, peers and scheduler load on both phones at this moment."""
    s = {"label": label, "at": time.strftime("%H:%M:%S"), "thermal": {"coord": thermal(COORD), "peer": thermal(PEER)}}
    for ip in (COORD, PEER):
        s[role_of(ip)] = {"peers": peers_brief(ip), "caps": caps(ip), "models": models(ip)}
    ce = next((p for p in s["coord"]["peers"].get("peers", []) if p["ip"] == PEER), None)
    pe = next((p for p in s["peer"]["peers"].get("peers", []) if p["ip"] == COORD), None)
    log(f"snap {label}: thermal coord/peer {s['thermal']['coord']}/{s['thermal']['peer']}; coord lists peer {ce and ce['state']}; peer lists coord {pe and pe['state']}")
    return s


def ensure_keepbusy_off(ip):
    b = call(ip, "GET", "/v1/sched/busy", timeout=8)
    if b.get("enabled"):
        adb(ip, "shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "keepBusy", "false")
        time.sleep(2)
        b = call(ip, "GET", "/v1/sched/busy", timeout=8)
    return b.get("enabled")


def start_app(ip):
    return adb(ip, "shell", "am", "start", "-n", f"{PKG}/.MainActivity").strip().splitlines()[-1:]


def wait_ready(timeout=150):
    """Both chat models READY, both embedders READY, each phone lists the other online."""
    t0 = time.time()
    last = {}
    while time.time() - t0 < timeout:
        mc, mp = models(COORD), models(PEER)
        ce = peer_entry(peers_raw(COORD, 5), PEER)
        pe = peer_entry(peers_raw(PEER, 5), COORD)
        last = {"coordModels": mc, "peerModels": mp, "coordListsPeer": ce and ce["state"], "peerListsCoord": pe and pe["state"]}
        if (mc.get("chat") == "READY" and mp.get("chat") == "READY" and mc.get("embed") == "READY" and mp.get("embed") == "READY"
                and last["coordListsPeer"] == "online" and last["peerListsCoord"] == "online"):
            return {"ok": True, "elapsedS": round(time.time() - t0, 1), "last": last}
        time.sleep(2)
    return {"ok": False, "elapsedS": round(time.time() - t0, 1), "last": last}


class Sampler(threading.Thread):
    """Calls fn every `every` seconds on its own thread; rows are (PC time, value)."""

    def __init__(self, fn, every):
        super().__init__(daemon=True)
        self.fn, self.every, self.rows, self.stop_ev = fn, every, [], threading.Event()

    def run(self):
        while not self.stop_ev.is_set():
            t = time.time()
            try:
                v = self.fn()
            except Exception as e:
                v = {"err": repr(e)}
            self.rows.append((t, v))
            self.stop_ev.wait(max(0.05, self.every - (time.time() - t)))

    def end(self):
        self.stop_ev.set()
        self.join(timeout=20)

    def rel(self, base):
        return [[round(t - base, 2), v] for t, v in self.rows]


class BatchWatch(Sampler):
    def __init__(self, bid, every=0.4):
        super().__init__(self.poll, every)
        self.bid, self.last = bid, None

    def poll(self):
        r = call(COORD, "GET", f"/v1/sched/batch/{self.bid}", timeout=6)
        if bad(r) or "state" not in r:
            return {"err": r.get("_err") or r.get("_status") or "no state"}
        self.last = r
        c = Counter(lab(i.get("nodeId")) for i in r.get("items", []) if not i.get("error"))
        return {"state": r["state"], "done": r["done"], "failed": r["failed"], "perNode": dict(c)}


def f_peer_on_coord():
    r = peers_raw(COORD, 5)
    e = peer_entry(r, PEER)
    if e is None:
        return {"state": "unreachable" if bad(r) else "absent"}
    return {"state": e["state"], "ageMs": e["ageMs"], "misses": e["misses"]}


def f_coord_on_peer():
    r = peers_raw(PEER, 5)
    e = peer_entry(r, COORD)
    if e is None:
        return {"state": "unreachable" if bad(r) else "absent"}
    return {"state": e["state"], "ageMs": e["ageMs"], "misses": e["misses"]}


def f_phones_on_coord():
    r = call(COORD, "GET", "/v1/sched/phones", timeout=8)
    if not isinstance(r, list):
        return {"err": r.get("_err") or r.get("_status")}
    return {lab(v["nodeId"]): {"up": v["up"], "inflight": v["inflight"], "running": (v.get("caps") or {}).get("running")} for v in r}


def f_peer_models():
    m = models(PEER)
    return {"api": m["api"], "chat": m.get("chat"), "embed": m.get("embed")}


def f_peer_caps():
    c = caps(PEER, 3)
    return {"api": c["api"], "running": c.get("running"), "queued": c.get("queued")}


def start_batch():
    return call(COORD, "POST", "/v1/sched/batch", {"builtin": "bench32", "mode": "swarm"}, timeout=30)


def wait_done_ge(bw, n, timeout=300):
    t = time.time()
    while time.time() - t < timeout:
        L = bw.last
        if L and (L["done"] >= n or L["state"] != "running"):
            return L
        time.sleep(0.1)
    return bw.last


def wait_finished(bw, timeout=600):
    t = time.time()
    while time.time() - t < timeout:
        L = bw.last
        if L and L["state"] != "running":
            return True
        time.sleep(0.5)
    return False


def summarize(rep):
    items = rep.get("items") or []
    ok = [i for i in items if not i.get("error")]
    prompt = [i["promptMs"] for i in ok if i.get("promptMs")]
    return {"id": rep.get("id"), "state": rep.get("state"), "total": rep.get("total"), "done": rep.get("done"), "failed": rep.get("failed"),
            "wallS": round(rep.get("wallMs", 0) / 1000, 2), "aggTokPerSec": round(rep.get("aggTokPerSec", 0), 2), "totalTokens": rep.get("totalTokens"),
            "perPhone": [{"node": lab(p["nodeId"]), "jobs": p["jobs"], "tokens": p["tokens"], "decodeTokPerSec": round(p["decodeTokPerSec"], 2)} for p in rep.get("perPhone", [])],
            "itemsByNode": dict(Counter(lab(i.get("nodeId")) for i in items)),
            "medianPromptMs": round(statistics.median(prompt), 1) if prompt else None,
            "errors": [{"index": i["index"], "node": lab(i.get("nodeId")), "error": i["error"]} for i in items if i.get("error")]}


def first_row(rows, after, pred):
    for t, v in rows:
        if t >= after and pred(v):
            return t
    return None


def last_row_before(rows, before):
    prev = None
    for t, v in rows:
        if t >= before:
            break
        prev = (t, v)
    return prev


def run_batch(tag, timeout=900):
    """One batch to the end; returns (record, final report)."""
    rec = {"tag": tag, "before": snap(f"{tag} before")}
    st = start_batch()
    if "id" not in st:
        rec["error"] = f"batch did not start: {st}"
        log(rec["error"])
        return rec, None
    t0 = time.time()
    bw = BatchWatch(st["id"])
    bw.start()
    ok = wait_finished(bw, timeout)
    bw.end()
    rep = call(COORD, "GET", f"/v1/sched/batch/{st['id']}", timeout=30)
    rec["pcWallS"] = round(time.time() - t0, 1)
    rec["finished"] = ok
    rec["report"] = rep
    rec["summary"] = summarize(rep) if "state" in rep else {"error": rep}
    rec["after"] = snap(f"{tag} after")
    return rec, rep


def step_t(n):
    rec, rep = run_batch(f"t{n}")
    s = rec.get("summary", {})
    log(f"t{n}: {s.get('done')}/{s.get('total')} done, {s.get('failed')} failed, wall {s.get('wallS')} s, agg {s.get('aggTokPerSec')} tok/s, perPhone {s.get('perPhone')}, median promptMs {s.get('medianPromptMs')}")
    return rec


def gaps_around(items, off_ms):
    ends = sorted(i["endMs"] for i in items if i.get("endMs"))
    before = [e for e in ends if e <= off_ms]
    after = [e for e in ends if e > off_ms]
    allgaps = [round(b - a) for a, b in zip(ends, ends[1:])]
    return {"lastFinishBeforeMs": round(before[-1]) if before else None, "firstFinishAfterMs": round(after[0]) if after else None,
            "gapAcrossKillMs": round(after[0] - before[-1]) if before and after else None, "medianGapMs": round(statistics.median(allgaps)) if allgaps else None,
            "maxGapMs": max(allgaps) if allgaps else None}


def step_p():
    rec = {"tag": "p", "before": snap("p before")}
    st = start_batch()
    if "id" not in st:
        rec["error"] = f"batch did not start: {st}"
        return rec
    t0 = time.time()
    peer_id = next((k for k, v in NAMES.items() if v == "peer"), None)
    bw = BatchWatch(st["id"])
    s_peer, s_phones = Sampler(f_peer_on_coord, 0.5), Sampler(f_phones_on_coord, 2.0)
    for s in (bw, s_peer, s_phones):
        s.start()
    L = wait_done_ge(bw, 8)
    rec["doneAtKill"] = L and {"done": L["done"], "state": L["state"], "perNode": dict(Counter(lab(i.get("nodeId")) for i in L.get("items", []) if not i.get("error")))}
    tk0 = time.time()
    out = adb(PEER, "shell", "am", "force-stop", PKG)
    tk1 = time.time()
    log(f"p: peer app force-stopped at batch t0+{tk0 - t0:.1f}s (done={L and L['done']}); adb returned after {tk1 - tk0:.2f}s")
    ok = wait_finished(bw, 600)
    for s in (bw, s_peer, s_phones):
        s.end()
    rep = call(COORD, "GET", f"/v1/sched/batch/{st['id']}", timeout=30)
    rec.update({"killIssuedAtS": round(tk0 - t0, 2), "killReturnedAtS": round(tk1 - t0, 2), "finished": ok, "report": rep,
                "summary": summarize(rep) if "state" in rep else {"error": rep}})
    away = first_row(s_peer.rows, tk1, lambda v: v.get("state") == "away")
    nononline = first_row(s_peer.rows, tk1, lambda v: v.get("state") != "online")
    down = first_row(s_phones.rows, tk1, lambda v: isinstance(v.get(lab(peer_id)), dict) and v[lab(peer_id)]["up"] is False)
    pre = last_row_before(s_phones.rows, tk0)
    rec["events"] = {
        "peerFirstNotOnlineAfterKillS": nononline and round(nononline - tk1, 2),
        "coordListsPeerAwayAfterKillS": away and round(away - tk1, 2),
        "coordListsPeerAwayAfterKillIssuedS": away and round(away - tk0, 2),
        "schedulerMarkedPeerDownAfterKillS (first /v1/sched/phones sample with up=false, 2 s sampling)": down and round(down - tk1, 2),
        "lastPhonesSampleBeforeKill": pre and {"secondsBeforeKill": round(tk0 - pre[0], 2), "view": pre[1]},
        "peerStateSequence": [[round(t - tk1, 2), v.get("state")] for (t, v), prev in zip(s_peer.rows, [None] + s_peer.rows[:-1]) if t >= tk0 and (prev is None or prev[1].get("state") != v.get("state"))],
    }
    if "items" in rep:
        items = rep["items"]
        off = (tk1 - t0) * 1000
        rec["events"]["peerJobsFinishedAfterKill"] = sum(1 for i in items if i.get("nodeId") == peer_id and i.get("endMs", 0) > off)
        rec["events"]["peerJobsFinishedBeforeKill"] = sum(1 for i in items if i.get("nodeId") == peer_id and i.get("endMs", 0) <= off)
        rec["events"]["timingAroundKill"] = gaps_around(items, off)
    rec["samplers"] = {"batch": bw.rel(t0), "peerOnCoord": s_peer.rel(t0), "phonesOnCoord": s_phones.rel(t0)}
    rec["after"] = snap("p after (peer still stopped)")
    # bring the peer back
    ts = time.time()
    start_app(PEER)
    rec["restart"] = wait_ready(150)
    rec["restart"]["secondsFromAppStart"] = round(time.time() - ts, 1)
    rec["keepBusyEnabledAfterRestart"] = {"peer": ensure_keepbusy_off(PEER), "coord": ensure_keepbusy_off(COORD)}
    rec["afterRestart"] = snap("p after restart")
    s = rec["summary"]
    log(f"p: {s.get('done')}/{s.get('total')} done, {s.get('failed')} failed, wall {s.get('wallS')} s, perPhone {s.get('perPhone')}, away after {rec['events']['coordListsPeerAwayAfterKillS']} s, errors {s.get('errors')}")
    return rec


def step_j():
    rec = {"tag": "j", "before": snap("j before")}
    ts = time.time()
    adb(PEER, "shell", "am", "force-stop", PKG)
    t_away = None
    while time.time() - ts < 120:
        e = peer_entry(peers_raw(COORD, 5), PEER)
        if e and e["state"] == "away":
            t_away = time.time()
            break
        time.sleep(0.5)
    rec["peerStoppedToAwayS"] = t_away and round(t_away - ts, 1)
    rec["afterStop"] = snap("j peer stopped, before batch")
    if t_away is None:
        rec["error"] = "coordinator never listed the stopped peer as away within 120 s"
        return rec
    st = start_batch()
    if "id" not in st:
        rec["error"] = f"batch did not start: {st}"
        return rec
    t0 = time.time()
    peer_id = next((k for k, v in NAMES.items() if v == "peer"), None)
    bw = BatchWatch(st["id"])
    s_peer, s_phones, s_pm = Sampler(f_peer_on_coord, 0.5), Sampler(f_phones_on_coord, 2.0), Sampler(f_peer_models, 1.0)
    for s in (bw, s_peer, s_phones, s_pm):
        s.start()
    L = wait_done_ge(bw, 4)
    ta = time.time()
    start_app(PEER)
    rec["doneAtPeerAppStart"] = L and L["done"]
    log(f"j: peer app started at batch t0+{ta - t0:.1f}s (done={L and L['done']})")
    ok = wait_finished(bw, 600)
    for s in (bw, s_peer, s_phones, s_pm):
        s.end()
    rep = call(COORD, "GET", f"/v1/sched/batch/{st['id']}", timeout=30)
    rec.update({"peerAppStartedAtS": round(ta - t0, 2), "finished": ok, "report": rep, "summary": summarize(rep) if "state" in rep else {"error": rep}})
    api = first_row(s_pm.rows, ta, lambda v: v.get("api"))
    chat = first_row(s_pm.rows, ta, lambda v: v.get("chat") == "READY")
    online = first_row(s_peer.rows, ta, lambda v: v.get("state") == "online")
    up = first_row(s_phones.rows, ta, lambda v: isinstance(v.get(lab(peer_id)), dict) and v[lab(peer_id)]["up"] is True)
    ev = {"peerApiAnswersAfterAppStartS": api and round(api - ta, 1), "peerChatReadyAfterAppStartS": chat and round(chat - ta, 1),
          "coordListsPeerOnlineAfterAppStartS": online and round(online - ta, 1), "schedulerMarksPeerUpAfterAppStartS": up and round(up - ta, 1)}
    if "items" in rep:
        items = sorted(rep["items"], key=lambda i: i.get("endMs", 0))
        first = next((n for n, i in enumerate(items, 1) if i.get("nodeId") == peer_id), None)
        ev["peerJobs"] = sum(1 for i in items if i.get("nodeId") == peer_id)
        ev["doneCountWhenPeersFirstJobEnded (rank by endMs)"] = first
        ev["peerFirstJobEndedAtBatchS"] = first and round(items[first - 1]["endMs"] / 1000, 1)
        ev["peerFirstJobEndedAfterAppStartS"] = first and round(items[first - 1]["endMs"] / 1000 - (ta - t0), 1)
    rec["events"] = ev
    rec["samplers"] = {"batch": bw.rel(t0), "peerOnCoord": s_peer.rel(t0), "phonesOnCoord": s_phones.rel(t0), "peerModels": s_pm.rel(t0)}
    rec["after"] = snap("j after")
    rec["settle"] = wait_ready(150)
    rec["keepBusyEnabledAfterRestart"] = {"peer": ensure_keepbusy_off(PEER), "coord": ensure_keepbusy_off(COORD)}
    s = rec["summary"]
    log(f"j: {s.get('done')}/{s.get('total')} done, {s.get('failed')} failed, wall {s.get('wallS')} s, perPhone {s.get('perPhone')}, events {ev}")
    return rec


def step_c():
    rec = {"tag": "c", "before": snap("c before")}
    st = start_batch()
    if "id" not in st:
        rec["error"] = f"batch did not start: {st}"
        return rec
    t0 = time.time()
    old_id = st["id"]
    bw = BatchWatch(old_id)
    s_cap, s_cop = Sampler(f_peer_caps, 1.0), Sampler(f_coord_on_peer, 1.0)
    for s in (bw, s_cap, s_cop):
        s.start()
    L = wait_done_ge(bw, 8)
    rec["doneAtKill"] = L and {"done": L["done"], "state": L["state"], "perNode": dict(Counter(lab(i.get("nodeId")) for i in L.get("items", []) if not i.get("error")))}
    tk0 = time.time()
    adb(COORD, "shell", "am", "force-stop", PKG)
    tk1 = time.time()
    start_app(COORD)
    ts = time.time()
    log(f"c: coordinator app force-stopped at batch t0+{tk0 - t0:.1f}s (done={L and L['done']}), started again {ts - tk1:.1f}s later")
    api = None
    while time.time() - tk1 < 120:
        r = peers_raw(COORD, 3)
        if not bad(r):
            api = time.time()
            break
        time.sleep(0.5)
    rec["coordApiAnswersAfterKillS"] = api and round(api - tk1, 1)
    old = call(COORD, "GET", f"/v1/sched/batch/{old_id}", timeout=10)
    rec["oldBatchAtApiUp"] = {k: old.get(k) for k in ("id", "state", "done", "failed", "total", "wallMs", "_status", "_raw", "_err")}
    rec["oldBatchItemsFinished"] = len(old.get("items") or [])
    # wait for the restarted coordinator to be usable (chat model READY, peer online), then start a new batch at once
    tw = time.time()
    rd = wait_ready(150)
    rec["restartReady"] = {**rd, "secondsFromKill": round(time.time() - tk1, 1)}
    rec["keepBusyEnabledAfterRestart"] = {"peer": ensure_keepbusy_off(PEER), "coord": ensure_keepbusy_off(COORD)}
    old2 = call(COORD, "GET", f"/v1/sched/batch/{old_id}", timeout=10)
    rec["oldBatchWhenReady"] = {k: old2.get(k) for k in ("id", "state", "done", "failed", "total", "wallMs", "_status", "_raw", "_err")}
    rec["beforeNewBatch"] = snap("c before new batch")
    tn0 = time.time()
    new = start_batch()
    rec["newBatchStart"] = new
    rec["newBatchStartedAfterKillS"] = round(tn0 - tk1, 1)
    bw.end()
    if "id" in new:
        t1 = time.time()
        bw2 = BatchWatch(new["id"])
        bw2.start()
        wait_finished(bw2, 900)
        bw2.end()
        rep = call(COORD, "GET", f"/v1/sched/batch/{new['id']}", timeout=30)
        rec["newBatch"] = {"summary": summarize(rep) if "state" in rep else {"error": rep}, "report": rep, "pcWallS": round(time.time() - t1, 1)}
    for s in (s_cap, s_cop):
        s.end()
    pre = last_row_before(s_cap.rows, tk0)
    idle = first_row(s_cap.rows, tk1, lambda v: v.get("api") and v.get("running") == 0 and v.get("queued") == 0)
    stayed = None
    if idle:
        stayed = all(v.get("running") == 0 for t, v in s_cap.rows if idle <= t < tn0)
    rec["peerIdle"] = {"lastCapsBeforeKill": pre and {"secondsBeforeKill": round(tk0 - pre[0], 2), "value": pre[1]},
                       "firstIdleSampleAfterKillS": idle and round(idle - tk1, 1), "idleWithin60s": bool(idle and idle - tk1 <= 60),
                       "stayedIdleUntilNewBatchStarted": stayed, "newBatchStartedAfterKillS": round(tn0 - tk1, 1),
                       "capsRowsFirst40sAfterKill": [[round(t - tk1, 1), v.get("running"), v.get("queued")] for t, v in s_cap.rows if tk1 <= t <= tk1 + 40]}
    rec["samplers"] = {"oldBatch": bw.rel(t0), "peerCaps": s_cap.rel(t0), "coordOnPeer": s_cop.rel(t0)}
    rec["after"] = snap("c after")
    ns = rec.get("newBatch", {}).get("summary", {})
    log(f"c: old batch at API up {rec['oldBatchAtApiUp']}, when ready {rec['oldBatchWhenReady']}; new batch start {new if 'id' not in new else 'ok'}; new {ns.get('done')}/{ns.get('total')} done, wall {ns.get('wallS')} s; peer idle {rec['peerIdle']['firstIdleSampleAfterKillS']} s after kill")
    return rec


def end_state():
    res = {}
    for ip in (COORD, PEER):
        start_app(ip)
    time.sleep(2)
    for ip in (COORD, PEER):
        m = models(ip)
        if m.get("chat") != "READY":
            log(f"{role_of(ip)} chat not READY ({m}), loading")
            res[f"{role_of(ip)}LoadCall"] = call(ip, "POST", "/v1/models/load", {"role": "chat", "file": "Qwen3.5-4B-Q4_K_M.gguf"}, timeout=30)
    res["wait"] = wait_ready(150)
    for ip in (COORD, PEER):
        res[role_of(ip)] = {"foreground": foreground(ip), "thermal": thermal(ip), "keepBusyEnabled": ensure_keepbusy_off(ip), "models": models(ip), "peers": peers_brief(ip)}
    return res


def main():
    for ip, role in ((COORD, "coord"), (PEER, "peer")):
        r = peers_raw(ip)
        NAMES[(r.get("self") or {}).get("nodeId")] = role
    log(f"nodes: {NAMES}")
    summary = {"command": "python " + " ".join(sys.argv), "startedAt": time.strftime("%Y-%m-%d %H:%M:%S"), "nodes": NAMES, "steps": []}
    # keep busy off, thermal wait (at most 10 minutes)
    summary["keepBusyEnabled"] = {role_of(ip): ensure_keepbusy_off(ip) for ip in (COORD, PEER)}
    tw = time.time()
    wait_s = float(os.environ.get("THERMAL_WAIT_S", "600"))  # at most 10 minutes for status 0 or 1 on both; 0 = record and go on
    while True:
        th = (thermal(COORD), thermal(PEER))
        if all(x in ("0", "1") for x in th) or time.time() - tw >= wait_s:
            break
        time.sleep(15)
    summary["thermalWait"] = {"seconds": round(time.time() - tw), "coordPeer": th}
    log(f"thermal at start {th} after {time.time() - tw:.0f}s; keep busy enabled {summary['keepBusyEnabled']}")
    summary["initialReady"] = wait_ready(120)
    log(f"initial ready: {summary['initialReady']['ok']}")
    seq = 0
    for letter in STEPS:
        seq += 1
        n = sum(1 for x in STEPS[:seq] if x == letter)
        t_step = time.time()
        fn = {"t": lambda: step_t(n), "p": step_p, "j": step_j, "c": step_c}.get(letter)
        if fn is None:
            continue
        try:
            rec = fn()
        except Exception as e:
            rec = {"tag": letter, "error": f"{type(e).__name__}: {e}"}
            log(f"step {letter} raised {rec['error']}")
        rec["stepSeconds"] = round(time.time() - t_step, 1)
        with open(os.path.join(OUT, f"step-{seq}-{letter}.json"), "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1, default=str)
        summary["steps"].append({"step": letter, "file": f"step-{seq}-{letter}.json", "summary": rec.get("summary") or (rec.get("newBatch") or {}).get("summary"),
                                 "events": rec.get("events"), "error": rec.get("error")})
        with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=1, default=str)
        # make sure the pair is whole again before the next step
        w = wait_ready(150)
        log(f"after step {letter}: pair ready {w['ok']}")
        if not w["ok"]:
            summary["steps"][-1]["pairNotReadyAfterStep"] = w["last"]
    summary["final"] = end_state()
    with open(os.path.join(OUT, "final.json"), "w", encoding="utf-8") as f:
        json.dump(summary["final"], f, ensure_ascii=False, indent=1, default=str)
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1, default=str)
    log(f"final: {json.dumps(summary['final'], ensure_ascii=False)[:900]}")


main()
