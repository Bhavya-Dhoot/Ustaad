import sys, json
raw = sys.stdin.read().split("\nHTTP")[0]
try:
    d = json.loads(raw)
except Exception:
    print("unparseable:", raw[:200]); sys.exit(0)
me = d["self"]
dr = me.get("direct") or {}
print("self=%s direct: role=%s phase=%s local=%s owner=%s band=%s mhz=%s" % (me["name"], dr.get("role"), dr.get("phase"), dr.get("localAddress"), dr.get("ownerAddress"), dr.get("band"), dr.get("frequencyMhz")))
for p in d["peers"]:
    print("  peer %-8s %-6s %-6s ip=%s rtt=%s therm=%s speed=%s" % (p["name"], p["link"], p["state"], p["ip"], p.get("rttMs") and round(p["rttMs"], 1), p.get("thermalStatus"), p.get("linkSpeedMbps")))
