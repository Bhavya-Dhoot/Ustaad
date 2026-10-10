p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()
marker = "## Pending, ready to run: three phones, Qwen3-30B-A3B-Instruct-2507-Q4_K_M (needs A, B and C)\n"
assert marker in s
new = marker + """
**Status at 08:19: not run.** Phone B was released and the build and scripts were ready, but phones A and C stayed held by other jobs (choir, link, visual QA, shell) from 07:20 to 08:18, and when the markers cleared at 08:18:25 the end-to-end tester wrote `e2e` into all three marker files in the same second the waiter polled, so the three-phone run did not start. Nothing in this section has been measured.

Setup commands used by the prepared scripts (all three phones accept the dev secret `swarmos-dev-secret` at this commit; signed with `scripts/swarm-call.sh <ip> <METHOD> <path> [json]`):

```
# 0. install and launch (adb install -r app-debug.apk; am start -n com.swarmos.app/.MainActivity --es name "Phone X" --ez setupDone true) on A 192.168.242.89, B 192.168.242.23, C 192.168.242.76
# 1. Wi-Fi Direct group with C as host (the driver sends the bulk); A and B join with the payload C returns
swarm-call.sh 192.168.242.76 POST /link/direct/host '{"rotate":false}'      # answers the pairing payload with p2p {ssid, pass}
swarm-call.sh 192.168.242.89 POST /link/direct/join '{"ssid":"<ssid>","pass":"<pass>"}'
swarm-call.sh 192.168.242.23 POST /link/direct/join '{"ssid":"<ssid>","pass":"<pass>"}'
swarm-call.sh 192.168.242.76 GET  /link/peers                               # A and B online on DIRECT; the rpc endpoints in args show which address is used
# 2. sanity: the 4B model in thirds (counts 11/11/10), one 100-token answer
swarm-call.sh 192.168.242.76 POST /memcloud/start '{"file":"Qwen3.5-4B-Q4_K_M.gguf","peers":["Phone A","Phone B"]}'
# 3. the 30B file: adb -s <C> shell cp /data/local/tmp/llama/models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf /sdcard/Android/data/com.swarmos.app/files/models/   (check free space first)
```

Then the 30B calls below. If it does not load or gives no token within 10 minutes, stop and record where it failed (phone, memory, last log lines); one retry at most, with a different split, e.g. `"layers":[10,19,19]` in `/memcloud/start`.

"""
s = s.replace(marker, new, 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
