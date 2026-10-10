import sys, json
d = json.load(sys.stdin)
r = d.get("roles", {})
out = {}
for k in ("chat", "embed", "rpc"):
    x = r.get(k, {})
    le = x.get("lastError")
    out[k] = {"state": x.get("state"), "backend": x.get("backend"), "restarts": x.get("restarts"), "lastError": (le[:60] + "...") if le else le, "notice": x.get("notice")}
out["useGpu"] = d.get("useGpu")
print(json.dumps(out))
