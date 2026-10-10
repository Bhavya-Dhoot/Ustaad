#!/usr/bin/env python3
"""Where does the fixed cost of a short prompt come from? Measures the chat server's prompt time against prompt length on one phone.

Usage: python scripts/prompt-cost-probe.py <phone-ip> <out-dir> [variants]
       python scripts/prompt-cost-probe.py <phone-ip> <out-dir> direct <label> [extra,server,args]   (trace mode, see direct_trace)
variants: comma list, default "default,ctxcp0,default2,cram0,both". Named: default (the app's own arguments), ctxcp0 (-ctxcp 0),
cram0 (-cram 0), both (-ctxcp 0 -cram 0), default2 (the same as default, to see drift). Any other name is written
name=arg:arg:... (for example ub128=-ub:128) and must pass the debug allow-list in llm/DebugLoad.kt.

For every variant: wait for thermal status <= 1 (at most 8 min), stop and load the chat model with the variant's extra arguments
(debug build, signed POST /v1/models/load), then through the app's signed POST /v1/sched/chat (the route the batch uses):
  1. fresh random-word prompts of 1, 30, 150 and 600 words (13, 53, 206 and 761 prompt tokens on Phone C), one unmeasured warm-up
     round, then 5 rounds in round-robin order; max 4 new tokens, temperature 0. promptMs is llama-server's timings.prompt_ms;
     promptTokens is timings.prompt_n (tokens evaluated, cache hits excluded). Fit promptMs = fixed + slope * promptTokens.
  2. exact repeat (cache reuse): the same prompt twice, at 30 and 150 words; cached tokens = cold promptTokens - repeat promptTokens.
  3. A, B, A with 150-word prompts (what the host cache is for): promptTokens of the second A.
  4. three fixed questions at temperature 0 (32 tokens) and one exact repeat of the first: the answers are saved to compare variants.
The server's own log (files/logs/chat.log, read with run-as) is saved next to the numbers. Thermal status is read over adb (<ip>:5555).
"""
import hashlib
import hmac
import json
import os
import random
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ip, OUT = sys.argv[1], sys.argv[2]
NAMES = (sys.argv[3] if len(sys.argv) > 3 else "default,ctxcp0,default2,cram0,both").split(",")
CHAT = "Qwen3.5-4B-Q4_K_M.gguf"
PKG = "com.swarmos.app"
KNOWN = {
    "default": [], "default2": [], "ctxcp0": ["-ctxcp", "0"], "cram0": ["-cram", "0"], "both": ["-ctxcp", "0", "-cram", "0"],
}
SIZES = [1, 30, 150, 600]  # content words per prompt; measured on Phone C: 13, 53, 206 and 761 prompt tokens (the chat template adds about 12)
ROUNDS = 5
FIXED_QUESTIONS = [
    "What is the capital of France? Answer in one short sentence.",
    "Name three primary colours.",
    "Write one sentence about the monsoon.",
]
WORDS = ("river stone window garden pencil market engine bridge candle forest harbor ladder mirror orchard pillow quartz rabbit saddle "
         "tunnel violin walnut yellow zebra anchor basket cobweb dragon ember feather glacier hammock island jungle kettle lantern "
         "meadow needle oyster paddle quiver rocket spiral thimble umbrella velvet wagon yarn zipper acorn blanket cactus dolphin "
         "eagle fiddle goblet helmet iceberg jacket kitten lemon magnet nutmeg olive parrot quilt ribbon sponge tomato unicorn "
         "valley whistle xylophone yogurt zeppelin apple button copper desert engine flute grape honey indigo jasmine koala "
         "lilac mango nickel orange pepper quarry radish silver tiger urchin violet willow yacht zinc amber bronze coral dune "
         "fern gravel hazel ivory jade kiwi lotus maple nectar opal plum quail rust sage teal umber vine wheat yew").split()
os.makedirs(OUT, exist_ok=True)


def call(method, path, body=None, timeout=300):
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


def adb(*args, timeout=60):
    p = subprocess.run([ADB, "-s", f"{ip}:5555", *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, encoding="utf-8", errors="replace")
    return p.stdout.replace("\r", "")


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip().split()[-1]
    return "?"


def wait_cool(max_s=480):
    t0, s = time.time(), thermal()
    while s not in ("0", "1") and time.time() - t0 < max_s:
        time.sleep(15)
        s = thermal()
    return s, round(time.time() - t0)


SEEN = set()


def prompt_of(words, seed):
    """Random words (about 1.2 tokens each); a prompt never repeats within one run, so nothing can be in a cache by accident."""
    for k in range(1000):
        rng = random.Random(f"{seed}-{k}")
        text = " ".join(rng.choice(WORDS) for _ in range(words))
        if text not in SEEN or seed.startswith(("repeat", "aba")):
            SEEN.add(text)
            return text
    raise RuntimeError("no fresh prompt")


def chat(prompt, max_tokens=4, tag=""):
    t0 = time.time()
    for attempt in range(5):
        r = call("POST", "/v1/sched/chat", {"jobId": f"pc-{int(time.time() * 1000)}-{random.randint(0, 9999)}",
                                           "request": {"messages": [{"role": "user", "content": prompt}], "maxTokens": max_tokens, "temperature": 0}})
        if r.get("_status") == 429:
            time.sleep(1.5)
            continue
        break
    wall = (time.time() - t0) * 1000
    st = r.get("stats")
    if not st:
        return {"tag": tag, "error": str(r)[:200], "wallMs": round(wall, 1)}
    return {"tag": tag, "promptTokens": st["promptTokens"], "promptMs": round(st["promptMs"], 1), "genMs": round(st["genMs"], 1),
            "completionTokens": st["completionTokens"], "wallMs": round(wall, 1), "text": r.get("text", "")}


def fit(rows):
    xs, ys = [r["promptTokens"] for r in rows], [r["promptMs"] for r in rows]
    n, mx, my = len(xs), statistics.mean(xs), statistics.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx if sxx else 0.0
    inter = my - slope * mx
    ss_tot = sum((y - my) ** 2 for y in ys)
    ss_res = sum((y - (inter + slope * x)) ** 2 for x, y in zip(xs, ys))
    return {"n": n, "fixedMs": round(inter, 1), "msPerToken": round(slope, 3), "tokPerSec": round(1000 / slope, 1) if slope > 0 else None,
            "r2": round(1 - ss_res / ss_tot, 4) if ss_tot else None}


def load_variant(args):
    adb_state = call("GET", "/v1/models/state").get("roles", {}).get("chat", {})
    call("POST", "/v1/models/stop", {"role": "chat"})
    time.sleep(3)
    t0 = time.time()
    resp = call("POST", "/v1/models/load", {"role": "chat", "file": CHAT, "args": args})
    if "_status" in resp or "_err" in resp:
        return None, resp, adb_state
    r = {}
    while time.time() - t0 < 240:
        r = call("GET", "/v1/models/state").get("roles", {}).get("chat", {})
        if r.get("state") in ("READY", "FAILED"):
            break
        time.sleep(0.5)
    return r, resp, adb_state


def run_variant(name, args):
    SEEN.clear()  # every variant gets the same prompts (a reload empties the server's cache)
    th0, waited = wait_cool()
    role, resp, _ = load_variant(args)
    if not role or role.get("state") != "READY":
        return {"name": name, "args": args, "error": f"load: {resp} {role}", "thermalBefore": th0}
    out = {"name": name, "args": args, "thermalBefore": th0, "thermalWaitedS": waited, "backend": role.get("backend"), "loadMs": role.get("loadMs"),
           "serverArgs": role.get("args"), "rssMbAfterLoad": role.get("rssMb")}
    rows = []
    for rep in range(-1, ROUNDS):  # -1 = unmeasured warm-up round
        for w in SIZES:
            r = chat(prompt_of(w, f"size{w}-rep{rep}"), tag=f"fresh-{w}")
            r.update(words=w, rep=rep, warmup=rep < 0)
            rows.append(r)
    out["fresh"] = rows
    good = [r for r in rows if not r["warmup"] and "error" not in r]
    out["fit"] = fit(good) if len(good) > 3 else None
    out["bySize"] = {}
    for w in SIZES:
        g = [r for r in good if r["words"] == w]
        if g:
            out["bySize"][w] = {"promptTokens": statistics.median(r["promptTokens"] for r in g), "promptMsMedian": round(statistics.median(r["promptMs"] for r in g), 1),
                                "promptMsMin": min(r["promptMs"] for r in g), "promptMsMax": max(r["promptMs"] for r in g),
                                "wallMinusServerMsMedian": round(statistics.median(r["wallMs"] - r["promptMs"] - r["genMs"] for r in g), 1),
                                "genMsMedian": round(statistics.median(r["genMs"] for r in g), 1)}
    rep_rows = []
    for w in (30, 150):  # exact repeat
        p = prompt_of(w, f"repeat{w}")
        for k in (1, 2):
            r = chat(p, tag=f"repeat-{w}-{k}")
            rep_rows.append(r)
    A, B = prompt_of(150, "abaA"), prompt_of(150, "abaB")
    for tag, p in (("aba-A1", A), ("aba-B", B), ("aba-A2", A)):
        rep_rows.append(chat(p, tag=tag))
    out["reuse"] = rep_rows
    ans = []
    for i, q in enumerate(FIXED_QUESTIONS):
        ans.append(chat(q, max_tokens=32, tag=f"answer-{i}"))
    ans.append(chat(FIXED_QUESTIONS[0], max_tokens=32, tag="answer-0-repeat"))
    out["answers"] = ans
    out["thermalAfter"] = thermal()
    log = adb("shell", "run-as", PKG, "cat", "files/logs/chat.log", timeout=120)
    with open(os.path.join(OUT, f"{name}.chat.log"), "w", encoding="utf-8") as f:
        f.write(log)
    out["logWarnings"] = {"makingRoom": log.count("making room for prompt cache entry"), "lines": len(log.splitlines())}
    return out


def line(o):
    if "error" in o:
        return f"{o['name']}: ERROR {o['error']}"
    f = o["fit"] or {}
    reuse = {r["tag"]: (r.get("promptTokens"), r.get("promptMs")) for r in o["reuse"]}
    return (f"{o['name']:9s} args={o['args']} thermal {o['thermalBefore']}->{o['thermalAfter']} fixed={f.get('fixedMs')} ms slope={f.get('msPerToken')} ms/tok "
            f"({f.get('tokPerSec')} tok/s) r2={f.get('r2')} | repeat30 {reuse.get('repeat-30-1')}->{reuse.get('repeat-30-2')} "
            f"repeat150 {reuse.get('repeat-150-1')}->{reuse.get('repeat-150-2')} ABA {reuse.get('aba-A1')},{reuse.get('aba-B')},{reuse.get('aba-A2')}")


def direct_trace():
    """Mode `direct`: stops the app's chat server, starts the same GPU llama-server by hand as the adb shell user with the
    app's arguments plus -lv 5 (trace and debug lines with microsecond stamps), sends fresh/repeat requests straight to it through
    `adb forward` (so the answer carries timings.cache_n), and saves the server log. Leaves the phone with chat stopped: reload it after."""
    import urllib.parse
    native = adb("shell", "pm", "path", PKG).strip().splitlines()[0].replace("package:", "").rsplit("/", 1)[0] + "/lib/arm64"
    model = f"/storage/emulated/0/Android/data/{PKG}/files/models/{CHAT}"
    key, port = "probekey", 18080
    flags = ["-ngl", "99", "-t", "4", "-c", "8192", "-np", "2", "-ctxcp", "6", "-cram", "1024", "--reasoning", "off", "--no-webui",
             "--api-key", key, "-lv", "5"] + [a for a in (sys.argv[5].split(",") if len(sys.argv) > 5 else []) if a]
    label = sys.argv[4] if len(sys.argv) > 4 else "direct"
    call("POST", "/v1/models/stop", {"role": "chat"})
    time.sleep(3)
    adb("forward", f"tcp:{port}", f"tcp:{port}")
    # Runs as the adb shell user, not run-as: run-as cannot read the model in the app's external folder. linker64 starts the binary
    # because the install path contains "==", which `env` would take for a VAR=value assignment. No kernel cache (the shell user cannot
    # write the app's), so this server compiles the OpenCL programs at start; nothing is written on the phone.
    cmd = [ADB, "-s", f"{ip}:5555", "shell", "env", f"LD_LIBRARY_PATH={native}:/vendor/lib64:/system/vendor/lib64",
           "/system/bin/linker64", f"{native}/libllama-server-gpu.so", "-m", model, "--host", "127.0.0.1", "--port", str(port)] + flags
    logpath = os.path.join(OUT, f"direct-{label}.log")
    logf = open(logpath, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    t0, ok = time.time(), False
    while time.time() - t0 < 180 and proc.poll() is None:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3) as r:
                ok = r.status == 200
        except Exception:
            ok = False
        if ok:
            break
        time.sleep(1)
    print(f"direct server up={ok} after {time.time() - t0:.1f} s, thermal {thermal()}", flush=True)
    results = []

    def ask(tag, text, cache_prompt=True):
        body = {"messages": [{"role": "user", "content": text}], "max_tokens": 4, "temperature": 0, "cache_prompt": cache_prompt,
                "chat_template_kwargs": {"enable_thinking": False}}
        req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        t1 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                j = json.loads(r.read().decode("utf-8", "replace"))
        except Exception as e:
            results.append({"tag": tag, "error": str(e)})
            return
        t = j.get("timings", {})
        results.append({"tag": tag, "wallMs": round((time.time() - t1) * 1000, 1), "cache_n": t.get("cache_n"), "prompt_n": t.get("prompt_n"),
                        "prompt_ms": round(t.get("prompt_ms", 0), 1), "predicted_ms": round(t.get("predicted_ms", 0), 1),
                        "text": j["choices"][0]["message"]["content"]})
        print(results[-1], flush=True)

    if ok:
        B, C = prompt_of(30, "dB"), prompt_of(150, "dC")
        E, F, G = prompt_of(150, "dE"), prompt_of(150, "dF"), prompt_of(150, "dG")
        for tag, text in (("warmup-hi", "Hi"), ("B30-fresh", B), ("B30-repeat", B), ("C150-fresh", C), ("C150-repeat", C),
                          ("E150-fresh", E), ("F150-fresh", F), ("G150-fresh", G), ("E150-after-F-and-G", E)):
            ask(tag, text)
            time.sleep(1)
        for k in range(6):  # request field cache_prompt on and off, fresh 30-word prompts, alternating
            ask(f"X{k}-cache_prompt-{'on' if k % 2 else 'off'}", prompt_of(30, f"dX{k}"), cache_prompt=bool(k % 2))
            time.sleep(1)
    pid = adb("shell", "pidof", "libllama-server-gpu.so").strip()
    if pid:
        adb("shell", "kill", "-TERM", pid.split()[0])
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.terminate()
    logf.close()
    adb("forward", "--remove", f"tcp:{port}")
    with open(os.path.join(OUT, f"direct-{label}.json"), "w", encoding="utf-8") as f:
        json.dump({"cmd": cmd[8:], "results": results, "thermalAfter": thermal()}, f, ensure_ascii=False, indent=1)
    print("direct trace saved:", logpath, "; chat is stopped in the app: reload it", flush=True)


if NAMES == ["direct"]:
    direct_trace()
    sys.exit(0)

summary = []
for n in NAMES:
    if "=" in n:
        name, a = n.split("=", 1)
        args = a.split(":")
    else:
        name, args = n, KNOWN[n]
    print(f"== {name} {args}", flush=True)
    o = run_variant(name, args)
    with open(os.path.join(OUT, f"{name}.json"), "w", encoding="utf-8") as f:
        json.dump(o, f, ensure_ascii=False, indent=1)
    print(line(o), flush=True)
    summary.append(o)
with open(os.path.join(OUT, "summary-" + "+".join(NAMES).replace("=", "_").replace(":", "_") + ".json"), "w", encoding="utf-8") as f:
    json.dump([{k: v for k, v in o.items() if k not in ("fresh", "answers", "reuse")} for o in summary], f, ensure_ascii=False, indent=1)
