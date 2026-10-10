#!/usr/bin/env python3
"""Speculation without a draft model (llama-server n-gram options) on one phone's GPU: speed and identical output (gate 9b).

Usage:
  python scripts/spec-probe.py 192.168.242.23 <out-dir> [--first N] base simple base simple ...
  python scripts/spec-probe.py 192.168.242.23 <out-dir> --report
Each name is one pass: wait for Thermal Status 0 or 1 (at most 8 minutes, the status is recorded either way), start the app's own
OpenCL llama-server binary standalone on loopback port 8090 with the app's chat arguments plus the variant's flags, send the 12 prompts
one after another (temperature 0, fixed seed, at most 128 new tokens), keep the full answers and timings, kill the server by its pid.
Names: base, simple (ngram-simple n=4 m=8), simple12 (ngram-simple, llama.cpp defaults n=12 m=48), mod (ngram-mod, llama.cpp defaults =
--spec-default), mapk (ngram-map-k n=4 m=8; defined, not run in the 2026-10-10 session).
Nothing is pushed to the phone: the binary is the one in the installed app's native library folder and the model is the app's
own file under /sdcard/Android/data/com.swarmos.app/files/models. The app's chat server is stopped first (signed call) and is NOT
reloaded here: run `python scripts/phone-probe.py <ip> reload chat` afterwards. Only Phone B (192.168.242.23) is accepted.
--report prints the per-pass tables from the pass-*.json files in <out-dir>.
"""
import argparse
import glob
import hashlib
import hmac
import json
import os
import random
import re
import secrets
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ONLY_PHONE = "192.168.242.23"
PKG = "com.swarmos.app"
MODEL = "/storage/emulated/0/Android/data/com.swarmos.app/files/models/Qwen3.5-4B-Q4_K_M.gguf"
BINARY = "libllama-server-gpu.so"
PORT, LOCAL = 8090, 18090
MAX_NEW = 128

# Flags added to the app's chat arguments (common/arg.cpp of third_party/llama.cpp, names checked with --help on the phone).
VARIANTS = {
    "base": [],
    "simple": ["--spec-type", "ngram-simple", "--spec-ngram-simple-size-n", "4", "--spec-ngram-simple-size-m", "8"],
    "simple12": ["--spec-type", "ngram-simple"],
    "mod": ["--spec-type", "ngram-mod"],
    "mapk": ["--spec-type", "ngram-map-k", "--spec-ngram-map-k-size-n", "4", "--spec-ngram-map-k-size-m", "8"],
}

# ---------------------------------------------------------------------------------------------------------------- prompts
DU_SYSTEM = ("You answer a question about the user's own files using only the numbered sources below. "
             "Answer in at most 80 words. Put the source number in square brackets, like [1], after each fact. "
             "If the sources do not contain the answer, say that you found nothing relevant. Do not invent anything.")
FOOT = "SYNTHETIC DEMO DATA - not a real document"
CORPUS = os.path.join(REPO, "docs", "demo-corpus")


def corpus_text(rel):
    with open(os.path.join(CORPUS, rel.replace("/", os.sep)), encoding="utf-8") as f:
        return " ".join(f.read().split())


# (device, title, date, snippet): text files are read from docs/demo-corpus, photo texts are what the recognizer reads from the
# generated pictures (scripts/make-demo-corpus.py), whitespace flattened like PromptBuilder.sourceLine does.
SOURCES = {
    "elec": ("Phone C", "IMG_3301_Electricity_Bill.jpg", "2026-09-20", "SUNRISE POWER DISTRIBUTION (DEMO) ELECTRICITY BILL 09/2026 Account ID : 8829104921 Meter : 48821 Units used : 312 Due date : 24-Sep-2026 Amount payable INR 2,410.00 Status: PAID via UPI " + FOOT),
    "mobile": ("Phone A", "IMG_1046_Mobile_Bill_Sept.jpg", "2026-10-02", "PINGLINE MOBILE (DEMO) Monthly bill September 2026 Plan: 2 GB per day, 84 days Bill amount INR 599.00 Due date 12-Oct-2026 " + FOOT),
    "pharm": ("Phone C", "IMG_3304_Pharmacy_Receipt.jpg", "2026-10-04", "GREENCROSS PHARMACY (DEMO) Receipt 40127 04-Oct-2026 Vitamin D3 tablets x1 180.00 Cough syrup 100 ml 95.00 Bandage roll x2 40.00 TOTAL 315.00 " + FOOT),
    "grocery": ("Phone B", "IMG_2232_Grocery_Receipt.jpg", "2026-10-02", "FRESHNEST SUPERMART (DEMO) Bill No 88213 02-Oct-2026 Tomatoes 1 kg 42.00 Basmati rice 5 kg 610.00 Toor dal 1 kg 168.00 Amul-style curd 400g 38.00 Bananas 12 pcs 60.00 TOTAL 918.00 Paid by UPI " + FOOT),
    "resto": ("Phone B", "IMG_2233_Restaurant_Bill.jpg", "2026-09-21", "SPICE ALLEY KITCHEN (DEMO) Table 7 Bill 5521 21-Sep-2026 Paneer tikka 320.00 Veg biryani x2 560.00 Masala chai x2 80.00 Service charge 96.00 TOTAL 1056.00 " + FOOT),
    "cab": ("Phone B", "IMG_2234_Cab_Receipt.jpg", "2026-09-14", "RIDEWAVE CABS (DEMO) Trip receipt 14-Sep-2026 Pickup : Fictional Nagar Metro Drop : Imaginary Tech Park Distance 11.4 km Time 38 min Fare INR 312.00 " + FOOT),
    "bus": ("Phone B", "IMG_2235_Bus_Ticket.jpg", "2026-10-01", "CITYLINK EXPRESS (DEMO) E-ticket PNR CL77412 Seat 14W Bengaluru to Mysuru 06:30 Journey date 03-Oct-2026 INR 450 " + FOOT),
    "gym": ("Phone A", "IMG_1045_Gym_Timetable.jpg", "2026-09-01", "IRONLEAF FITNESS (DEMO) Timetable Mon Strength 6 am Wed Cardio 6 am Fri Core and mobility 7 am Sat Open floor 8 to 11 am " + FOOT),
    "board": ("Phone C", "IMG_3303_Whiteboard_Architecture.jpg", "2026-10-01", "QUARTER PLAN Phase 1 data cleanup Phase 2 new dashboard Phase 3 rollout to teams Owner: Tara " + FOOT),
    "yoga": ("Phone B", "Yoga_Class_Schedule.txt", "2026-09-28", corpus_text("B/Documents/UstaadDemo/Yoga_Class_Schedule.txt")),
    "dentist": ("Phone A", "Notes_Dentist_Appointment.txt", "2026-10-05", corpus_text("A/Documents/UstaadDemo/Notes_Dentist_Appointment.txt")),
    "goa": ("Phone A", "Goa_Trip_Itinerary.txt", "2026-10-03", corpus_text("A/Documents/UstaadDemo/Goa_Trip_Itinerary.txt")),
    "meeting": ("Phone C", "Meeting_Notes_Q4_Planning.txt", "2026-10-02", corpus_text("C/Documents/UstaadDemo/Meeting_Notes_Q4_Planning.txt")),
    "pet": ("Phone C", "Pet_Vaccination_Record.txt", "2026-10-03", corpus_text("C/Documents/UstaadDemo/Pet_Vaccination_Record.txt")),
    "wifi": ("Phone C", "Wifi_Router_Setup_Notes.md", "2026-09-30", corpus_text("C/Documents/UstaadDemo/Wifi_Router_Setup_Notes.md")),
    "trek": ("Phone B", "Weekend_Trek_Packing_List.txt", "2026-10-06", corpus_text("B/Documents/UstaadDemo/Weekend_Trek_Packing_List.txt")),
}
DU_ASKS = [
    ("du1", "When is my electricity bill due and how much is it?", ["mobile", "pharm", "elec", "grocery", "wifi", "meeting"]),
    ("du2", "What was the total on the restaurant bill and what did we order?", ["grocery", "resto", "cab", "pharm", "yoga", "gym"]),
    ("du3", "When is the yoga class and how much is the monthly fee?", ["gym", "yoga", "dentist", "goa", "trek", "bus"]),
    ("du4", "How much was the cab ride and where did it go?", ["bus", "resto", "cab", "grocery", "mobile", "goa"]),
    ("du5", "What did we decide in the Q4 planning meeting and who owns the data migration?", ["board", "meeting", "wifi", "pet", "dentist", "gym"]),
    ("du6", "When is my dentist appointment and what should I bring?", ["goa", "pet", "dentist", "yoga", "meeting", "mobile"]),
]

CH_FORMAT = ("Reply with one JSON object only: key_steps (at most 3 short steps; the last step states the final result), "
             "answer (only the final result), confidence (a number from 0 to 1).")
CH_RULE = {"MATH": "The answer is one plain number: no units, no commas, no words.",
           "CODE": "The answer is exactly what the program prints or returns, nothing else."}
CH_METHOD = {"MATH": "Work step by step. Write each calculation in key_steps like 12*3=36.",
             "CODE": "Trace the code step by step; track variable values in key_steps."}


def choir_schema(kind):
    answer = {"type": "number"} if kind == "MATH" else {"type": "string", "minLength": 1, "maxLength": 60}
    return json.dumps({"type": "object", "properties": {
        "key_steps": {"type": "array", "items": {"type": "string", "maxLength": 100}, "maxItems": 3},
        "answer": answer, "confidence": {"type": "number"}},
        "required": ["key_steps", "answer", "confidence"]}, separators=(",", ":"))


def candidate(label, answer, steps):
    return f"{label}Answer: {answer.strip()}" + (f"\n   Steps: {'; '.join(steps)}" if steps else "") + "\n"


def build_prompts():
    with open(os.path.join(REPO, "app", "app", "src", "main", "assets", "choir", "problems.json"), encoding="utf-8") as f:
        problems = {p["id"]: p for p in json.load(f)}
    out = []
    for pid, q, keys in DU_ASKS:
        lines = []
        for n, k in enumerate(keys, 1):
            dev, title, day, snip = SOURCES[k]
            lines.append(f"[{n}] {dev} \u00b7 {title} \u00b7 {day}: {' '.join(snip.split())}")
        out.append({"id": pid, "group": "data-union", "max_tokens": MAX_NEW, "seed": 42, "schema": None,
                    "messages": [{"role": "system", "content": DU_SYSTEM}, {"role": "user", "content": "Sources:\n" + "\n".join(lines) + f"\n\nQuestion: {q}"}]})
    for pid, kind, name in (("m1", "MATH", "ch1"), ("c2", "CODE", "ch2")):
        sysmsg = f"You are one of several independent solvers on separate phones. {CH_METHOD[kind]} {CH_RULE[kind]} {CH_FORMAT}"
        out.append({"id": name, "group": "choir", "max_tokens": MAX_NEW, "seed": 42, "schema": choir_schema(kind),
                    "messages": [{"role": "system", "content": sysmsg}, {"role": "user", "content": problems[pid]["text"]}]})
    # a revise round: the prompt carries the earlier answers with their steps, as ChoirPrompts.revise builds it (candidates written by hand)
    m3 = problems["m3"]["text"]
    body = f"Problem:\n{m3}\n\n" + candidate("Your earlier answer. ", "164.5", ["3*45+2*18=171", "500-171=329", "329/2=164.5"]) + "\nOther solvers answered (authors unknown):\n"
    body += candidate("1. ", "164", ["3*45+2*18=171", "500-171=329", "329/2=164"]) + candidate("2. ", "164.5", ["3*45=135 and 2*18=36", "135+36=171, change 500-171=329", "half of 329 is 164.5"])
    rev = ("You check answers. Find the single step where the answers differ, check that step yourself, then keep or change your answer. "
           f"Do not change your answer just because others disagree. {CH_RULE['MATH']} {CH_FORMAT}")
    out.append({"id": "ch3", "group": "choir", "max_tokens": MAX_NEW, "seed": 42, "schema": choir_schema("MATH"),
                "messages": [{"role": "system", "content": rev}, {"role": "user", "content": body}]})
    for i, topic in ((0, "the monsoon"), (2, "binary search"), (4, "photosynthesis")):  # BatchRunner Bench.bench32 topics 0, 2, 4
        out.append({"id": f"pl{i}", "group": "plain", "max_tokens": 48, "seed": i, "schema": None,
                    "messages": [{"role": "system", "content": "Answer in one short sentence."}, {"role": "user", "content": f"Explain {topic}."}]})
    return out


def chat_body(p):
    b = {"model": "Qwen3.5-4B-Q4_K_M", "stream": False, "max_tokens": p["max_tokens"], "temperature": 0.0, "cache_prompt": True,
         "seed": p["seed"], "messages": p["messages"]}
    if p["schema"]:
        b["response_format"] = {"type": "json_schema", "json_schema": {"name": "response", "strict": True, "schema": json.loads(p["schema"])}}
    b["chat_template_kwargs"] = {"enable_thinking": False}
    return b


# ---------------------------------------------------------------------------------------------------------------- phone side
def adb(ip, *args, timeout=120):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return (p.stdout + p.stderr).replace("\r", "")


def thermal(ip):
    for line in adb(ip, "shell", "dumpsys", "thermalservice", timeout=60).splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def wait_thermal(ip, limit=1, max_s=480):
    t0 = time.time()
    s = thermal(ip)
    while s not in [str(x) for x in range(limit + 1)] and time.time() - t0 < max_s:
        time.sleep(20)
        s = thermal(ip)
    return s, int(time.time() - t0)


def signed(ip, method, path, body=None):
    secret = os.environ.get("SWARM_SECRET", "swarmos-dev-secret")
    text = "" if body is None else json.dumps(body)
    ts = str(int(time.time() * 1000))
    sig = hmac.new(secret.encode(), f"{method}\n{path}\n{ts}\n{text}".encode(), hashlib.sha256).hexdigest()
    headers = {"X-Swarm-Node": "pc", "X-Swarm-Ts": ts, "X-Swarm-Sig": sig, "X-Swarm-Req": f"pc-{ts}-{random.randint(0, 99999)}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://{ip}:8787{path}", data=text.encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except Exception as e:
        return {"_err": f"{type(e).__name__}: {e}"}


NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def local_call(path, key, body=None, timeout=300):
    req = urllib.request.Request(f"http://127.0.0.1:{LOCAL}{path}", data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with NO_PROXY.open(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


class Server:
    def __init__(self, ip, native, extra, logpath):
        self.ip, self.native, self.extra, self.logpath = ip, native, extra, logpath
        self.key = secrets.token_urlsafe(24)
        self.pid = None
        self.lines = []

    def start(self, wait_s=300):
        args = (f"-m {MODEL} --host 127.0.0.1 --port {PORT} -ngl 99 -t 4 -c 8192 -np 2 -ctxcp 6 -cram 1024 --reasoning off --no-webui "
                f"--api-key {self.key} " + " ".join(self.extra))
        cmd = (f"echo $$; cd {self.native} && export LD_LIBRARY_PATH={self.native}:/vendor/lib64:/system/vendor/lib64 && "
               f"exec ./{BINARY} {args} 2>&1")
        self.proc = subprocess.Popen([ADB, "-s", f"{self.ip}:5555", "shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     stdin=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        log = open(self.logpath, "w", encoding="utf-8")

        def pump():
            for line in self.proc.stdout:
                line = line.rstrip("\r\n")
                if self.pid is None and line.strip().isdigit():
                    self.pid = int(line.strip())
                    continue
                log.write(line.replace(self.key, "***") + "\n")
                log.flush()
                self.lines.append(line)
            log.close()
        threading.Thread(target=pump, daemon=True).start()
        t0 = time.time()
        while time.time() - t0 < wait_s:
            if self.proc.poll() is not None:
                raise RuntimeError("server exited: " + " | ".join(self.lines[-6:]))
            try:
                with NO_PROXY.open(f"http://127.0.0.1:{LOCAL}/health", timeout=5) as r:
                    if r.status == 200:
                        return int(time.time() - t0)
            except Exception:
                pass
            time.sleep(2)
        raise RuntimeError("server not ready in time: " + " | ".join(self.lines[-6:]))

    def status(self):
        return " ".join(adb(self.ip, "shell", f"grep -E 'VmHWM|VmRSS' /proc/{self.pid}/status").split())

    def stop(self):
        if self.pid is None:
            return
        adb(self.ip, "shell", f"kill {self.pid}")
        for _ in range(15):
            if "alive" not in adb(self.ip, "shell", f"[ -d /proc/{self.pid} ] && echo alive"):
                break
            time.sleep(2)
        else:
            adb(self.ip, "shell", f"kill -9 {self.pid}")
        try:
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


def run_pass(ip, out, n, name, native, prompts):
    extra = VARIANTS[name]
    th0, waited = wait_thermal(ip)
    srv = Server(ip, native, extra, os.path.join(out, f"server-{n:02d}-{name}.log"))
    started = None
    try:
        started = srv.start()
        print(f"pass {n} {name}: thermal {th0} (waited {waited} s), server pid {srv.pid} ready in {started} s", flush=True)
        local_call("/v1/chat/completions", srv.key, {"model": "x", "max_tokens": 8, "temperature": 0.0, "messages": [{"role": "user", "content": "Say hi."}], "chat_template_kwargs": {"enable_thinking": False}})
        rows = []
        for p in prompts:
            t0 = time.time()
            try:
                r = local_call("/v1/chat/completions", srv.key, chat_body(p))
                t = r.get("timings", {})
                ch = r["choices"][0]
                rows.append({"id": p["id"], "group": p["group"], "wall_s": round(time.time() - t0, 2), "text": ch["message"].get("content") or "",
                             "finish": ch.get("finish_reason"), "prompt_n": t.get("prompt_n"), "cache_n": t.get("cache_n"),
                             "prompt_per_second": t.get("prompt_per_second"), "predicted_n": t.get("predicted_n"),
                             "predicted_per_second": t.get("predicted_per_second"), "draft_n": t.get("draft_n"), "draft_n_accepted": t.get("draft_n_accepted"),
                             "timings": t})
            except Exception as e:
                rows.append({"id": p["id"], "group": p["group"], "error": f"{type(e).__name__}: {e}"})
            x = rows[-1]
            print(f"  {x['id']:<5} " + (x["error"] if "error" in x else f"prompt {x['prompt_n']} (+{x['cache_n']} cached) decode {x['predicted_n']} tok at {x['predicted_per_second']:.2f} tok/s, drafted {x['draft_n']} accepted {x['draft_n_accepted']}, {x['wall_s']} s"), flush=True)
        mem = srv.status()
    finally:
        srv.stop()
    th1 = thermal(ip)
    res = {"pass": n, "name": name, "flags": extra, "thermal_before": th0, "waited_s": waited, "thermal_after": th1, "ready_s": started, "mem": mem, "rows": rows}
    with open(os.path.join(out, f"pass-{n:02d}-{name}.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print(f"pass {n} {name} done: thermal {th0} -> {th1}, {mem}", flush=True)


# ---------------------------------------------------------------------------------------------------------------- report
def report(out):
    passes = [json.load(open(f, encoding="utf-8")) for f in sorted(glob.glob(os.path.join(out, "pass-*.json")))]
    if not passes:
        print("no pass files")
        return
    first_base = next((p for p in passes if p["name"] == "base"), None)
    ref = {r["id"]: r.get("text") for r in first_base["rows"]} if first_base else {}
    for group in ("data-union", "choir", "plain"):
        print(f"\n### {group}\n")
        print("| Pass | Setting | Thermal before / after | Prompts | Decode tok/s median (min-max) | Tokens drafted | Accepted | Same text as first baseline |")
        print("|---|---|---|---|---|---|---|---|")
        for p in passes:
            rows = [r for r in p["rows"] if r["group"] == group]
            ok = [r for r in rows if "error" not in r and r.get("predicted_per_second")]
            speeds = [r["predicted_per_second"] for r in ok]
            dr = sum(r.get("draft_n") or 0 for r in ok)
            ac = sum(r.get("draft_n_accepted") or 0 for r in ok)
            same = sum(1 for r in ok if ref.get(r["id"]) == r["text"])
            reported = any(r.get("draft_n") is not None for r in ok)  # the server leaves draft_n out of timings when it drafted nothing
            share = f"{ac} ({100 * ac / dr:.0f} %)" if dr else "none"
            dr = dr if reported else "none"
            flags = " ".join(p["flags"]) or "no speculation"
            med = f"{statistics.median(speeds):.2f} ({min(speeds):.2f}-{max(speeds):.2f})" if speeds else "blank"
            print(f"| {p['pass']} | {p['name']}: {flags} | {p['thermal_before']} / {p['thermal_after']} | {len(ok)} of {len(rows)} | {med} | {dr} | {share} | {same} of {len(ok)} |")
    print("\n### per prompt, decode tok/s by pass (drafted/accepted in brackets)\n")
    ids = [r["id"] for r in passes[0]["rows"]]
    print("| Prompt | " + " | ".join(f"{p['pass']} {p['name']}" for p in passes) + " |")
    print("|---|" + "---|" * len(passes))
    for i in ids:
        cells = []
        for p in passes:
            r = next((x for x in p["rows"] if x["id"] == i), None)
            if not r or "error" in r:
                cells.append("blank")
            else:
                dn = f" (drafted {r['draft_n']}, accepted {r.get('draft_n_accepted')})" if r.get("draft_n") is not None else ""
                cells.append(f"{r['predicted_per_second']:.2f}{dn} {'same' if ref.get(i) == r['text'] else 'DIFFERENT'}")
        print(f"| {i} | " + " | ".join(cells) + " |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ip")
    ap.add_argument("out")
    ap.add_argument("passes", nargs="*")
    ap.add_argument("--first", type=int, default=1)
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    if a.report:
        report(a.out)
        return
    if a.ip != ONLY_PHONE:
        sys.exit(f"refused: this probe runs on Phone B ({ONLY_PHONE}) only")
    for name in a.passes:
        if name not in VARIANTS:
            sys.exit(f"unknown pass {name}; known: {', '.join(VARIANTS)}")
    prompts = build_prompts()
    with open(os.path.join(a.out, "prompts.json"), "w", encoding="utf-8") as f:
        json.dump(prompts, f, ensure_ascii=False, indent=1)
    apk = re.search(r"package:(\S+)", adb(a.ip, "shell", "pm", "path", PKG)).group(1)
    native = os.path.dirname(apk) + "/lib/arm64"
    print("binary sha256 on the phone:", adb(a.ip, "shell", f"sha256sum {native}/{BINARY}").split()[0], flush=True)
    print("stop app chat server:", str(signed(a.ip, "POST", "/v1/models/stop", {"role": "chat"}))[:160], flush=True)
    time.sleep(4)
    print("forward:", adb(a.ip, "forward", f"tcp:{LOCAL}", f"tcp:{PORT}").strip(), flush=True)
    try:
        for i, name in enumerate(a.passes):
            run_pass(a.ip, a.out, a.first + i, name, native, prompts)
    finally:
        print("remove forward:", adb(a.ip, "forward", "--remove", f"tcp:{LOCAL}").strip(), flush=True)


if __name__ == "__main__":
    main()
