import sys, json
raw = sys.stdin.read()
raw = raw.split("\nHTTP")[0]
try:
    d = json.loads(raw)
except Exception as e:
    print("unparseable:", raw[:300]); sys.exit(0)
if "phase" not in d:
    print(json.dumps(d)[:600]); sys.exit(0)
g = d.get("generation") or {}
b = d.get("baseline") or {}
print("phase=%s loadMs=%s rttMs=%s lost=%s fail=%s" % (d["phase"], d.get("loadMs"), d.get("rttMs"), d.get("lostPhone"), d.get("failReason")))
for p in d.get("phones", []):
    print("  %-8s online=%s step=%r rss=%s peak=%s recv=%s cache=%s frac=%.2f therm=%s ram=%s rtt=%s" % (
        p["name"], p["online"], p["step"][:60], p.get("rssMb"), p.get("peakMb"), p.get("receivedMb"), p.get("cachedMb"), p.get("loadFraction", 0), p.get("thermalStatus"), p.get("ramAvailMb"), p.get("rttMs")))
if g:
    print("  gen: running=%s ttftMs=%s tok/s=%s tokens=%s err=%s text=%r" % (g.get("running"), g.get("ttftMs"), g.get("decodeTokPerSec"), g.get("tokens"), g.get("error"), (g.get("text") or "")[:160]))
if b:
    print("  baseline:", json.dumps(b)[:300])
if d.get("smallModel"):
    print("  small:", d["smallModel"])
if d.get("log"):
    print("  log:", json.dumps(d["log"])[:300])
for s in d.get("steps", [])[-6:]:
    print("   -", s[:200])
