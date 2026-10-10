#!/usr/bin/env python3
"""Helper for scripts/e2e-check.sh (PC side only, stdlib only). Reads the JSON the phones returned, makes the
PASS/FAIL decisions and writes the markdown tables that go into docs/benchmarks/2026-10-10-e2e-*.md.

Every number printed here is read from a response file; nothing is estimated.

  e2e.py get FILE EXPR [DEFAULT]        print EXPR evaluated with the parsed JSON as `d` ('' or DEFAULT when it fails)
  e2e.py ask-body KEY [K] [DEADLINE]    ASCII-only JSON body for POST /v1/pdu/ask or /v1/pdu/debug/scores
  e2e.py question KEY                   the question text
  e2e.py slices PROBLEMS_FILE N [SKIP]  eval slices, one comma-separated id list per line, kinds mixed in every slice (SKIP: ids to leave out)
  e2e.py done-ids DIR                    ids that already have an error-free row in DIR/eval-slice-*.json
  e2e.py models-assert FILE CHATFILE    PASS/FAIL line for GET /v1/models/state
  e2e.py peer-assert FILE NODEID        'online ...' when NODEID is listed online in GET /v1/link/peers
  e2e.py choir-assert FILE               PASS/FAIL line for a /v1/choir/run trace
  e2e.py ask-assert FILE KIND            PASS/FAIL line for a /v1/pdu/ask report (kind: relevant | none | any)
  e2e.py batch-assert FILE MODE          PASS/FAIL line for a finished /v1/sched/batch report
  e2e.py report-choir-run FILE           markdown tables for one choir run
  e2e.py report-eval DIR                 markdown tables for the merged eval slices in DIR
  e2e.py report-pdu DIR                  markdown tables for the Data Union responses in DIR
  e2e.py report-batch DIR                markdown tables for the throughput batches in DIR
"""
import glob
import json
import os
import statistics
import sys

QUESTIONS = {
    "warranty": "Where is the warranty bill for my laptop?",
    "unrelated": "What is the boiling point of liquid nitrogen?",
    # the same question in Hindi (cross-language retrieval; the documents are English)
    "hindi_warranty": "मेरे लैपटॉप का वारंटी बिल कहाँ है?",
    # about the Hindi note on phone C: "How many rupees must be paid to the milkman?"
    "hindi_note": "दूध वाले को कितने रुपये देने हैं?",
    "electricity": "When is my electricity bill due and how much is it?",
    "salary": "What is my salary for September 2026?",
}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def out(s=""):
    sys.stdout.write(s + "\n")


def try_load(path):
    try:
        return load(path)
    except Exception:
        return None


def scalar(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False)
    return str(v)


def cmd_get(file, expr, default=""):
    d = try_load(file)
    try:
        v = eval(expr, {"d": d, "__builtins__": {"len": len, "sum": sum, "min": min, "max": max, "round": round, "any": any, "all": all}})
    except Exception:
        out(default)
        return
    out(default if v is None else scalar(v))


def cmd_ask_body(key, k="5", deadline="1500"):
    out(json.dumps({"text": QUESTIONS[key], "k": int(k), "deadlineMs": int(deadline)}, ensure_ascii=True))


def cmd_done_ids(d):
    ids = []
    for f in glob.glob(os.path.join(d, "eval-slice-*.json")):
        rep = try_load(f)
        for r in (rep or {}).get("rows", []):
            if not r.get("error"):
                ids.append(r["problemId"])
    out(",".join(sorted(set(ids))))


def cmd_slices(problems_file, n, skip=""):
    skipped = set(x for x in skip.split(",") if x)
    probs = [p for p in load(problems_file) if p["id"] not in skipped]
    groups = {}
    for p in probs:
        groups.setdefault(p["group"], []).append(p["id"])
    order = [g for g in ("arith", "pct", "mcq", "code") if g in groups] + [g for g in groups if g not in ("arith", "pct", "mcq", "code")]
    ids = []
    while any(groups[g] for g in order):
        for g in order:
            if groups[g]:
                ids.append(groups[g].pop(0))
    n = int(n)
    for i in range(0, len(ids), n):
        out(",".join(ids[i:i + n]))


# ---------------------------------------------------------------------------------------------
# assertions
# ---------------------------------------------------------------------------------------------

def cmd_models_assert(file, chat_file):
    d = try_load(file)
    if not d:
        return out("FAIL no state response")
    roles = d.get("roles", {})
    chat, emb = roles.get("chat", {}), roles.get("embed", {})
    bad = []
    if chat.get("state") != "READY":
        bad.append(f"chat {chat.get('state')} ({chat.get('reason')})")
    elif (chat.get("model") or "").upper() == "FAKE" or not (chat.get("file") or "").endswith(".gguf"):
        bad.append(f"chat is not a real model ({chat.get('model')})")
    elif chat_file and chat.get("file") != chat_file:
        bad.append(f"chat file is {chat.get('file')}, expected {chat_file}")
    if emb.get("state") != "READY":
        bad.append(f"embed {emb.get('state')} ({emb.get('reason')})")
    elif not (emb.get("file") or "").endswith(".gguf"):
        bad.append(f"embed is not a real model ({emb.get('model')})")
    if bad:
        return out("FAIL " + "; ".join(bad))
    out(f"PASS chat={chat.get('file')} loadMs={chat.get('loadMs')} rssMb={chat.get('rssMb')}; embed={emb.get('file')}; thermal={d.get('thermalStatus')}")


def cmd_peer_assert(file, node_id):
    d = try_load(file)
    if not d:
        return out("none")
    for p in d.get("peers", []):
        if p.get("nodeId") == node_id and p.get("state") == "online":
            return out(f"online link={p.get('link')} rttMs={p.get('rttMs')} thermal={p.get('thermalStatus')}")
    out("none")


def lane_issues(t):
    bad = []
    lanes = t.get("lanes", [])
    if t.get("error"):
        bad.append(f"run error: {t['error']}")
    if t.get("phase") != "DONE":
        bad.append(f"phase {t.get('phase')}")
    if len(lanes) < 2:
        bad.append(f"{len(lanes)} lane(s)")
    for l in lanes:
        r1 = l.get("r1")
        if l.get("fake") or (l.get("model") or "").upper() in ("", "FAKE"):
            bad.append(f"{l.get('name')} model '{l.get('model')}' fake={l.get('fake')}")
        if not r1 or not (r1.get("answer") or "").strip():
            bad.append(f"{l.get('name')} gave no answer (status {l.get('status')}, error {l.get('error')})")
    if not t.get("finalPick") or t["finalPick"].get("answer") is None:
        bad.append("no final answer")
    if t.get("fake"):
        bad.append("trace is flagged fake")
    return bad


def cmd_choir_assert(file):
    t = try_load(file)
    if not t:
        return out("FAIL no trace")
    bad = lane_issues(t)
    if bad:
        return out("FAIL " + "; ".join(bad))
    f, tot = t["finalPick"], t.get("totals", {})
    out(f"PASS answer={f.get('answer')} method={f.get('method')} verified={scalar(f.get('verified'))} check={f.get('check')} basis={f.get('checkSource')} "
        f"rounds={tot.get('rounds')} tokens={tot.get('tokens')} wallMs={tot.get('wallMs')} known={t.get('knownAnswer')} "
        f"choirCorrect={scalar(t.get('choirCorrect'))} choirToolCorrect={scalar(t.get('choirToolCorrect'))}")


def cmd_ask_assert(file, kind):
    r = try_load(file)
    if not r:
        return out("FAIL no report")
    bad = []
    if (r.get("model") or "").upper() in ("", "FAKE"):
        bad.append(f"answer model is '{r.get('model')}'")
    if not (r.get("answer") or "").strip():
        bad.append("empty answer")
    if r.get("lexicalOnly"):
        bad.append("answered by keywords only (lexicalOnly), the embedder was not used")
    if kind == "relevant" and not r.get("sources"):
        bad.append("no sources")
    if kind == "none" and r.get("sources"):
        bad.append(f"{len(r['sources'])} source(s) shown for an unrelated question: " + ", ".join(f"{s['phone']}:{s['title']}" for s in r["sources"]))
    unreachable = [x["phone"] for x in r.get("devices", []) if x.get("status") not in ("ok", "OK") and not x.get("local")]
    if unreachable:
        bad.append("peer not ok: " + ", ".join(unreachable))
    if bad:
        return out("FAIL " + "; ".join(bad))
    t = r["timings"]
    out(f"PASS sources={len(r.get('sources', []))} ttftMs={t['ttftMs']} totalMs={t['totalMs']} incomplete={scalar(r.get('incomplete'))} withheld={r.get('withheldPrivate')}")


def cmd_batch_assert(file, mode):
    r = try_load(file)
    if not r:
        return out("FAIL no report")
    bad = []
    if r.get("state") != "done":
        bad.append(f"state {r.get('state')}")
    if r.get("failed"):
        bad.append(f"{r['failed']} failed item(s)")
    if r.get("fake"):
        bad.append("fake model")
    if r.get("done") != r.get("total"):
        bad.append(f"done {r.get('done')} of {r.get('total')}")
    shares = r.get("perPhone", [])
    if mode == "swarm" and len([s for s in shares if s.get("jobs", 0) > 0]) < 2:
        bad.append("fewer than two phones took jobs")
    if mode == "local" and len(shares) != 1:
        bad.append(f"{len(shares)} phones took jobs in local mode")
    if bad:
        return out("FAIL " + "; ".join(bad))
    out(f"PASS wallMs={r['wallMs']:.0f} tokens={r['totalTokens']} aggTokPerSec={r['aggTokPerSec']:.2f} phones={len(shares)}")


# ---------------------------------------------------------------------------------------------
# reports (markdown)
# ---------------------------------------------------------------------------------------------

def names_of(results_dir):
    m = {}
    for p in ("A", "C"):
        h = try_load(os.path.join(results_dir, f"hello-{p}.json"))
        if h:
            m[h["nodeId"]] = h["name"]
    return m


def row(*cells):
    return "| " + " | ".join(str(c) for c in cells) + " |"


def yn(v):
    return "-" if v is None else ("yes" if v else "no")


def cmd_report_choir_run(file):
    t = load(file)
    f = t.get("finalPick") or {}
    tot = t.get("totals", {})
    out("| Phone | Role | Model | Round | Answer | Confidence | Tokens | Phone ms | Wall ms | Tool check | Check basis |")
    out("|---|---|---|---|---|---|---|---|---|---|---|")
    for l in t.get("lanes", []):
        for rn, r in (("R1", l.get("r1")), ("R2", l.get("r2"))):
            if r:
                out(row(l["name"], l["role"], l.get("model"), rn, r["answer"], r["confidence"], r["tokens"], r["ms"], r["wallMs"], r["check"], r["checkSource"]))
    out()
    out("| Field | Value |")
    out("|---|---|")
    for k, v in (
        ("Problem id / known answer", f"{t.get('problemId')} / {t.get('knownAnswer')}"),
        ("Phase / agreement", f"{t.get('phase')} / {t.get('agreement')}"),
        ("Rounds", tot.get("rounds")), ("Tokens (all lanes)", tot.get("tokens")), ("Wall-clock ms", tot.get("wallMs")),
        ("Final answer", f.get("answer")), ("Pick method", f.get("method")), ("Verified by tool", yn(f.get("verified"))),
        ("Tool check of the final answer", f.get("check")), ("Check basis", f.get("checkSource")), ("Label", f.get("label")),
        ("Baseline lane (coordinator alone, R1)", f"{(t.get('baseline') or {}).get('answer')} in {(t.get('baseline') or {}).get('wallMs')} ms, {(t.get('baseline') or {}).get('tokens')} tokens"),
        ("Baseline correct / choir correct / choir+tool correct", f"{yn(t.get('baselineCorrect'))} / {yn(t.get('choirCorrect'))} / {yn(t.get('choirToolCorrect'))}"),
        ("Judge", (t.get("judge") or {}).get("reason") or "not needed"),
        ("Error", t.get("error") or "none"),
    ):
        out(row(k, v))


def summarize(rows):
    n = len(rows)
    sampled = [r for r in rows if r.get("samplesAnswered") is not None]

    def mean(xs):
        return sum(xs) / len(xs) if xs else 0.0

    c = lambda key: sum(1 for r in rows if r.get(key))
    return {
        "n": n, "baseline": c("baselineCorrect"), "choir": c("choirCorrect"), "tool": c("choirToolCorrect"),
        "verified": c("verified"), "byTest": c("verifiedByTest"), "consistent": c("consistent"),
        "abstained": sum(1 for r in rows if r.get("toolMethod") == "ABSTAIN"),
        "baselineMs": mean([r.get("baselineMs", 0) for r in rows]), "choirMs": mean([r.get("choirMs", 0) for r in rows]),
        "rounds": mean([r.get("rounds", 1) for r in rows]),
        "samplesN": len(sampled), "samples": sum(1 for r in sampled if r.get("samplesCorrect")),
        "samplesMs": mean([r.get("samplesMs", 0) for r in sampled]),
        "errors": sum(1 for r in rows if r.get("error")),
    }


def pct(a, n):
    return f"{100.0 * a / n:.0f} %" if n else "-"


def cmd_report_eval(d):
    rows, models, nodes, use_check = {}, set(), set(), set()
    for f in sorted(glob.glob(os.path.join(d, "eval-slice-*.json")), key=lambda p: int(os.path.basename(p).split("-")[2].split(".")[0])):
        rep = try_load(f)
        if not rep or "rows" not in rep:
            continue
        if rep.get("model"):
            models.add(rep["model"])
        nodes.update(rep.get("nodes", []))
        use_check.add(scalar(rep.get("useProblemCheck")))
        for r in rep["rows"]:
            rows[r["problemId"]] = r
    rows = list(rows.values())
    s = summarize(rows)
    out(f"Model: {', '.join(sorted(models)) or '(not in response)'}; nodes: {', '.join(sorted(nodes)) or '(not in response)'}; "
        f"check against the problem's test: {', '.join(sorted(use_check)) or '?'}; problems run: n = {s['n']} of 20; rows with an error: {s['errors']}.")
    out()
    out("| Arm | n | Correct | Accuracy | Mean wall-clock per problem (ms) |")
    out("|---|---|---|---|---|")
    out(row("One phone alone (coordinator's own first answer)", s["n"], s["baseline"], pct(s["baseline"], s["n"]), f"{s['baselineMs']:.0f}"))
    out(row("One phone, three samples, majority", s["samplesN"], s["samples"], pct(s["samples"], s["samplesN"]), f"{s['samplesMs']:.0f}"))
    out(row("Choir, two phones, no tool check", s["n"], s["choir"], pct(s["choir"], s["n"]), f"{s['choirMs']:.0f}"))
    out(row("Choir, two phones, with tool check", s["n"], s["tool"], pct(s["tool"], s["n"]), f"{s['choirMs']:.0f} (same run)"))
    out()
    out(f"Tool check as the app defines it: verified {s['verified']} of {s['n']} (verified against the problem's own test: {s['byTest']}; "
        f"only consistent with the models' written arithmetic: {s['consistent']}); abstained {s['abstained']}; mean rounds {s['rounds']:.2f}.")
    out()
    out("| Kind | n | One phone | Three samples | Choir | Choir + tool |")
    out("|---|---|---|---|---|---|")
    for kind in ("MATH", "MCQ", "CODE"):
        sub = [r for r in rows if r.get("kind") == kind]
        if sub:
            t = summarize(sub)
            out(row(kind, t["n"], t["baseline"], f"{t['samples']} of {t['samplesN']}", t["choir"], t["tool"]))
    out()
    out("| Slice | Problems | Thermal at start A / C | Peak thermal A / C | Wall (s) | HTTP | Rows | Note |")
    out("|---|---|---|---|---|---|---|---|")
    tsv = os.path.join(d, "slices.tsv")
    if os.path.exists(tsv):
        for line in open(tsv, encoding="utf-8"):
            out(row(*line.rstrip("\n").split("\t")))
    out()
    out("| Problem | Kind | Expected | One phone | 3 samples | Choir | Choir + tool | Verified | Rounds | Choir ms | Nodes answered | Error |")
    out("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda r: r["problemId"]):
        mark = lambda ans, ok: f"{ans} ({'ok' if ok else 'wrong'})" if ans is not None else "none (wrong)"
        ver = "test" if r.get("verifiedByTest") else ("consistent" if r.get("consistent") else "no")
        samp = mark(r.get("samplesAnswer"), r.get("samplesCorrect")) if r.get("samplesAnswered") is not None else "not run"
        out(row(r["problemId"], r["kind"], r["expected"], mark(r.get("baselineAnswer"), r.get("baselineCorrect")), samp,
                mark(r.get("choirAnswer"), r.get("choirCorrect")), mark(r.get("choirToolAnswer"), r.get("choirToolCorrect")), ver,
                r.get("rounds"), r.get("choirMs"), f"{r.get('nodesAnswered')}/{r.get('nodesTotal')}", r.get("error") or ""))


def cmd_report_pdu(d):
    names = names_of(d)
    out("Index runs")
    out()
    out("| Phone | Folder | Seen | Indexed | Chunks | OCR images | OCR ms avg | Embed ms | Total ms | Failed | Error |")
    out("|---|---|---|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(d, "pdu-index-*.json"))):
        r = try_load(f)
        parts = os.path.basename(f)[len("pdu-index-"):-5].split("-")
        if not r:
            continue
        out(row(parts[0], "-".join(parts[1:]), r.get("seen"), r.get("indexed"), r.get("chunks"), r.get("ocrCount"), r.get("ocrMsAvg"),
                r.get("embedMs"), r.get("totalMs"), r.get("failed"), r.get("error") or "; ".join(r.get("errors", [])[:3]) or ""))
    out()
    out("Store state after indexing")
    out()
    out("| Phone | State | Embed model in the store | Photos | Documents | Scans | No text | Chunks | Folders |")
    out("|---|---|---|---|---|---|---|---|---|")
    for p in ("A", "C"):
        for label, fn in (("as indexed", f"pdu-status-{p}-as-indexed.json"), ("after sharing", f"pdu-status-{p}.json")):
            s = try_load(os.path.join(d, fn))
            if s:
                folders = "; ".join(f"{x['folder']} ({x['docs']} docs, {'PRIVATE' if x['isPrivate'] else 'shared'})" for x in s.get("folders", []))
                out(row(f"Phone {p}", label, s.get("embedModel"), s.get("photos"), s.get("documents"), s.get("scans"), s.get("noText"), s.get("chunks"), folders))
    out()
    out("Questions asked from Phone A")
    for f in sorted(glob.glob(os.path.join(d, "pdu-ask-*.json"))):
        r = try_load(f)
        if not r:
            continue
        t = r["timings"]
        out()
        out(f"{os.path.basename(f)[:-5]}: {r['question']}")
        out()
        out("| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |")
        out("|---|---|---|---|---|---|---|---|---|")
        out(row(t["embedMs"], t["localSearchMs"], t["firstCardsMs"], t["ttftMs"], t["totalMs"], yn(r.get("incomplete")), yn(r.get("lexicalOnly")), r.get("withheldPrivate"), r.get("model")))
        out()
        out("| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |")
        out("|---|---|---|---|---|---|---|---|---|")
        for x in r.get("devices", []):
            out(row(x["phone"], yn(x["local"]), x["status"] + (f" ({x['detail']})" if x.get("detail") else ""), x["hits"],
                    "-" if x["local"] else x["rttMs"], x["tookMs"], x["withheldPrivate"], x["bytes"], x["indexedChunks"]))
        out()
        out("| # | Phone | File | Kind | Score | Snippet (first 90 chars) |")
        out("|---|---|---|---|---|---|")
        for s in r.get("sources", []):
            out(row(s["n"], s["phone"] + (" (local)" if s["local"] else ""), s["title"], s["kind"], f"{s['score']:.4f}", s["snippet"][:90].replace("\n", " ").replace("|", "/")))
        if not r.get("sources"):
            out(row("-", "-", "no sources shown", "-", "-", "-"))
        out()
        out("Answer text: " + (r.get("answer") or "").replace("\n", " "))
    out()
    out("Calibration (POST /v1/pdu/debug/scores, top rows by RRF; the dense floor in the app is read from the response)")
    for f in sorted(glob.glob(os.path.join(d, "pdu-scores-*.json"))):
        r = try_load(f)
        if not r:
            continue
        sp = r.get("cosineSpread") or {}
        out()
        out(f"{os.path.basename(f)[11:-5]}: {r['question']} | embed model {r['embedModel']} | denseMinCosine {r['denseMinCosine']} | minCoverage {r['minCoverage']} | "
            f"chunks {r['chunks']} | cosine over all chunks min {sp.get('min', 0):.3f} median {sp.get('median', 0):.3f} p90 {sp.get('p90', 0):.3f} max {sp.get('max', 0):.3f}")
        out()
        out("| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |")
        out("|---|---|---|---|---|---|---|---|---|")
        for x in r.get("rows", [])[:6]:
            out(row(x["title"], x["kind"], f"{x['cosine']:.3f}", x.get("denseRank"), "-" if x.get("bm25") is None else f"{x['bm25']:.2f}", x.get("bm25Rank"),
                    f"{x['coverage']:.2f}", f"{x['rrf']:.4f}", yn(x["passes"])))


def cmd_report_batch(d):
    names = names_of(d)
    meta = {}
    tsv = os.path.join(d, "batches.tsv")
    if os.path.exists(tsv):
        for line in open(tsv, encoding="utf-8"):
            p = line.rstrip("\n").split("\t")
            meta[p[0]] = p
    out("| Run | Mode | Thermal at start A / C | Peak thermal A / C | Wall-clock s | Items done / failed | Total tokens | Aggregate tokens/s | Model | Fake |")
    out("|---|---|---|---|---|---|---|---|---|---|")
    reports = []
    for f in sorted(glob.glob(os.path.join(d, "batch-*.json"))):
        r = try_load(f)
        if not r:
            continue
        tag = os.path.basename(f)[len("batch-"):-5]
        m = meta.get(tag, [tag, "", "?", "?", "?", "?"])
        reports.append((tag, r))
        out(row(tag, r["mode"], f"{m[2]} / {m[3]}", f"{m[4]} / {m[5]}", f"{r['wallMs'] / 1000:.1f}", f"{r['done']} / {r['failed']}",
                r["totalTokens"], f"{r['aggTokPerSec']:.2f}", ", ".join(r.get("models", [])), yn(r.get("fake"))))
    out()
    out("| Run | Phone | Jobs | Tokens | Share of tokens | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |")
    out("|---|---|---|---|---|---|---|")
    for tag, r in reports:
        total = sum(s["tokens"] for s in r["perPhone"]) or 1
        for s in r["perPhone"]:
            out(row(tag, names.get(s["nodeId"], s["nodeId"]), s["jobs"], s["tokens"], f"{100.0 * s['tokens'] / total:.0f} %", f"{s['tokPerSec']:.2f}", f"{s['decodeTokPerSec']:.2f}"))
    loc = [r["aggTokPerSec"] for _, r in reports if r["mode"] == "local"]
    sw = [r["aggTokPerSec"] for _, r in reports if r["mode"] == "swarm"]
    if loc and sw:
        out()
        out(f"Local mean {statistics.mean(loc):.2f} tokens/s over {len(loc)} run(s) ({', '.join(f'{x:.2f}' for x in loc)}); "
            f"swarm mean {statistics.mean(sw):.2f} tokens/s over {len(sw)} run(s) ({', '.join(f'{x:.2f}' for x in sw)}); "
            f"ratio swarm / local {statistics.mean(sw) / statistics.mean(loc):.2f}.")


COMMANDS = {
    "get": cmd_get, "done-ids": cmd_done_ids, "ask-body": cmd_ask_body, "slices": cmd_slices, "models-assert": cmd_models_assert, "peer-assert": cmd_peer_assert,
    "choir-assert": cmd_choir_assert, "ask-assert": cmd_ask_assert, "batch-assert": cmd_batch_assert,
    "report-choir-run": cmd_report_choir_run, "report-eval": cmd_report_eval, "report-pdu": cmd_report_pdu, "report-batch": cmd_report_batch,
}

if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2 or (sys.argv[1] != "question" and sys.argv[1] not in COMMANDS):
        sys.stderr.write(__doc__)
        sys.exit(2)
    if sys.argv[1] == "question":
        out(QUESTIONS[sys.argv[2]])
    else:
        COMMANDS[sys.argv[1]](*sys.argv[2:])
