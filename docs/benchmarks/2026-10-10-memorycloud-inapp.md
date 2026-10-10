# MemoryCloud in the app: Qwen3.5-4B split over two phones (2026-10-10)

What this covers: the in-app mechanism (planner, rpc-server start and stop through signed routes, memory hygiene, llama-server with `--rpc`, streamed answer, DEGRADED and re-plan), measured with the 4B model over phone C (driver) and phone A (worker). **The 30B model was not run** and **no three-phone split was run** (phone B reserved, 30B does not load over two phones per `docs/benchmarks/2026-10-10-rpc-split-spike.md`). The three-phone 30B run is a ready-to-run procedure at the end, marked pending.

Splitting the 4B model is slower than running it alone (below). It is shown only to prove the mechanism; the app says so on screen when the model fits one phone (`MemCloudLogic.SPLIT_COSTS_SPEED`).

Build: base id `com.swarmos.app`, branch `worktree-agent-ac34ad0bc1b4ddd46`, llama.cpp v0.6.0 CPU build (rpc-server and the split llama-server use the CPU build; `RestartPolicy` and `BackendPlan` leave `--rpc` servers alone). Model: `Qwen3.5-4B-Q4_K_M.gguf` (2,740,937,888 B, pinned in `docs/models.md`), 32 layers, tied output head, from the GGUF header. Context 4096, 1 slot. Link: Wi-Fi Direct group hosted by A (`192.168.49.1`), chosen by the registry's path policy (Direct preferred). Ping C to A during a run: 5.9 ms Direct, 8.5 ms LAN (avg of 8). Heartbeat round trip in the app: 20 to 39 ms while generating.

## Trust model for rpc-server

llama.cpp's rpc-server has no authentication: whoever can open its port can read and write tensors on the phone. In this app (`memcloud/RpcLender.kt`, wired to the phone by `RpcWorker.kt`):

1. It starts only on a signed `POST /v1/memcloud/rpc/start` (HMAC with the pairing secret), i.e. from a phone that holds the secret; the sender node id is recorded and shown. One driver at a time holds the loan.
2. It binds to one address, the link address the driver reached the worker on (Direct group address when Direct is up, else the LAN address), and that address must be in the link module's advertisable set (`LinkRoute.advertisable()`: Wi-Fi, hotspot and Direct interfaces; cellular and every other interface are refused). The request is also refused for `0.0.0.0`, `::` and `127.x`. Measured on a phone (first pass): `-H 192.168.49.1` on A (rpc.log: `endpoint : 192.168.49.1:50052`).
3. **Lease**: the loan lasts `RpcLender.LEASE_MS` = 10 s and the driver renews it every 3 s (`POST /v1/memcloud/rpc/renew`, accepted only from the phone that started the loan). When the lease runs out the worker stops rpc-server and restarts its own chat and embedding servers from the list the driver sent with the start request, whatever the driver's heartbeat says (a driver that is online but idle, or dead, ends the loan the same way). The driver also stops renewing 60 s after DEGRADED, and `POST /rpc/stop` ends it at once when the split ends.
4. The file names in that restore list are reduced to a file name (`File(name).name`), must be `.gguf` and must exist in the models folder; anything else is dropped.
5. While it listens the Memory tab shows "Phone A is lending memory to Phone C on the direct link (192.168.49.1). It stops by itself 10 s after Phone C last renewed the loan." with a Stop lending button, and the phone-wide banner says "Lending memory to Phone C." (PhoneActivity owner `memcloud.lend`; the driver's line uses owner `memcloud.driver`, so neither clears the other's).

Residual risk: any phone on the same link that knows the port can still talk to rpc-server while it listens. The window is the length of a split plus at most 10 s after the driver goes quiet. **The lease, the renew route and the advertisable-address check are covered by unit tests with a fake clock and fake phones (`RpcLenderTest`, 11 tests) and were not run on a phone in the second pass (no phone use). The first-pass phone runs used the earlier heartbeat watchdog, which this lease replaces.**

## Exact arguments (from the state route `args` and `/proc/<pid>/cmdline`)

```
# worker A (started by the app): the shipped librpc-server.so
librpc-server.so -H 127.0.0.1 -p 50052 -H 192.168.49.1 -t 6 -c      # LlamaArgs defaults, then the last -H wins; -c = on-disk tensor cache in filesDir/llama-cache/rpc
# driver C (started by the app): libllama-server.so, CHAT role, port 8080 on 127.0.0.1
libllama-server.so -m .../models/Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -t 6 -c 4096 -np 1 --reasoning off --no-webui \
    --rpc 192.168.49.1:50052 -ngl 17 -t 6 -c 4096 -np 1 -fit off     # the extras are the planner's output; last flag wins
```

`-ngl 17`: the driver keeps layers 0-15, layers 16-31 and the output layer go to the worker (rule from the spike: layers `i >= n_layer + 1 - ngl`). `-t` is the number of cores the app may use when it forks the process (`CpuSet`, read from `/proc/self/status`): 6 here, because the app was not top-app and its children inherit cpuset `0-5`.

## Results, two phones, 4B

Start state recorded for each run; busy check `top -n 1` showed no other load; no standalone llama or rpc process on either phone (checked with `ps`, shell user). Thermal Status 0-1 is the gate; the cold-load run started at A=2.

| What | Result | Start state | End state |
|---|---|---|---|
| Load, rpc cache cold (A's `llama-cache/rpc` emptied: 65 files, 1363 MB), 05:13 | **28 s** to READY; A received 1640 MB over Direct (24 % at 15 s, 60 % at 22 s, 81 % at 30 s of wall) | A 2, C 0 | A 2, C 0 |
| Load, cache warm, second load right after the cold one, 05:15 | **12.0 s** | A 2, C 0 | |
| Load, cache warm, 05:09 and 05:13 (re-plan) | 11.3 s and 12.0 s | A 1, C 0 | |
| 200-token answer, 05:10 ("Explain how a heat pump works, in about 200 words") | TTFT **2.31 s**, decode **6.98 tok/s** (server timings, 200 tokens) | A 1, C 0 | A 2, C 0 |
| 200-token answer, same prompt, 05:15 | TTFT 2.59 s, decode **6.86 tok/s** | A 2, C 0 | A 2, C 0 |
| RSS during the run | C llama-server 2684-2713 MB (peak 3688-3769 MB); A rpc-server 1151-1202 MB (peak 1653-1657 MB), anonymous 1147-1198 MB; A tensor cache 1361 MB | | |
| Same model on C alone, measured by the app just before it stops the small server (48 tokens) | 14.69 tok/s (05:09), 10.80 tok/s (05:15) | C 0 | |

Reading: the split is about half the speed of one phone here (7 against 11 to 15 tok/s) and the native worker measured 3.48 tok/s through llama-server on this pair (`docs/benchmarks/2026-10-10-rpc-split-spike.md`: cold load 444 s on the LAN, warm 62 s). The faster loads here are over the Direct group and with the model already in the page cache of C, so they are not comparable to the spike's 444 s. Both runs of 200 tokens ran with A at Thermal Status 1 then 2 (heat showed within 30 s). Sustained 5-minute generation: **not measured**.

### A bug the first run exposed (kept for the record)

First run, 04:56, rpc-server with `-t 8`: READY in 40.3 s but **0.3 tok/s** (64 tokens in 3.5 min, TTFT 7.99 s). The worker's process was in cpuset `/background` with `Cpus_allowed_list: 0-5` (the link variant of the app held the foreground on A) and showed a new set of 8 threads per graph at 567 % CPU: llama.cpp's spin barriers with 8 threads on 6 cores. A child process keeps the cpuset its parent had when it was forked. Fix: `-t` = cores the app may use (`CpuSet.threads()`), and the run above (6.9 tok/s) is the same phones, same model, same link.

## Verification of the failure path

| Test | Result |
|---|---|
| Pull test, 05:11: answer streaming (7 tokens, TTFT 2.2 s), `adb shell am force-stop com.swarmos.app` on A at T0 (the command took 131 ms) | C reported **DEGRADED, lost phone "Phone A"** at **T0 + 1.2 s** (the poll at +0.5 s still said READY; poll step about 0.7 s). The stream ended with `EOFException`, llama-server was stopped, A's rpc-server was gone (`pidof` empty). One run, not repeated. |
| Re-plan, 05:12:59: A's app relaunched at 05:12:48, `POST /v1/memcloud/replan` | READY after **12.0 s** on the same two phones; same flags |
| Stop and restore: `POST /v1/memcloud/stop` | C's chat server (Qwen3.5-4B, 5.6 GB) and embedder (321 MB) stopped before the split and READY again after stop (`/v1/models/state`); A's rpc-server stopped |
| Baseline panel ("Try on this phone alone") | **Not run on a phone.** The 30B one-phone result is the spike's (no token in 134 s, 23 background apps killed on C); repeating it in the app would repeat that damage. The 4B small-model number above is the "for scale" line. |

Only with two phones the re-plan has no phone to leave out: it reloads on the returned phone. With three phones it drops the lost one (unit-tested: `MemCloudLogicTest`, planner for 2 and 3 peers).

## What the unit tests cover (443 tests in the app, 0 failures; 71 of them in memcloud)

GGUF header reader (a synthetic file; the real 30B header when present: 48 layers, 128 experts, 8 used; corrupt headers with absurd counts give a clean `GgufFormatException`, never an out-of-memory), planner for 2, 3 and 4 peers including flags vs llama.cpp's placement rule, untied head pinned to the driver, tied head counted on the remote phone (so received bytes are compared with planned bytes including the embedding copy), load-log parser, ExpertMesh arithmetic, DEGRADED and re-plan decisions for two and three phones, thread count, activity line.

`SplitCoordinatorTest` (14 tests) runs the whole coordinator against a fake phone: a successful split and stop; a failed rpc start on one of two peers; a llama-server that does not start; a cancelled load (by `stop()` and by cancelling the job); no peers; a lost phone and the 60 s renewal window; a re-plan that leaves the remaining worker running; the app restarting during a split (persisted restore list, peers, baseline); a killed baseline. Each failure path is asserted to send `/rpc/stop` to every touched peer, load back the servers of a peer that never got rpc-server running, restore this phone's chat and embedder exactly once, and clear the activity line. `RpcLenderTest` (11 tests) covers the lease, renewals, one driver at a time, address checks and restore-list sanitising.

## Not verified (plain list)

- 30B model: no run. It does not load on one phone or over two (spike). Three-phone run pending.
- Three-phone split: not run; planner and state machine are unit-tested for it.
- The screens were built and compile, but were **not inspected on a phone** (the link variant held the foreground on both phones, and nobody can touch the screens). Includes the on-screen "fits one phone, splitting is slower" sentence and the lending line.
- Per-device layer assignment from the llama-server log: the server prints no `load_tensors` lines at default verbosity (observed: 21 lines, none of them buffers), so the parser works on synthetic lines only and the state's `log.buffers` stays empty. The placement is evidenced indirectly: A received 1640 MB, which is its layers (1052 MB by the GGUF) plus a copy of the embedding table for the tied output head.
- The lease, `/rpc/renew`, the Stop lending button and the PhoneActivity banners: unit-tested only, never run on a phone.
- ExpertMesh screen: not shown on a phone (needs an MoE file in the app's models folder; the 30B file is not there).
- The ExpertMesh figures are **estimates of network wait only**, from the round trip of a TCP connect to the worker's rpc-server port (median of 3 connects, `AndroidSplitEnv.tcpRttMs`; one network round trip, unlike the HTTP heartbeat, which adds the server's handling). That measurement code was not run on a phone. The planning overhead (1.5 GB per phone for KV cache, buffers and the process) is a planning allowance, and the screen calls the resulting need an estimate.
- Thermal after 5 minutes of generation, RAM high-water for a model that does not fit: not measured.

## Pending, ready to run: three phones, Qwen3-30B-A3B-Instruct-2507-Q4_K_M (needs A, B and C)

Preconditions: all three phones at Thermal Status 0-1, no standalone llama or rpc process, the app in front on all three, about 11 GB free on each (the native worker's figure), the 30B file in C's models folder (`adb -s <C> shell cp /data/local/tmp/llama/models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf /sdcard/Android/data/com.swarmos.app/files/models/`), all three paired with the same secret. Calls are signed with `scripts/swarm-call.sh <ip> <METHOD> <path> [json]` (set `SWARM_SECRET` to the swarm's pairing secret). C drives:

```
C=192.168.242.76
swarm-call.sh $C GET  "/memcloud/plan?file=Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf&peers=Phone%20A,Phone%20B"   # shows layers 0-15 / 16-31 / 32-47 and the fit check per phone
swarm-call.sh $C POST /memcloud/start '{"file":"Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf","peers":["Phone A","Phone B"]}'
swarm-call.sh $C GET  /memcloud/state        # poll every 3 s until phase READY (loadMs) or FAILED; phones[] has per-phone receivedMb, cachedMb, rssMb, thermalStatus
swarm-call.sh $C POST /memcloud/ask '{"prompt":"Explain how a heat pump works, in about 200 words.","maxTokens":200}'
swarm-call.sh $C GET  /memcloud/state        # generation.ttftMs, decodeTokPerSec, tokens; record phones[].rssMb/peakMb and thermalStatus; poll again after 5 minutes of repeated asks for thermal
swarm-call.sh $C POST /memcloud/stop         # restores the small servers on all three phones
```

Expected flags from the planner (what to look for in `args`): `--rpc <A>:50052,<B>:50052 -ngl 33 -ts 15.5,17.5 -ot ^output\.weight=CPU -t <cores> -c 4096 -np 1 -fit off`. Driver C keeps layers 0-15, the embedding table and the lm_head (the 30B has an untied head, so only the 4 KB hidden state, not 608 KB of logits, returns per token). Record load time cold and warm (clear `files/llama-cache/rpc` on A and B for the cold one with `run-as com.swarmos.app`), time to first token, decode tok/s over 200 tokens, RSS and peak per phone, thermal after 5 minutes. Pull test: `adb -s <A or B> shell am force-stop com.swarmos.app` mid-answer, expect DEGRADED within a few seconds and `POST /memcloud/replan` to load on the remaining phone.
