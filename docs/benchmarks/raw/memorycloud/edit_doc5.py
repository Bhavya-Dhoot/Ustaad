p = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\docs\benchmarks\2026-10-10-memorycloud-inapp.md"
s = open(p, encoding="utf-8").read()

# intro paragraph
a = s.index("What this covers:")
b = s.index("\n", a)
s = s[:a] + "What this covers: the in-app mechanism (planner, rpc-server start and stop through signed routes, memory hygiene, llama-server with `--rpc`, streamed answer, DEGRADED and re-plan). First a two-phone run with the 4B model (C driver, A worker), then, at the end of this file, the **three-phone run of Qwen3-30B-A3B (18.6 GB), which loaded and generated** (section \"Three phones\")." + s[b:]

s = s.replace("- 30B model: no run. It does not load on one phone or over two (spike). Three-phone run pending.\n- Three-phone split: not run; planner and state machine are unit-tested for it.\n",
              "- 30B model: ran only over three phones (section \"Three phones\"); it does not load on one phone or over two (spike). Not measured there: a cold load repeated on cleared caches, a 5-minute sustained generation, thermal after 5 minutes.\n", 1)

# the procedure section becomes the record of the commands used
old_head = "## Pending, ready to run: three phones, Qwen3-30B-A3B-Instruct-2507-Q4_K_M (needs A, B and C)\n"
assert old_head in s
a = s.index(old_head)
b = s.index("Setup commands used by the prepared scripts", a)
s = s[:a] + "## Procedure used for the three-phone run (results in \"Three phones\" above it)\n\nRun at 09:18-09:37 on 2026-10-10. An earlier attempt to start at 08:18 lost the device queue to another job; this is the procedure as prepared, and as executed.\n\n" + s[b:]

three = """## Three phones: Qwen3-30B-A3B-Instruct-2507-Q4_K_M, driver C, lenders A and B (2026-10-10, 09:18 to 09:37)

**Result: it loads and generates.** An 18.6 GB model (17.3 GiB, `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf`, 18,556,686,752 B, 48 layers, 128 experts, 8 used per token) that does not run on one phone and did not load over two (`docs/benchmarks/2026-10-10-rpc-split-spike.md`) ran through the app over three phones: **load 319 s cold, 65 s warm, first token 1.5 to 2.8 s, decode 5.7 to 6.4 tok/s**. Debug build of master e2d41e4 (`com.swarmos.app`) on all three phones; the 30B file was copied on the driver from `/data/local/tmp/llama/models` into the app's models folder with `adb shell cp` (35 s, 409 GB free; it stays there).

### Setup and link

- Wi-Fi Direct group, C as host (`POST /v1/link/direct/host {"rotate":false}`, then `/v1/link/direct/join` on A and B): C 192.168.49.1, A 192.168.49.73, B 192.168.49.175, 5 GHz, 5765 MHz, formed in 1.1 s each. The swarm's bulk path was DIRECT for both lenders. **The rpc endpoints that carried the weights and the tokens were `192.168.49.73:50052` and `192.168.49.175:50052` (the Direct addresses; see the `--rpc` flag below).**
- Raw probe `POST /v1/link/tput` (200 MB, DIRECT, 09:19): C to A **680 Mbps** (receiver 679.8), C to B **245 Mbps**; round trip avg 6.7 ms (A) and 7.2 ms (B), p95 11 and 13 ms. In the app: TCP connect to the rpc ports 7.1 ms (A) and 7.8 ms (B).
- Thermal guard: at 09:18 A=2, B=2, C=1; waited 1.5 minutes; at 09:20:18 A=1, B=1, C=1 (skin 40.5 to 41.0 C, two skin sensors each). No other llama or rpc process on any phone.
- Plan from `GET /v1/memcloud/plan` (estimates, weights from the GGUF header): C layers 0-15 weights 6220 MB (with embedding table and the untied lm_head), A layers 16-31 5611 MB, B layers 32-47 5859 MB; planning estimate 7.1 to 7.8 GB each against 10.9 to 11.2 GB each could give.

### Exact arguments

```
# driver C (state route `args`): llama-server CPU build, port 8080 on 127.0.0.1, defaults then the planner's flags
libllama-server.so -m .../models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -t 6 -c 8192 -np 2 --reasoning off --no-webui \\
    --rpc 192.168.49.73:50052,192.168.49.175:50052 -ngl 33 -ts 15.5,17.5 -ot ^output\\.weight=CPU -t 8 -c 4096 -np 1 -fit off
# lenders A and B (started by the app): librpc-server.so -H <their Direct address> -p 50052 -t 8 -c      (tensor cache on, bound to the Direct address only)
# calls, signed with scripts/swarm-call.sh (dev secret), all from the PC:
POST C /v1/memcloud/start {"file":"Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf","peers":["Phone A","Phone B"]}
POST C /v1/memcloud/ask   {"prompt":"Explain how a heat pump works, in about 100 words.","maxTokens":100}   (and 200 words / 200 tokens)
POST C /v1/memcloud/replan, POST C /v1/memcloud/stop
```

### Numbers

| What | Result | State |
|---|---|---|
| First start, 09:20:38 | **FAILED in 12 s**: `Phone A: rpc-server ... port 50052 is still in use after two retries`. A stale listener held the port at that moment; the failure teardown ran, nothing was left on any phone (checked: no rpc or llama process), and the start was repeated at once. Not reproduced. | A 1, B 1, C 1 |
| Load, cold (the 30B had never been sent to A or B), 09:21:15 to 09:26:34 | **319.2 s** to READY (`loadMs` 319171). Bytes the lenders received (anonymous memory of rpc-server): **A 5743 MB, B 5991 MB** (planned 5611 and 5859); tensor cache on disk A 6792 MB (includes 1.4 GB of older 4B tensors), B 5679 MB. B was receiving at 23 to 27 % after 90 s while A showed none yet. Driver C llama-server peak RSS 9123 MB (VmHWM) during the load, 3.0 GB right after READY, 4.9 GB while serving. | start A 1, B 1, C 1; at READY A 1, B 0, C 2 |
| Load, warm (re-plan after the pull test, 09:31:49 to 09:32:54; B's app was restarted so its own chat and embedding servers had come back and were stopped again first) | **65.3 s** to READY; A 5743 MB and B 5389 MB received (reloaded from the tensor cache, no bytes over the link for weights that were cached) | A 0, B 0, C 1 |
| 100-token answer, 09:27 ("Explain how a heat pump works, in about 100 words.") | TTFT **2.78 s**, decode **5.69 tok/s** (server timings, 100 tokens) | A 1, B 0, C 2 |
| 200-token answer, 09:29:47 (same prompt, 200 words) | TTFT **1.49 s**, decode **6.20 tok/s** (200 tokens); the screen showed 6.4 tok/s live | A 0, B 0, C 1 at the end |
| 100-token answer after the re-plan, 09:33 | TTFT 2.14 s, decode **6.42 tok/s** | A 0, B 0, C 1 |
| Pull test, 09:31: 200-token story streaming (27 tokens out, TTFT 2.0 s, 5.5 tok/s), `adb shell am force-stop com.swarmos.app` on lender B at T0 (command 353 ms) | C reported **DEGRADED naming "Phone B" at T0 + 1.77 s** (the poll at +0.93 s still said READY); `EOFException` on the stream, llama-server stopped, 27 tokens kept on screen. Re-plan after B's app was relaunched (09:31:24): READY after 65.3 s, row above. | |
| Memory before the load (MemAvailable) | A 11.19 GB, B 11.49 GB, C 11.51 GB; SwapFree A 15.06, B 15.53, C 15.13 GB | 09:20 |
| Memory with the split ready and after the 200-token answer | A 5.33 and 5.24 GB, B 5.25 and 5.31 GB, C 5.27 and 5.13 GB available; SwapFree C 11.76 GB at READY and 13.7 GB later (C swapped about 3.4 GB at the peak and gave back about 2 GB), A 15.1 and B 15.4 GB unchanged | 09:27 and 09:30 |
| PSS of the app plus its llama children (read with `run-as`, 09:30:48 while serving) | A **5887 MB** (rpc-server 5746, app 140); B **6130 MB** (rpc-server 5995, app 134); C **5088 MB** (llama-server 4930, app 158). After the re-plan (09:34): A 5887, B 5804, C 4817 | |
| Thermal at the end, 09:34:17 | A 0 (skin 39.0 C), B 0 (39.0 C), C 1 (41.0 C) | |
| Android kills (events log, 09:20 to 09:35) | Nothing of `com.swarmos.*` except my own `am force-stop` of B. On C the system killed cached apps during both loads (09:21:42: camera, a vivo widget service; 09:32:08: Google search, Messaging, bbk.account, vivo widget, imanager). A: none. B: cached apps around the force-stop only. | |

Reading: the capacity claim holds on these phones. About 6 GB per phone, roughly 5.1 GB on the driver, with 5.2 to 5.3 GB still available on each phone while serving. Decode is 5.7 to 6.4 tok/s, inside the research estimate of 6 to 10 (docs/research/03), and the first token comes in 1.5 to 2.8 s. The cold load is 5.3 minutes, set by the slower Direct link to B (245 Mbps against 680 Mbps to A) and by the driver reading the file; the demo should load once and keep the cache. The 4B sanity run in thirds was skipped: the 30B run proves the three-phone path.

### Checked on the screen (driver C)

Screenshots with the Memory tab in front (`mCurrentFocus` = `com.swarmos.app/.MainActivity` checked first): `docs/design/shots/memcloud-ready-30b.png` (SPLIT READY, 6.4 tok/s, first word after 1.5 s, load time 319 s, TCP round trips 7.1 and 7.8 ms, 3 phone boundaries per token, strip C/A/B, the model's file name as title, the banner "Running a large model with Phone A and Phone B.") and `docs/design/shots/memcloud-degraded-30b.png` (DEGRADED: "Phone B stopped answering", strip with B's layers hatched and "Phone B: 32-47 (lost)", the interrupted answer, "Re-plan with 3 phones", lane caption "this phone, driver").

### What looked wrong

- The DEGRADED screen says "Re-plan with 3 phones" two seconds after B was killed: the count uses the phones the registry still lists online, and B's heartbeat had not yet gone away. The button works either way (the re-plan used the three phones once B was back), but the label can overstate for a few seconds.
- The first start failed on a port that was held on A (above). The app's own retry logic did not clear it; the coordinator's teardown did, and the second start worked. Worth watching: if it recurs, a start should retry once on a "port in use" failure from a lender.
- `log.buffers` in the state is still empty (llama-server prints no per-device lines at default verbosity); the per-phone weights are evidenced by the bytes the lenders received.
- The lenders' rpc-server children run with `-t 8` and the driver's llama-server with `-t 8` here because all three apps were in front (8 allowed cores); a backgrounded app would get fewer (see the cpuset note above).
- Not measured: a 5-minute sustained generation (thermal stayed at 0 to 2 across about 15 minutes of load and answers, but each answer was under 40 s), a cold load on cleared caches, the 4B thirds run.

### Phones left as found

`POST /v1/memcloud/stop` (driver and lenders restored), Wi-Fi Direct group stopped on all three (there was none when I started), rpc-server stopped everywhere, the app in front on A, B and C, the default chat (Qwen3.5-4B) and embedding (EmbeddingGemma-300M) servers READY on all three (A and C had none running when I started, so I loaded them with `/v1/models/load`; B restored its own). No setting changed, no `pm clear`. The 30B copy stays on C.

"""
marker = "## Procedure used for the three-phone run"
s = s.replace(marker, three + marker, 1)
open(p, "w", encoding="utf-8").write(s)
print("ok")
