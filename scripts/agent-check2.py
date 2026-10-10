#!/usr/bin/env python3
"""Acceptance checks of agent endpoint version 2 (plans/2026-10-10-agent-tools-files.md): tool calling and file search.

Usage: python scripts/agent-check2.py <adb-serial> <key> <out-dir> <steps>
Agent access (and, for the file steps, file search) must already be on with <key> on that phone.
steps: t tool selection on 12 prompts; u tool_choice and a two-step conversation; v streamed tool call through the OpenAI
client; w file search on this phone and across the swarm (run on Phone C of the demo corpus); p private-file check (run on
Phone B, which holds a salary slip in a Private folder); x the whole loop: the model calls search_files, this script runs
the search, the model answers; o file search switched off answers 403 (run before the switch is turned on).
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL, KEY, OUT, STEPS = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
PORT = int(os.environ.get("AGENT_PORT", "8790"))
LPORT = int(os.environ.get("AGENT_LOCAL_PORT", str(PORT)))  # a second phone can be forwarded to another local port
BASE = f"http://127.0.0.1:{LPORT}"
os.makedirs(OUT, exist_ok=True)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # answers contain Devanagari; a Windows console defaults to cp1252

WEATHER = {"type": "function", "function": {"name": "get_weather", "description": "Current weather for a city.",
           "parameters": {"type": "object", "properties": {"city": {"type": "string"}, "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]}},
                          "required": ["city"]}}}
ALARM = {"type": "function", "function": {"name": "set_alarm", "description": "Set an alarm on the phone.",
         "parameters": {"type": "object", "properties": {"time": {"type": "string", "description": "24-hour time, HH:MM"}, "label": {"type": "string"}},
                        "required": ["time"]}}}
SEARCH = {"type": "function", "function": {"name": "search_files", "description": "Search the user's own files (bills, notes, photos of documents) and return short snippets.",
          "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}}
TOOLS = [WEATHER, ALARM, SEARCH]

# (prompt, expected tool or None, argument name, substrings of which one must appear in that argument or in the answer)
CASES = [
    ("What's the weather in Bengaluru right now?", "get_weather", "city", ["bengaluru", "bangalore"]),
    ("Is it raining in Mumbai?", "get_weather", "city", ["mumbai"]),
    ("How hot is it in Delhi, in fahrenheit?", "get_weather", "city", ["delhi"]),
    ("Wake me up at 6:30 tomorrow morning.", "set_alarm", "time", ["06:30", "6:30"]),
    ("Set an alarm for 21:15 called medicine.", "set_alarm", "time", ["21:15"]),
    ("Find my laptop warranty bill.", "search_files", "query", ["warranty"]),
    ("Where did I save the electricity bill?", "search_files", "query", ["electricity"]),
    ("Look in my files for the yoga class schedule.", "search_files", "query", ["yoga"]),
    ("What is 12 times 12?", None, None, ["144"]),
    ("Say hello in Hindi.", None, None, ["नमस्ते", "namaste"]),
    ("What is the capital of France?", None, None, ["paris"]),
    ("Write a two-line poem about the sea.", None, None, [""]),
]


def adb(*args, timeout=120):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    return (p.stdout + p.stderr).replace("\r", "").strip()


def thermal():
    for line in adb("shell", "dumpsys", "thermalservice").splitlines():
        if "Thermal Status" in line:
            return line.strip()
    return "?"


def save(name, obj):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False, indent=1))


def call(path, body=None, timeout=240):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 # the endpoint refuses a Host other than 127.0.0.1:<its port>, so name that port even when adb forwards another one
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}", "Host": f"127.0.0.1:{PORT}"},
                                 method="GET" if body is None else "POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, status = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode("utf-8", "replace"), e.code
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}", time.time() - t0
    try:
        return status, json.loads(raw), time.time() - t0
    except ValueError:
        return status, raw, time.time() - t0


def chat(messages, tools=None, max_tokens=200, **extra):
    body = {"model": extra.pop("model", "ustaad-local"), "messages": messages, "max_tokens": max_tokens, "temperature": 0, **extra}
    if tools is not None:
        body["tools"] = tools
    return call("/v1/chat/completions", body)


def first_message(b):
    return b["choices"][0]["message"], b["choices"][0].get("finish_reason")


def calls_of(msg):
    out = []
    for c in msg.get("tool_calls") or []:
        try:
            args = json.loads(c["function"]["arguments"])
        except Exception as e:
            args = {"__unparsed__": f"{type(e).__name__}: {c['function'].get('arguments')!r}"}
        out.append((c["function"]["name"], args, c.get("id")))
    return out


def show(tag, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {tag}  {detail}", flush=True)


def keys_in(obj, found=None):
    found = set() if found is None else found
    if isinstance(obj, dict):
        for k, v in obj.items():
            found.add(k)
            keys_in(v, found)
    elif isinstance(obj, list):
        for v in obj:
            keys_in(v, found)
    return found


print(f"INFO  phone {SERIAL}  {thermal()}  forward: {adb('forward', f'tcp:{LPORT}', f'tcp:{PORT}')}", flush=True)

if "o" in STEPS:
    s, b, _ = call("/v1/files/search", {"query": "electricity bill"})
    save("o-files-off.json", [s, b])
    show("o-files-off", s == 403, f"file search switched off: HTTP {s} {b.get('error', {}).get('code') if isinstance(b, dict) else b}")

if "t" in STEPS:
    rows, right_tool, right_arg, direct, parsed_ok, secs = [], 0, 0, 0, 0, []
    for prompt, want, arg, subs in CASES:
        s, b, dt = chat([{"role": "user", "content": prompt}], TOOLS)
        secs.append(dt)
        if s != 200 or not isinstance(b, dict):
            rows.append(dict(prompt=prompt, want=want, status=s, got=str(b)[:200]))
            continue
        msg, fin = first_message(b)
        cs = calls_of(msg)
        unparsed = any("__unparsed__" in a for _, a, _ in cs)
        if cs and not unparsed:
            parsed_ok += 1
        got = cs[0][0] if cs else None
        val = str(cs[0][1].get(arg, "")).lower() if cs and arg else (msg.get("content") or "").lower()
        tool_ok = got == want
        arg_ok = tool_ok and any(x in val for x in subs)
        if want is None:
            direct += int(got is None)
        else:
            right_tool += int(tool_ok)
            right_arg += int(arg_ok)
        rows.append(dict(prompt=prompt, want=want, got=got, args=cs[0][1] if cs else None, content=msg.get("content"), finish=fin, seconds=round(dt, 2),
                         tool_ok=tool_ok, arg_ok=arg_ok, usage=b.get("usage")))
        print(f"INFO  t  want {want}  got {got}  {json.dumps(cs[0][1], ensure_ascii=False) if cs else repr((msg.get('content') or '')[:70])}  {dt:.1f} s", flush=True)
    save("t-tool-selection.json", rows)
    n_tool = sum(1 for c in CASES if c[1])
    show("t-tool-selection", True, f"right tool {right_tool} of {n_tool}; right key argument {right_arg} of {n_tool}; answered directly {direct} of {len(CASES) - n_tool}; "
         f"tool calls with arguments that parse {parsed_ok}; mean {sum(secs) / len(secs):.1f} s per request; {thermal()}")

if "u" in STEPS:
    out = {}
    s, b, _ = chat([{"role": "user", "content": "What is the capital of France?"}], TOOLS, tool_choice="required")
    cs = calls_of(first_message(b)[0]) if s == 200 else []
    out["required"] = [s, b]
    show("u-required", s == 200 and len(cs) >= 1, f"HTTP {s}, calls {[(n, a) for n, a, _ in cs]}")
    s, b, _ = chat([{"role": "user", "content": "What's the weather in Bengaluru right now?"}], TOOLS, tool_choice="none")
    m = first_message(b)[0] if s == 200 else {}
    out["none"] = [s, b]
    show("u-none", s == 200 and not m.get("tool_calls") and bool(m.get("content")), f"HTTP {s}, content {(m.get('content') or '')[:80]!r}")
    s, b, _ = chat([{"role": "user", "content": "Remind me at seven in the evening."}], TOOLS, tool_choice={"type": "function", "function": {"name": "set_alarm"}})
    cs = calls_of(first_message(b)[0]) if s == 200 else []
    out["named"] = [s, b]
    show("u-named", s == 200 and bool(cs) and all(n == "set_alarm" for n, _, _ in cs), f"HTTP {s}, calls {[(n, a) for n, a, _ in cs]}")
    s, b, _ = chat([{"role": "user", "content": "hi"}], TOOLS, tool_choice={"type": "function", "function": {"name": "nope"}})
    out["unknown_named"] = [s, b]
    s2, b2, _ = chat([{"role": "user", "content": "hi"}], TOOLS + [WEATHER])
    out["duplicate"] = [s2, b2]
    s3, b3, _ = call("/v1/chat/completions", {"model": "ustaad-local", "messages": [{"role": "user", "content": "hi"}], "tools": TOOLS,
                                              "response_format": {"type": "json_schema", "json_schema": {"name": "x", "schema": {"type": "object"}}}})
    out["with_response_format"] = [s3, b3]
    show("u-refusals", s == 400 and s2 == 400 and s3 == 400, f"unknown named tool {s}, duplicate names {s2}, tools with response_format {s3}")
    # two steps: call, result, answer
    msgs = [{"role": "user", "content": "What's the weather in Bengaluru right now?"}]
    t0 = time.time()
    s, b, _ = chat(msgs, [WEATHER])
    m, fin = first_message(b) if s == 200 else ({}, None)
    cs = calls_of(m)
    final, s2 = None, None
    if cs:
        msgs.append({"role": "assistant", "content": None, "tool_calls": m["tool_calls"]})
        msgs.append({"role": "tool", "tool_call_id": cs[0][2], "content": json.dumps({"city": "Bengaluru", "temp_c": 24, "condition": "light rain"})})
        s2, b2, _ = chat(msgs, [WEATHER])
        final = first_message(b2)[0].get("content") if s2 == 200 else str(b2)[:200]
        out["two_step_final"] = [s2, b2]
    out["two_step_first"] = [s, b]
    save("u-tool-choice.json", out)
    ok = bool(cs) and s2 == 200 and final and ("24" in final or "rain" in final.lower())
    show("u-two-step", ok, f"first: {fin}, call {[(n, a) for n, a, _ in cs]}; final after the tool result: {final!r}; {time.time() - t0:.1f} s for both calls")

if "v" in STEPS:
    try:
        from openai import OpenAI
        c = OpenAI(base_url=BASE + "/v1", api_key=KEY, timeout=240, max_retries=0)
        name, args, n, fin, t0 = "", "", 0, None, time.time()
        for ev in c.chat.completions.create(model="ustaad-local", messages=[{"role": "user", "content": "Set an alarm for 21:15 called medicine."}],
                                            tools=TOOLS, max_tokens=200, temperature=0, stream=True):
            n += 1
            if not ev.choices:
                continue
            fin = ev.choices[0].finish_reason or fin
            for tc in ev.choices[0].delta.tool_calls or []:
                name += (tc.function.name or "") if tc.function else ""
                args += (tc.function.arguments or "") if tc.function else ""
        r = c.chat.completions.create(model="ustaad-local", messages=[{"role": "user", "content": "Is it raining in Mumbai?"}], tools=TOOLS, max_tokens=200, temperature=0)
        tc = r.choices[0].message.tool_calls
        save("v-openai-sdk-tools.json", {"stream": {"events": n, "name": name, "arguments": args, "finish": fin, "seconds": time.time() - t0},
                                         "plain": {"finish": r.choices[0].finish_reason, "calls": [(x.function.name, x.function.arguments) for x in tc or []]}})
        show("v-openai-sdk-tools", name == "set_alarm" and isinstance(json.loads(args), dict) and fin == "tool_calls" and bool(tc),
             f"streamed: {n} events, {name}({args}), finish {fin}; not streamed: {[(x.function.name, x.function.arguments) for x in tc or []]}, finish {r.choices[0].finish_reason}")
    except Exception as e:
        save("v-openai-sdk-tools.json", f"{type(e).__name__}: {e}")
        show("v-openai-sdk-tools", False, f"{type(e).__name__}: {e}")

BAD_KEYS = {"uriHash", "uri", "path", "withheldPrivate", "withheld", "embedding", "docId", "chunkId"}


def search(query, scope, n=5):
    s, b, dt = call("/v1/files/search", {"query": query, "scope": scope, "max_results": n})
    rows = [(d.get("phone"), d.get("title")) for d in b.get("data", [])] if isinstance(b, dict) else []
    return s, b, dt, rows


if "w" in STEPS:
    out = {}
    s, b, dt, rows = search("When is my electricity bill due?", "phone")
    out["phone_electricity"] = [s, b]
    show("w-phone", s == 200 and any("Electricity" in (t or "") for _, t in rows), f"scope phone, {dt:.2f} s: {rows}")
    s2, b2, dt2, rows2 = search("warranty bill for my laptop", "swarm")
    out["swarm_warranty"] = [s2, b2]
    show("w-swarm", s2 == 200 and any(t == "IMG_2231.jpg" for _, t in rows2) and any("Warranty_Certificate" in (t or "") for _, t in rows2),
         f"scope swarm, {dt2:.2f} s: {rows2}; phones {b2.get('phones') if isinstance(b2, dict) else b2}; incomplete {b2.get('incomplete') if isinstance(b2, dict) else None}")
    s3, b3, dt3, rows3 = search("What is my salary for September 2026?", "swarm", 10)
    out["swarm_salary"] = [s3, b3]
    leak = [r for r in rows3 if "salary" in (r[1] or "").lower() or "private" in (r[1] or "").lower()]
    show("w-swarm-private", s3 == 200 and not leak, f"salary question across the swarm, {dt3:.2f} s: {rows3}")
    bad = sorted((keys_in(b) | keys_in(b2) | keys_in(b3)) & BAD_KEYS)
    longest = max([len(d.get("snippet") or "") for x in (b, b2, b3) if isinstance(x, dict) for d in x.get("data", [])] or [0])
    show("w-shape", not bad and longest <= 400, f"keys present {sorted(keys_in(b2))}; forbidden keys found {bad}; longest snippet {longest} characters")
    out["limits"] = [call("/v1/files/search", {"query": ""})[:2], call("/v1/files/search", {"query": "x" * 1001})[:2],
                     call("/v1/files/search", {"query": "bill", "scope": "internet"})[:2], call("/v1/files/search", {"query": "bill", "max_results": 50})[:2]]
    show("w-limits", all(x[0] == 400 for x in out["limits"][:3]), f"empty query {out['limits'][0][0]}, 1001 characters {out['limits'][1][0]}, unknown scope {out['limits'][2][0]}, max_results 50 {out['limits'][3][0]}")
    save("w-files.json", out)

if "p" in STEPS:  # on the phone that owns the private file
    s, b, dt, rows = search("What is my salary for September 2026?", "phone", 10)
    s2, b2, dt2, rows2 = search("salary slip", "phone", 10)
    leak = [r for r in rows + rows2 if "salary" in (r[1] or "").lower() or "private" in (r[1] or "").lower()]
    bad = sorted((keys_in(b) | keys_in(b2)) & BAD_KEYS)
    save("p-private-owner-phone.json", {"salary_question": [s, b], "salary_slip": [s2, b2]})
    show("p-private-owner-phone", s == 200 and s2 == 200 and not leak and not bad, f"on the phone that holds the private salary slip: {rows} and {rows2}; forbidden keys {bad}")

if "x" in STEPS:
    SYS = ("You answer questions about the user's own documents. Use the search_files tool to look, "
           "then answer in one or two sentences and name the file and the phone.")
    # (question, substrings of which one must be in the final answer)
    LOOPS = [("Where is the warranty bill for my laptop?", ["IMG_2231", "Phone B"]),
             ("When is my electricity bill due and how much is it?", ["24-Sep", "24 Sep", "September 24", "2,410", "2410"]),
             ("When is the yoga class?", ["6:30", "Monday", "7:00"])]
    all_steps, good = {}, 0
    for n, (question, want) in enumerate(LOOPS, 1):
        msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": question}]
        t0 = time.time()
        s, b, d1 = chat(msgs, [SEARCH])
        m, fin = first_message(b) if s == 200 and isinstance(b, dict) else ({}, None)
        cs = calls_of(m)
        steps = {"first": [s, b]}
        final, rows, d2, d3 = None, [], None, None
        if cs and cs[0][0] == "search_files":
            s2, b2, d2, rows = search(str(cs[0][1].get("query", "")), "swarm")
            steps["search"] = [s2, b2]
            msgs.append({"role": "assistant", "content": None, "tool_calls": m["tool_calls"]})
            msgs.append({"role": "tool", "tool_call_id": cs[0][2], "content": json.dumps(b2.get("data", []) if isinstance(b2, dict) else [], ensure_ascii=False)})
            s3, b3, d3 = chat(msgs, [SEARCH], max_tokens=200)
            steps["final"] = [s3, b3]
            if s3 == 200 and isinstance(b3, dict):
                fm, _ = first_message(b3)
                final = fm.get("content") or f"(tool call again: {calls_of(fm)})"
        else:
            final = None if not m else f"(no tool call; the model said: {(m.get('content') or '')[:120]})"
        ok = bool(final) and bool(cs) and any(w in final for w in want)
        good += int(ok)
        all_steps[f"loop{n}"] = steps
        show(f"x-loop-{n}", ok, f"{question!r}: search_files({cs[0][1] if cs else None}) in {d1:.1f} s; search gave {rows} in {d2 if d2 is None else round(d2, 2)} s; "
             f"answer in {d3 if d3 is None else round(d3, 1)} s: {final!r}; whole loop {time.time() - t0:.1f} s")
    save("x-loop.json", all_steps)
    show("x-loops", good == len(LOOPS), f"{good} of {len(LOOPS)} loops ended in an answer with the expected fact")

if "s" in STEPS:  # schemas the endpoint must refuse or map, never a 500
    big = {"type": "function", "function": {"name": "tag_items", "description": "Tag items.",
           "parameters": {"type": "object", "properties": {"tags": {"type": "array", "items": {"type": "string"}, "maxItems": 100000}}, "required": ["tags"]}}}
    ext = {"type": "function", "function": {"name": "ext_ref", "description": "x",
           "parameters": {"type": "object", "properties": {"a": {"$ref": "https://example.com/schema.json"}}}}}
    s1, b1, _ = chat([{"role": "user", "content": "Tag this."}], [big], tool_choice="required")
    s2, b2, _ = chat([{"role": "user", "content": "Do it."}], [ext], tool_choice="required")
    save("s-schemas.json", {"large_bound": [s1, b1], "external_ref": [s2, b2]})
    show("s-schemas", s1 == 400 and s2 == 400, f"maxItems 100000: HTTP {s1} {b1.get('error', {}).get('code') if isinstance(b1, dict) else b1}; "
         f"external $ref: HTTP {s2} {b2.get('error', {}).get('code') if isinstance(b2, dict) else b2}")

print(f"INFO  end  {thermal()}", flush=True)
