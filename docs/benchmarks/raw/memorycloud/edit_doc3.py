p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()
old = "- ExpertMesh screen: not shown on a phone (needs an MoE file in the app's models folder; the 30B file is not there)."
assert old in s
s = s.replace(old, old + """
- The ExpertMesh figures are **estimates of network wait only**, from the round trip of a TCP connect to the worker's rpc-server port (median of 3 connects, `AndroidSplitEnv.tcpRttMs`; one network round trip, unlike the HTTP heartbeat, which adds the server's handling). That measurement code was not run on a phone. The planning overhead (1.5 GB per phone for KV cache, buffers and the process) is a planning allowance, and the screen calls the resulting need an estimate.""", 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
