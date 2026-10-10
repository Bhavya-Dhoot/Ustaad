p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()

def rep(old_start, old_end_marker, new):
    global s
    a = s.index(old_start)
    b = s.index(old_end_marker, a)
    s = s[:a] + new + s[b:]

# trust model section: from the intro sentence to the end of the residual risk paragraph
a = s.index("llama.cpp's rpc-server has no authentication")
b = s.index("## Exact arguments")
s = s[:a] + """llama.cpp's rpc-server has no authentication: whoever can open its port can read and write tensors on the phone. In this app (`memcloud/RpcLender.kt`, wired to the phone by `RpcWorker.kt`):

1. It starts only on a signed `POST /v1/memcloud/rpc/start` (HMAC with the pairing secret), i.e. from a phone that holds the secret; the sender node id is recorded and shown. One driver at a time holds the loan.
2. It binds to one address, the link address the driver reached the worker on (Direct group address when Direct is up, else the LAN address), and that address must be in the link module's advertisable set (`LinkRoute.advertisable()`: Wi-Fi, hotspot and Direct interfaces; cellular and every other interface are refused). The request is also refused for `0.0.0.0`, `::` and `127.x`. Measured on a phone (first pass): `-H 192.168.49.1` on A (rpc.log: `endpoint : 192.168.49.1:50052`).
3. **Lease**: the loan lasts `RpcLender.LEASE_MS` = 10 s and the driver renews it every 3 s (`POST /v1/memcloud/rpc/renew`, accepted only from the phone that started the loan). When the lease runs out the worker stops rpc-server and restarts its own chat and embedding servers from the list the driver sent with the start request, whatever the driver's heartbeat says (a driver that is online but idle, or dead, ends the loan the same way). The driver also stops renewing 60 s after DEGRADED, and `POST /rpc/stop` ends it at once when the split ends.
4. The file names in that restore list are reduced to a file name (`File(name).name`), must be `.gguf` and must exist in the models folder; anything else is dropped.
5. While it listens the Memory tab shows "Phone A is lending memory to Phone C on the direct link (192.168.49.1). It stops by itself 10 s after Phone C last renewed the loan." with a Stop lending button, and the phone-wide banner says "Lending memory to Phone C." (PhoneActivity owner `memcloud.lend`; the driver's line uses owner `memcloud.driver`, so neither clears the other's).

Residual risk: any phone on the same link that knows the port can still talk to rpc-server while it listens. The window is the length of a split plus at most 10 s after the driver goes quiet. **The lease, the renew route and the advertisable-address check are covered by unit tests with a fake clock and fake phones (`RpcLenderTest`, 11 tests) and were not run on a phone in the second pass (no phone use). The first-pass phone runs used the earlier heartbeat watchdog, which this lease replaces.**

""" + s[b:]

a = s.index("## What the unit tests cover")
b = s.index("## Not verified")
s = s[:a] + """## What the unit tests cover (443 tests in the app, 0 failures; 71 of them in memcloud)

GGUF header reader (a synthetic file; the real 30B header when present: 48 layers, 128 experts, 8 used; corrupt headers with absurd counts give a clean `GgufFormatException`, never an out-of-memory), planner for 2, 3 and 4 peers including flags vs llama.cpp's placement rule, untied head pinned to the driver, tied head counted on the remote phone (so received bytes are compared with planned bytes including the embedding copy), load-log parser, ExpertMesh arithmetic, DEGRADED and re-plan decisions for two and three phones, thread count, activity line.

`SplitCoordinatorTest` (14 tests) runs the whole coordinator against a fake phone: a successful split and stop; a failed rpc start on one of two peers; a llama-server that does not start; a cancelled load (by `stop()` and by cancelling the job); no peers; a lost phone and the 60 s renewal window; a re-plan that leaves the remaining worker running; the app restarting during a split (persisted restore list, peers, baseline); a killed baseline. Each failure path is asserted to send `/rpc/stop` to every touched peer, load back the servers of a peer that never got rpc-server running, restore this phone's chat and embedder exactly once, and clear the activity line. `RpcLenderTest` (11 tests) covers the lease, renewals, one driver at a time, address checks and restore-list sanitising.

""" + s[b:]

old = "- 10 s driver-lost watchdog on a worker, the Stop lending button, PhoneActivity banner: not exercised."
assert old in s
s = s.replace(old, "- The lease, `/rpc/renew`, the Stop lending button and the PhoneActivity banners: unit-tested only, never run on a phone.", 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
