#!/usr/bin/env python3
"""Acceptance checks of the agent endpoint (plans/2026-10-10-agent-endpoint.md) on one phone.

Usage: python scripts/agent-check.py <adb-serial> <phone-wifi-ip> <key> <out-dir> [steps]
The endpoint must already be on with <key> (debug build: launch MainActivity with --ez agentAccess true --es agentKey <key>).
The PC reaches the phone's loopback through `adb forward`; one check runs curl on the phone itself (UID shell).
steps: letters from "abcdefghij" (default all). Every raw answer is written to <out-dir>; a line per check goes to stdout.
"""
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL, PHONE_IP, KEY, OUT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
STEPS = sys.argv[5] if len(sys.argv) > 5 else "abcdefghij"
PORT = int(os.environ.get("AGENT_PORT", "8790"))
BASE = f"http://127.0.0.1:{PORT}"
PKG = "com.swarmos.app"
PROMPT = "Explain how a heat pump works, in about 80 words."
os.makedirs(OUT, exist_ok=True)


def adb(*args, timeout=120):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return (p.stdout + p.stderr).replace("\r", "").strip()


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip()
    return "?"


def focus():
    for line in adb("shell", "dumpsys", "window").splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return "?"


def save(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False, indent=1))


def call(path, body=None, key=KEY, timeout=240):
    """Returns (status, parsed-or-text, seconds)."""
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers,
                                 method="GET" if body is None else "POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, status = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:  # connection refused, reset, timeout
        return 0, f"{type(e).__name__}: {e}", time.time() - t0
    dt = time.time() - t0
    try:
        return status, json.loads(raw), dt
    except ValueError:
        return status, raw, dt


def stream(body, timeout=240):
    """Returns dict(status, ttft, total, text, chunks, usage, fingerprint, done)."""
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=json.dumps({**body, "stream": True}).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"}, method="POST")
    t0, ttft, text, chunks, usage, fp, done = time.time(), None, "", 0, None, None, False
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status = r.status
            for line in r:
                line = line.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    done = True
                    break
                ev = json.loads(data)
                fp = ev.get("system_fingerprint", fp)
                usage = ev.get("usage") or usage
                for ch in ev.get("choices", []):
                    delta = (ch.get("delta") or {}).get("content") or ""
                    if delta:
                        chunks += 1
                        text += delta
                        if ttft is None:
                            ttft = time.time() - t0
    except urllib.error.HTTPError as e:
        return dict(status=e.code, error=e.read().decode("utf-8", "replace"))
    except Exception as e:
        return dict(status=0, error=f"{type(e).__name__}: {e}", ttft=ttft, text=text, chunks=chunks, total=time.time() - t0)
    return dict(status=status, ttft=ttft, total=time.time() - t0, text=text, chunks=chunks, usage=usage, fingerprint=fp, done=done)


def chat_body(model="ustaad-local", prompt=PROMPT, max_tokens=120):
    return {"model": model, "messages": [{"role": "user", "content": prompt}], "max_tokens": max_tokens, "temperature": 0}


def show(tag, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {tag}  {detail}", flush=True)


def rate(r):
    u = r.get("usage") or {}
    n = u.get("completion_tokens")
    if n and r.get("ttft") is not None and r["total"] > r["ttft"]:
        return f"{n} tokens, {n / (r['total'] - r['ttft']):.1f} tok/s after the first token"
    return f"{r.get('chunks')} chunks (no usage in the stream)"


print(f"INFO  phone {SERIAL}  {thermal()}  {focus()}", flush=True)
print("INFO  adb forward:", adb("forward", f"tcp:{PORT}", f"tcp:{PORT}"), flush=True)

if "a" in STEPS:  # criterion 1: key
    s0, b0, _ = call("/v1/models", key=None)
    s1, b1, _ = call("/v1/models", key="wrong-" + KEY)
    s2, b2, _ = call("/v1/models")
    save("a-models.json", {"nokey": [s0, b0], "wrongkey": [s1, b1], "key": [s2, b2]})
    ids = [m.get("id") for m in b2.get("data", [])] if isinstance(b2, dict) else []
    show("a-auth", s0 == 401 and s1 == 401 and s2 == 200, f"no key {s0}, wrong key {s1}, right key {s2}, models {ids}")

if "b" in STEPS:  # criterion 2: not reachable from the Wi-Fi
    try:
        socket.create_connection((PHONE_IP, PORT), timeout=4).close()
        lan = "CONNECTED"
    except Exception as e:
        lan = type(e).__name__
    show("b-lan", lan != "CONNECTED", f"TCP connect from the PC to {PHONE_IP}:{PORT}: {lan}")

if "c" in STEPS:  # criterion 3: another UID on the phone
    uid = adb("shell", "id", "-u")
    body = json.dumps(chat_body(prompt="Reply with the single word: ready", max_tokens=8)).replace('"', '\\"')
    out = adb("shell", f"curl -s -m 120 -w ' HTTP%{{http_code}}' -H 'Authorization: Bearer {KEY}' -H 'Content-Type: application/json' "
                       f"-d \"{body}\" http://127.0.0.1:{PORT}/v1/chat/completions", timeout=150)
    save("c-other-uid.txt", f"uid {uid}\n{out}")
    show("c-other-uid", out.endswith("HTTP200") and '"choices"' in out, f"curl on the phone as uid {uid}: ...{out[-160:]}")

if "d" in STEPS:  # criterion 4: non-streamed, streamed, schema, embeddings
    s, b, dt = call("/v1/chat/completions", chat_body())
    save("d-chat.json", b)
    ok = s == 200 and isinstance(b, dict) and b["choices"][0]["message"]["content"].strip() != ""
    show("d-chat", ok, f"HTTP {s}, {dt:.2f} s, usage {b.get('usage') if isinstance(b, dict) else b}, fingerprint {b.get('system_fingerprint') if isinstance(b, dict) else None}")
    r = stream(chat_body())
    save("d-stream.json", r)
    show("d-stream", r.get("status") == 200 and r.get("done") and r.get("text"), f"first token {r.get('ttft')}, total {r.get('total')}, {rate(r)}, {thermal()}")
    schema = {"type": "object", "properties": {"city": {"type": "string"}, "population_millions": {"type": "number"}},
              "required": ["city", "population_millions"]}
    s, b, dt = call("/v1/chat/completions", {**chat_body(prompt="Give the capital of Japan and its population in millions.", max_tokens=60),
                                              "response_format": {"type": "json_schema", "json_schema": {"name": "city", "schema": schema}}})
    save("d-schema.json", b)
    try:
        parsed = json.loads(b["choices"][0]["message"]["content"])
        ok = s == 200 and "city" in parsed and "population_millions" in parsed
    except Exception as e:
        parsed, ok = f"{type(e).__name__}: {e}", False
    show("d-schema", ok, f"HTTP {s}, {dt:.2f} s, parsed {parsed}")
    s, b, dt = call("/v1/embeddings", {"model": "ustaad-local", "input": ["warranty bill for a laptop", "yoga class schedule"]})
    save("d-embed.json", {"status": s, "dims": [len(x.get("embedding", [])) for x in b.get("data", [])] if isinstance(b, dict) else b})
    dims = [len(x.get("embedding", [])) for x in b.get("data", [])] if isinstance(b, dict) else []
    show("d-embed", s == 200 and len(dims) == 2 and dims[0] > 0, f"HTTP {s}, {dt:.2f} s, vectors {len(dims)}, dimensions {dims}")

if "e" in STEPS:  # criterion 5: swarm
    r = stream(chat_body(model="ustaad-swarm"))
    save("e-swarm.json", r)
    show("e-swarm", r.get("status") == 200 and r.get("text"), f"first token {r.get('ttft')}, total {r.get('total')}, {rate(r)}, ran on {r.get('fingerprint')}")
    s, b, dt = call("/v1/chat/completions", chat_body(model="ustaad-swarm"))
    save("e-swarm-plain.json", b)
    show("e-swarm-plain", s == 200, f"HTTP {s}, {dt:.2f} s, ran on {b.get('system_fingerprint') if isinstance(b, dict) else b}")

if "f" in STEPS:  # criterion 6: one model call at a time
    res = {}

    def one(tag):
        t0 = time.time()
        s, b, dt = call("/v1/chat/completions", chat_body(max_tokens=100))
        res[tag] = dict(status=s, start=t0, seconds=dt, tokens=(b.get("usage") or {}).get("completion_tokens") if isinstance(b, dict) else None)

    s, b, alone = call("/v1/chat/completions", chat_body(max_tokens=100))
    th = [threading.Thread(target=one, args=(t,)) for t in ("first", "second")]
    th[0].start(); time.sleep(0.3); th[1].start()
    [t.join() for t in th]
    save("f-two-at-once.json", {"alone_seconds": alone, **res})
    slow = max(res["first"]["seconds"], res["second"]["seconds"])
    show("f-two-at-once", all(v["status"] == 200 for v in res.values()),
         f"alone {alone:.2f} s; together {res['first']['seconds']:.2f} s and {res['second']['seconds']:.2f} s (slower one is {slow / alone:.2f} times a single call)")

if "g" in STEPS:  # refusals
    out = {}
    # version 2 accepts `tools`; the old `functions` field stays refused
    out["tools"] = call("/v1/chat/completions", {**chat_body(), "functions": [{"name": "x", "parameters": {"type": "object"}}]})[:2]
    out["unknown_model"] = call("/v1/chat/completions", chat_body(model="gpt-4o"))[:2]
    out["n2"] = call("/v1/chat/completions", {**chat_body(), "n": 2})[:2]
    out["image"] = call("/v1/chat/completions", {"model": "ustaad-local", "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}]}]})[:2]
    out["big"] = call("/v1/chat/completions", chat_body(prompt="x" * (1 << 20) + "y" * 4096))[:2]
    out["many"] = call("/v1/chat/completions", {"model": "ustaad-local", "messages": [{"role": "user", "content": "hi"}] * 65})[:2]
    save("g-refusals.json", out)
    codes = {k: v[0] for k, v in out.items()}
    show("g-refusals", codes["tools"] == 400 and codes["unknown_model"] == 404 and codes["n2"] == 400 and codes["image"] == 400
         and codes["big"] in (413, 0) and codes["many"] == 400, f"{codes}")

if "h" in STEPS:  # the real OpenAI client, unmodified
    try:
        from openai import OpenAI
        c = OpenAI(base_url=BASE + "/v1", api_key=KEY, timeout=240, max_retries=0)
        t0 = time.time()
        r = c.chat.completions.create(model="ustaad-local", messages=[{"role": "user", "content": "Name three uses of a phone with no internet."}], max_tokens=80)
        txt = r.choices[0].message.content
        t1, first, n = time.time(), None, 0
        for ev in c.chat.completions.create(model="ustaad-local", messages=[{"role": "user", "content": "Count from one to ten in words."}], max_tokens=60, stream=True):
            if ev.choices and ev.choices[0].delta.content:
                n += 1
                first = first or time.time() - t1
        emb = c.embeddings.create(model="ustaad-local", input="hello")
        save("h-openai-sdk.json", {"text": txt, "usage": r.usage.model_dump() if r.usage else None, "seconds": t1 - t0, "stream_first": first,
                                   "stream_chunks": n, "embedding_dims": len(emb.data[0].embedding), "models": [m.id for m in c.models.list().data]})
        show("h-openai-sdk", bool(txt) and n > 0, f"chat {t1 - t0:.2f} s, usage {r.usage.model_dump() if r.usage else None}; stream first token {first}, {n} chunks; embedding {len(emb.data[0].embedding)} dims")
    except Exception as e:
        save("h-openai-sdk.json", f"{type(e).__name__}: {e}")
        show("h-openai-sdk", False, f"{type(e).__name__}: {e}")

if "i" in STEPS:  # criterion 7: Ustaad not in front
    front = stream(chat_body())
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    rows = {"in_front": front, "focus_before": focus()}
    for wait in (5, 60, 180):
        time.sleep(wait if wait == 5 else wait - (5 if wait == 60 else 60))
        r = stream(chat_body(), timeout=200)
        rows[f"home_after_{wait}s"] = {**r, "focus": focus(), "thermal": thermal()}
        show(f"i-background-{wait}s", r.get("status") == 200 and bool(r.get("text")),
             f"first token {r.get('ttft')}, total {r.get('total')}, {rate(r) if r.get('status') == 200 else r.get('error')}  [in front: first token {front.get('ttft')}, total {front.get('total')}, {rate(front)}]")
    print("INFO  back to front:", adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")[:80], focus(), flush=True)
    save("i-background.json", rows)

if "j" in STEPS:  # criterion 1, second half: the switch closes the port
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "agentAccess", "false")
    time.sleep(4)
    s_off, b_off, _ = call("/v1/models", timeout=8)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "agentAccess", "true", "--es", "agentKey", KEY)
    time.sleep(4)
    s_on, _, _ = call("/v1/models", timeout=8)
    save("j-switch.json", {"off": [s_off, b_off], "on_again": s_on})
    show("j-switch", s_off == 0 and s_on == 200, f"switch off: {b_off if s_off == 0 else s_off}; on again: HTTP {s_on}")

print(f"INFO  end  {thermal()}  {focus()}", flush=True)
