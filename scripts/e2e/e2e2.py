#!/usr/bin/env python3
"""Helper for scripts/e2e2-check.sh (second end-to-end run: Data Union, Knowledge Bank, throughput, Choir on 2 or 3 phones).
PC side, stdlib only. Reads the JSON the phones returned, makes the PASS/FAIL decisions and writes markdown tables.
Every number printed is read from a response file. Shares the question list and basic helpers with e2e.py.

  e2e2.py get FILE EXPR [DEFAULT]           (same as e2e.py)
  e2e2.py ask-body KEY [K] [DEADLINE]       ASCII JSON body for /v1/pdu/ask, /v1/pdu/debug/scores
  e2e2.py question KEY
  e2e2.py models-assert FILE CHATFILE       PASS/FAIL line for GET /v1/models/state (backend and notice included)
  e2e2.py ask-assert FILE KIND              KIND: relevant | none | any | incomplete
  e2e2.py batch-assert FILE MODE PHONES     PASS/FAIL for a finished sched batch (MODE local|swarm, PHONES expected count)
  e2e2.py kb-save-body ASKFILE SHARE        POST /v1/kb/debug/save body made from a real ask answer and its sources
  e2e2.py kb-tamper-body KBFILE ID          POST /v1/kb/cards body with the answer of card ID changed
  e2e2.py kb-card KBFILE ID FIELD           one field of card ID from GET /v1/kb/cards
  e2e2.py report-pdu DIR | report-batch DIR | report-kb DIR
"""
import glob
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e  # noqa: E402
from e2e import load, out, row, try_load, yn  # noqa: E402

e2e.QUESTIONS.update({
    "restaurant": "What was the total on the restaurant bill?",
    "yoga": "When is the yoga class?",
    "cab": "How much was the cab ride?",
    "peer_down": "What is in the router setup notes?",
})


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
    if bad:
        return out("FAIL " + "; ".join(bad))
    note = f" notice={chat.get('notice')}" if chat.get("notice") else ""
    out(f"PASS chat={chat.get('file')} backend={chat.get('backend')} loadMs={chat.get('loadMs')} rssMb={chat.get('rssMb')}; embed={emb.get('file')}; thermal={d.get('thermalStatus')}{note}")


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
        bad.append("answered by keywords only (lexicalOnly)")
    if kind == "relevant" and not r.get("sources"):
        bad.append("no sources")
    if kind == "none" and r.get("sources"):
        bad.append(f"{len(r['sources'])} source(s) shown: " + ", ".join(f"{s['phone']}:{s['title']}" for s in r["sources"]))
    peers_bad = [x["phone"] + ":" + x["status"] for x in r.get("devices", []) if x.get("status") != "ok" and not x.get("local")]
    if kind == "incomplete":
        if not r.get("incomplete"):
            bad.append("not flagged incomplete although a peer app was stopped")
        if not peers_bad:
            bad.append("no peer reported as not ok")
    else:
        if peers_bad:
            bad.append("peer not ok: " + ", ".join(peers_bad))
        if r.get("incomplete") and kind != "any":
            bad.append("flagged incomplete")
    if bad:
        return out("FAIL " + "; ".join(bad))
    t = r["timings"]
    out(f"PASS sources={len(r.get('sources', []))} ttftMs={t['ttftMs']} totalMs={t['totalMs']} incomplete={e2e.scalar(r.get('incomplete'))} withheld={r.get('withheldPrivate')} peers={'; '.join(peers_bad) or 'ok'}")


def cmd_batch_assert(file, mode, phones):
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
    took = [s for s in r.get("perPhone", []) if s.get("jobs", 0) > 0]
    if len(took) != int(phones):
        bad.append(f"{len(took)} phone(s) took jobs, expected {phones}")
    if bad:
        return out("FAIL " + "; ".join(bad))
    out(f"PASS wallMs={r['wallMs']:.0f} tokens={r['totalTokens']} aggTokPerSec={r['aggTokPerSec']:.2f} phones={len(took)}")


def cmd_kb_save_body(ask_file, share):
    r = load(ask_file)
    srcs = [{"phone": s["phone"], "title": s["title"], "dateMs": s["dateMs"], "priv": False} for s in r["sources"]][:8]
    out(json.dumps({"question": r["question"], "answer": r["answer"][:600], "sources": srcs, "share": share == "true"}, ensure_ascii=True))


def cmd_kb_card(kb_file, card_id, field):
    d = try_load(kb_file) or []
    for c in d:
        if c.get("id") == card_id:
            return out(e2e.scalar(c.get(field)))
    out("")


def cmd_kb_tamper_body(kb_file, card_id):
    for c in load(kb_file):
        if c["id"] == card_id:
            w = {k: c[k] for k in ("id", "question", "answer", "sources", "authorId", "createdMs", "auditHash", "mac")}
            w["authorName"] = c["author"]
            w["answer"] = "Ten years."
            return out(json.dumps({"cards": [w]}, ensure_ascii=True))
    out("{}")


# ---------------------------------------------------------------------------------------------
# reports
# ---------------------------------------------------------------------------------------------

def names_of(d):
    m = {}
    for f in glob.glob(os.path.join(d, "hello-*.json")):
        h = try_load(f)
        if h:
            m[h["nodeId"]] = h["name"]
    return m


def cmd_report_pdu(d):
    IDXNAME = {str(i): (try_load(os.path.join(d, f"hello-{i}.json")) or {}).get("name", str(i)) for i in (1, 2, 3)}
    th = {}
    p = os.path.join(d, "thermal.log")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8"):
            if line.startswith("pdu-ask-"):
                k, rest = line.rstrip("\n").split("\t", 1)
                th[k[len("pdu-ask-"):]] = rest.replace("thermal start ", "")
    out("| Ask | Asked on | Question | Embed ms | Local search ms | Slowest peer round trip ms | Time to first token ms | Total ms | Sources shown (phone: file) | Withheld by peers | Incomplete | Thermal at start |")
    out("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(d, "pdu-ask-*.json")), key=os.path.getmtime):
        r = try_load(f)
        if not r:
            continue
        k = os.path.basename(f)[len("pdu-ask-"):-5]
        t = r["timings"]
        peers = [x for x in r["devices"] if not x["local"]]
        me = [x["phone"] for x in r["devices"] if x["local"]]
        src = "; ".join(f"{s['phone']}: {s['title']}" for s in r["sources"]) or "none"
        out(row(k, me[0] if me else "?", r["question"], t["embedMs"], t["localSearchMs"], max([x["rttMs"] for x in peers] or [0]), t["ttftMs"], t["totalMs"], src,
                sum(x["withheldPrivate"] for x in peers), yn(r.get("incomplete")), th.get(k, "?")))
    out()
    out("| Ask | Phone | Local | Status | Detail | Hits | Peer round trip ms | Search ms | Withheld private | Indexed chunks |")
    out("|---|---|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(d, "pdu-ask-*.json")), key=os.path.getmtime):
        r = try_load(f)
        k = os.path.basename(f)[len("pdu-ask-"):-5]
        for x in (r or {}).get("devices", []):
            out(row(k, x["phone"], yn(x["local"]), x["status"], x.get("detail") or "", x["hits"], "-" if x["local"] else x["rttMs"], x["tookMs"], x["withheldPrivate"], x["indexedChunks"]))
    out()
    out("Answers")
    for f in sorted(glob.glob(os.path.join(d, "pdu-ask-*.json")), key=os.path.getmtime):
        r = try_load(f)
        if r:
            out()
            out(f"- {os.path.basename(f)[len('pdu-ask-'):-5]} ({r['question']}): " + (r.get("answer") or "").replace("\n", " ") + (f"  [note: {r['note']}]" if r.get("note") else ""))
    out()
    out("Index runs")
    out()
    out("| Phone | Folder | Seen | Indexed | Chunks | OCR images | OCR ms avg | Embed ms | Total ms | Failed | Error |")
    out("|---|---|---|---|---|---|---|---|---|---|---|")
    for f in sorted(glob.glob(os.path.join(d, "pdu-index-*.json")), key=os.path.getmtime):
        r = try_load(f)
        if not r:
            continue
        parts = os.path.basename(f)[len("pdu-index-"):-5].split("-", 1)
        out(row(IDXNAME.get(parts[0], parts[0]), parts[1] if len(parts) > 1 else "", r.get("seen"), r.get("indexed"), r.get("chunks"), r.get("ocrCount"), r.get("ocrMsAvg"), r.get("embedMs"), r.get("totalMs"), r.get("failed"), r.get("error") or "; ".join(r.get("errors", [])[:3])))
    out()
    out("Calibration (POST /v1/pdu/debug/scores)")
    for f in sorted(glob.glob(os.path.join(d, "pdu-scores-*.json"))):
        r = try_load(f)
        if not r:
            continue
        sp = r.get("cosineSpread") or {}
        out()
        out(f"{os.path.basename(f)[11:-5]}: {r['question']} | embed model {r['embedModel']} | denseMinCosine {r['denseMinCosine']:.2f} | chunks {r['chunks']} | cosine over all chunks min {sp.get('min', 0):.3f} median {sp.get('median', 0):.3f} p90 {sp.get('p90', 0):.3f} max {sp.get('max', 0):.3f}")
        out()
        out("| Title | Cosine | Dense rank | BM25 | Coverage | Passes |")
        out("|---|---|---|---|---|---|")
        for x in r.get("rows", [])[:5]:
            out(row(x["title"], f"{x['cosine']:.3f}", x.get("denseRank"), "-" if x.get("bm25") is None else f"{x['bm25']:.2f}", f"{x['coverage']:.2f}", yn(x["passes"])))


def cmd_report_batch(d):
    names = names_of(d)
    meta = {}
    tsv = os.path.join(d, "batches.tsv")
    if os.path.exists(tsv):
        for line in open(tsv, encoding="utf-8"):
            p = line.rstrip("\n").split("\t")
            meta[p[0]] = p
    out("| Run | Mode | Phones | Coordinator | Thermal at start | Peak thermal | Wall-clock s | Done / failed | Tokens | Aggregate tokens/s | Backend |")
    out("|---|---|---|---|---|---|---|---|---|---|---|")
    reps = []
    for f in sorted(glob.glob(os.path.join(d, "batch-*.json")), key=os.path.getmtime):
        r = try_load(f)
        if not r:
            continue
        tag = os.path.basename(f)[len("batch-"):-5]
        m = meta.get(tag, [tag, "", "", "", "", "", ""])
        reps.append((tag, r))
        out(row(tag, r["mode"], len(r["perPhone"]), m[2], m[3], m[4], f"{r['wallMs'] / 1000:.1f}", f"{r['done']} / {r['failed']}", r["totalTokens"], f"{r['aggTokPerSec']:.2f}", m[5] if len(m) > 5 else ""))
    out()
    out("| Run | Phone | Jobs | Tokens | Share of tokens | Busy s (sum of generation time) | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |")
    out("|---|---|---|---|---|---|---|---|")
    for tag, r in reps:
        total = sum(s["tokens"] for s in r["perPhone"]) or 1
        busy = {}
        for it in r.get("items", []):
            busy[it.get("nodeId")] = busy.get(it.get("nodeId"), 0.0) + it.get("genMs", 0.0) / 1000
        for s in r["perPhone"]:
            out(row(tag, names.get(s["nodeId"], s["nodeId"]), s["jobs"], s["tokens"], f"{100.0 * s['tokens'] / total:.0f} %", f"{busy.get(s['nodeId'], 0):.1f}", f"{s['tokPerSec']:.2f}", f"{s['decodeTokPerSec']:.2f}"))
    singles = {}
    for tag, r in reps:
        if r["mode"] == "local":
            singles[tag] = r["aggTokPerSec"]
    swarm = {}
    for tag, r in reps:
        if r["mode"] == "swarm":
            swarm.setdefault(len(r["perPhone"]), []).append(r["aggTokPerSec"])
    if singles:
        best_tag = max(singles, key=singles.get)
        out()
        out("Single phones: " + ", ".join(f"{k} {v:.2f}" for k, v in singles.items()) + f". Fastest single phone: {best_tag} at {singles[best_tag]:.2f} tokens/s.")
        for n, v in sorted(swarm.items()):
            out(f"Swarm of {n} phones: {', '.join(f'{x:.2f}' for x in v)} tokens/s (mean {statistics.mean(v):.2f}); against the fastest single phone: " + ", ".join(f"{x / singles[best_tag]:.2f}x" for x in v) + f" (mean {statistics.mean(v) / singles[best_tag]:.2f}x).")


# ---------------------------------------------------------------------------------------------
# Choir evaluation (strict scorer): arms with n, accuracy, mean seconds, invalid answers
# ---------------------------------------------------------------------------------------------

import re  # noqa: E402


def valid_answer(kind, ans):
    """An answer is valid when it has the shape the problem kind asks for (a number, an option letter, a short non-empty output)."""
    if ans is None or str(ans).strip() == "":
        return False
    a = str(ans).strip()
    if kind == "MATH":
        return re.fullmatch(r"-?\d+(\.\d+)?", a) is not None
    if kind == "MCQ":
        return re.fullmatch(r"[A-F]", a) is not None
    return len(a) <= 60


def eval_rows(d, tag):
    rows, meta = {}, {"models": set(), "nodes": set(), "check": set(), "stopped": False}
    files = sorted(glob.glob(os.path.join(d, f"eval-{tag}-*.json")), key=lambda p: int(os.path.basename(p).rsplit("-", 1)[1].split(".")[0]))
    for f in files:
        rep = try_load(f)
        if not rep or "rows" not in rep:
            continue
        if rep.get("model"):
            meta["models"].add(rep["model"])
        meta["nodes"].update(rep.get("nodes", []))
        meta["check"].add(e2e.scalar(rep.get("useProblemCheck")))
        meta["stopped"] = meta["stopped"] or bool(rep.get("stopped"))
        for r in rep["rows"]:
            rows[r["problemId"]] = r
    return list(rows.values()), meta


def cmd_report_eval(d, tag):
    rows, meta = eval_rows(d, tag)
    n = len(rows)

    def mean(xs):
        return sum(xs) / len(xs) if xs else 0.0

    def invalid_of(r):
        k = r["kind"]
        b = 0 if valid_answer(k, r.get("baselineAnswer")) else 1
        sm = len(r.get("samplesFailures") or []) + sum(1 for a in (r.get("samplesAnswers") or []) if not valid_answer(k, a))
        c = 0 if valid_answer(k, r.get("choirAnswer")) else 1
        t = 0 if valid_answer(k, r.get("choirToolAnswer")) else 1
        return b, sm, c, t

    inv = [invalid_of(r) for r in rows]
    sampled = [r for r in rows if r.get("samplesAnswered") is not None]
    out(f"Model: {', '.join(sorted(meta['models'])) or '?'}; phones in the run: {', '.join(sorted(meta['nodes'])) or '?'}; check against the problem's own test: {', '.join(sorted(meta['check'])) or '?'}; problems run: n = {n} of 20; rows with an error: {sum(1 for r in rows if r.get('error'))}.")
    out()
    out("| Arm | n | Correct | Accuracy | Mean wall-clock per problem (s) | Invalid answers |")
    out("|---|---|---|---|---|---|")
    pc = lambda a, b: f"{100.0 * a / b:.0f} %" if b else "-"
    cb = sum(1 for r in rows if r.get("baselineCorrect"))
    cs = sum(1 for r in sampled if r.get("samplesCorrect"))
    cc = sum(1 for r in rows if r.get("choirCorrect"))
    ct = sum(1 for r in rows if r.get("choirToolCorrect"))
    out(row("One phone alone (coordinator's own first answer)", n, cb, pc(cb, n), f"{mean([r.get('baselineMs', 0) for r in rows]) / 1000:.1f}", sum(i[0] for i in inv)))
    out(row("One phone, three samples, majority", len(sampled), cs, pc(cs, len(sampled)), f"{mean([r.get('samplesMs', 0) for r in sampled]) / 1000:.1f}", sum(i[1] for i in inv)))
    out(row("Choir, all phones, no tool check", n, cc, pc(cc, n), f"{mean([r.get('choirMs', 0) for r in rows]) / 1000:.1f}", sum(i[2] for i in inv)))
    out(row("Choir, all phones, with tool check", n, ct, pc(ct, n), f"{mean([r.get('choirMs', 0) for r in rows]) / 1000:.1f} (same run)", sum(i[3] for i in inv)))
    out()
    ver = sum(1 for r in rows if r.get("verified"))
    bt = sum(1 for r in rows if r.get("verifiedByTest"))
    cons = sum(1 for r in rows if r.get("consistent"))
    out(f"Tool check as the app defines it: verified {ver} of {n} (against the problem's own test: {bt}; only consistent with the models' written arithmetic: {cons}); abstained {sum(1 for r in rows if r.get('toolMethod') == 'ABSTAIN')}; mean rounds {mean([r.get('rounds', 1) for r in rows]):.2f}; rows where fewer phones answered than were asked: {sum(1 for r in rows if r.get('nodesAnswered', 0) < r.get('nodesTotal', 0))}.")
    out(f"Invalid answers = no answer, or one that is not a number (MATH), an option letter (MCQ) or a short output (CODE); sample failures count as invalid. Total over all four arms: {sum(sum(i) for i in inv)}.")
    out()
    out("| Kind | n | One phone | Three samples | Choir | Choir + tool |")
    out("|---|---|---|---|---|---|")
    for kind in ("MATH", "MCQ", "CODE"):
        sub = [r for r in rows if r.get("kind") == kind]
        if sub:
            ss = [r for r in sub if r.get("samplesAnswered") is not None]
            out(row(kind, len(sub), sum(1 for r in sub if r.get("baselineCorrect")), f"{sum(1 for r in ss if r.get('samplesCorrect'))} of {len(ss)}", sum(1 for r in sub if r.get("choirCorrect")), sum(1 for r in sub if r.get("choirToolCorrect"))))
    out()
    out("| Slice | Problems | Thermal at start | Peak thermal | Wall (s) | HTTP | Rows | Note |")
    out("|---|---|---|---|---|---|---|---|")
    tsv = os.path.join(d, f"slices-{tag}.tsv")
    if os.path.exists(tsv):
        for line in open(tsv, encoding="utf-8"):
            out(row(*line.rstrip(chr(10)).split(chr(9))))
    out()
    out("| Problem | Kind | Expected | One phone | 3 samples | Choir | Choir + tool | Verified | Rounds | Choir s | Phones answered |")
    out("|---|---|---|---|---|---|---|---|---|---|---|")
    mark = lambda ans, ok: f"{ans} ({'ok' if ok else 'wrong'})" if ans is not None else "none"
    for r in sorted(rows, key=lambda r: r["problemId"]):
        v = "test" if r.get("verifiedByTest") else ("consistent" if r.get("consistent") else "no")
        samp = mark(r.get("samplesAnswer"), r.get("samplesCorrect")) if r.get("samplesAnswered") is not None else "not run"
        out(row(r["problemId"], r["kind"], r["expected"], mark(r.get("baselineAnswer"), r.get("baselineCorrect")), samp, mark(r.get("choirAnswer"), r.get("choirCorrect")),
                mark(r.get("choirToolAnswer"), r.get("choirToolCorrect")), v, r.get("rounds"), f"{r.get('choirMs', 0) / 1000:.1f}", f"{r.get('nodesAnswered')}/{r.get('nodesTotal')}"))


COMMANDS = {
    "get": e2e.cmd_get, "ask-body": e2e.cmd_ask_body, "models-assert": cmd_models_assert, "ask-assert": cmd_ask_assert,
    "batch-assert": cmd_batch_assert, "kb-save-body": cmd_kb_save_body, "kb-tamper-body": cmd_kb_tamper_body, "kb-card": cmd_kb_card,
    "report-pdu": cmd_report_pdu, "report-batch": cmd_report_batch, "report-eval": cmd_report_eval, "slices": e2e.cmd_slices,
}

if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2 or (sys.argv[1] != "question" and sys.argv[1] not in COMMANDS):
        sys.stderr.write(__doc__)
        sys.exit(2)
    if sys.argv[1] == "question":
        out(e2e.QUESTIONS[sys.argv[2]])
    else:
        COMMANDS[sys.argv[1]](*sys.argv[2:])
