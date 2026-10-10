# Ustaad (working name SwarmOS): what was built and measured, as of 2026-10-10 evening

Written from the repository documents, the scripts and `git log` only. No phone, build or test was run for this file, so every phone state below is "reported by a document", not verified. Sections 3 to 7, 9 and 10 were then brought up to date by the orchestrator with the runs of the same evening, which the last section lists. Unless a row says otherwise, a figure is one run on the debug build `com.swarmos.app`, on the iQOO phones, over the shared access point. docs/CLAIMS.md decides what may be said in public. Where two files disagree the newer one is used and the difference is named.

## 1. What it is and the fixed scope

- Ustaad is an Android app that makes two or three iQOO 15 phones on one local link work as one system, with no cloud model. It was built for the iQOO Hackathon 2026 finale, Bengaluru, 2026-10-09 to 2026-10-11.
- Swarm (SwarmLink plus the scheduler) is the coordination layer. On top sit Agent Choir, Private Data Union, MemoryCloud and ExpertMesh. A Knowledge Bank page hangs off the Data tab. The inference engine is llama.cpp; Ustaad is not a new model or engine.
- Scope lock (docs/PRD.md, CLAUDE.md): those modules on SwarmLink, nothing else. Blackout Cloud is a one-line footnote. No cloud calls except a model download. Kotlin and Jetpack Compose, minSdk 31, target 36. Native code only for llama.cpp.
- The founder reopened scope on 2026-10-10 for three items: the agent endpoint (gate 7), tool calling and file search for other apps (gate 8), and hardening plus a keep-busy mode (gate 9).
- Rules: every number shown to judges comes from a run on the phones and is logged in docs/benchmarks/. A gate without measured numbers does not close. Hardware: iQOO I2501, Snapdragon 8 Elite Gen 5, 16 GB, Adreno 840, Android 16, OriginOS 6.

## 2. How it is built

- Source: `app/app/src/main/java/com/swarmos/app/`. Packages: core (registry, health, audit log, signer, foreground service, ports), link (discovery, heartbeat, pairing, Wi-Fi Direct, Bluetooth, failover, dedup, throughput probe), llm (runtime, model store, benchmark), sched (scheduler, batch runner), choir, pdu (Data Union), kb (Knowledge Bank), memcloud, agent, busy (keep busy), shell (setup, settings), ui (the "Whiteboard" kit). Five tabs: Swarm, Models, Choir, Data, Memory.
- Ports (core/Ports.kt, docs/CONTRACTS.md section 5): 8787 node API (all interfaces), 8788 raw TCP throughput probe, 8790 agent endpoint (127.0.0.1 only), 8080 chat server and 8081 embedding server (127.0.0.1), 50052 rpc-server (link interface, on demand). `-PportOffset=N` shifts 8787, 8788 and 8790. The demo build uses the same ports, so only one build runs at a time.
- Runtime (gates/gate-2-inference-baseline.md): llama.cpp v0.6.0 (commit d812350) `llama-server` and `rpc-server`, shipped as executables named lib*.so and run as child processes, called over loopback HTTP. There is no JNI inference wrapper. Two builds: CPU (armv8.6 with i8mm) and OpenCL for the Adreno GPU. Chat runs on the GPU by default and falls back to the CPU.
- Chat arguments (docs/benchmarks/2026-10-10-inapp-llm.md, 2026-10-10-prompt-fixed-cost.md): `-ngl 99 -t 4 -c 8192 -np 2 --reasoning off --no-webui`, plus `-ctxcp 6 -cram 1024` (bounded prompt cache) and an OpenCL program cache folder. Scheduler capacity is 1 job per phone. With thinking on, Qwen3.5 spent 217 tokens (17 s) on a 5-token question (2026-10-10-llama-cpu-baseline.md), so thinking is off. The model servers need a random key made at each app start.
- Models (docs/models.md): Qwen3.5-4B Q4_K_M, 2,740,937,888 bytes (chat and all JSON output); EmbeddingGemma-300M Q8_0, 333,590,944 bytes (768 numbers cut to 256); Gemma 4 E4B (chat only: 7 of 10 schema outputs valid, 3 empty, 2026-10-10-inapp-llm.md); Qwen3-30B-A3B-Instruct-2507 Q4_K_M, 18,556,686,752 bytes (48 layers, 128 experts, 8 used per token). EmbeddingGemma 2 does not load on this llama.cpp. Models are pushed with adb; the in-app download has never run on a phone.
- Finding peers: the same Wi-Fi uses NSD `_swarmos._tcp` plus a signed health check. All three phones list each other 3.7 to 5.3 s after a cold start (gates/gate-1-swarmlink.md). Bulk and low-latency traffic use a Wi-Fi Direct group the app makes on 5 GHz, pinned to the access point's channel and joined by name and passphrase with no dialog. Bluetooth carries small signed control messages only. Path choice is by measurement; bulk uses the best single path.
- Pairing (docs/CONTRACTS.md sections 6 and 9): by QR, the `swarmos://pair` link or a pasted code; the owner confirms. Debug builds share a fixed development secret. The demo build makes a random secret per install.
- Trust on the wire (docs/benchmarks/2026-10-10-swarmlink-throughput.md): each request carries node id, timestamp and an HMAC-SHA256 signature; each answer carries its own HMAC. Each request has a unique id and the server keeps answers for 5 minutes (256 entries, 32 MB), so a retry never runs twice. Heartbeat is once a second. Traffic is signed, not encrypted; the Direct group key protects the radio link. rpc-server has no login, so it binds only to the link address, starts only on a signed request, and stops 10 s after the driver stops renewing its lease. The audit log is hash-chained, local and unsigned.
- Agent Choir: every phone answers the same problem; phones that disagree see the others' answers and revise (round 2, round 3 if needed); majority, then confidence. Answers are typed (number, option letter, short output), validated, retried once. VERIFIED means the problem's own test passed; CONSISTENT means only that the written arithmetic re-computes. One model on every phone, with different prompts and temperatures (0.3, 0.6). Deadline 60 s (docs/CLAIMS.md CH1).
- Data Union: per-phone SQLite with FTS5 and 256-number vectors, brute-force cosine, rank fusion, dense floor 0.40. Only the question text leaves a phone. A peer returns at most 5 snippets by default (1 to 10 allowed) of at most 400 characters. New folders are private until shared; a folder named Private is never shared. Hindi OCR runs as a second pass; dictation stays on the device.
- Knowledge Bank: a saved answer (card, at most 600 characters, its sources, content-hash id, HMAC). Shared cards replicate to online peers at once and every 3 minutes; revocation is a tombstone. MemoryCloud: a layer-range split over llama.cpp RPC; lenders run rpc-server, the driver runs llama-server with `--rpc`; a capacity tool, not a speed tool. ExpertMesh: an explanation screen over the same split of a mixture-of-experts model. Single experts were not placed on different phones, and its remote-experts figures are estimates.
- Hard facts: no Wi-Fi Aware service (the framework still lists the feature flag); no 6 GHz (country IN); no NNAPI; the NPU is not used; OriginOS freezes the app with the screen off, so Ustaad must stay in front. Heat is the main limit (section 5).

## 3. What each gate delivered

- G0 research and PRD (gates/gate-0-research-prd.md): scope locked; six research reports; llama.cpp chosen. Access point baseline 50 to 61 Mbps, 11 ms average round trip (docs/benchmarks/2026-10-09-swarmlink-lan-baseline.md).
- G1 SwarmLink (gates/gate-1-swarmlink.md, 2026-10-10-swarmlink-g1.md, 2026-10-10-swarmlink-throughput.md): the gate file reads Wi-Fi Direct 305 to 503 Mbps against 96 to 104 through the access point (A and B, 100 MiB). Phase 2 is newer and reads 640 to 920 Mbps on Direct against 89 to 102 on the access point (A and C, 200 MiB) once the group sits on the station channel. Round trip: TCP 6.3 to 8.3 ms on Direct; a signed request 20 to 28 ms. Failover: 700 sent, 700 ok, 700 executions, 2 path failovers. Away after 2.1 to 2.2 s, back in 1.0 s. The camera QR scan was never exercised.
- G2 inference (gates/gate-2-inference-baseline.md, 2026-10-10-inapp-llm.md): Qwen3.5-4B in the app. CPU decode 14.8 tok/s on B, 14.86 standalone on A. GPU prefill 201.9 against 71.6 tok/s in the app (one run each). OriginOS killed the app twice, at 6 min 18 s and 11 min 2 s, with the screen off while it generated.
- G3 Agent Choir (gates/gate-3-agent-choir.md, 2026-10-10-e2e2-choir.md): three phones, 20 problems, strict scorer. Pass 1: Choir 16 of 20 in 20.1 s per problem; one phone sampling three times 18 of 20 in 32.3 s; one phone one answer 14 of 20 in 11.4 s. Pass 2 (problem test on): Choir 14, with tool check 15, one answer 11. 40 of 40 Choir answers valid. The Choir is faster than the equal-compute baseline, not more accurate; one problem is 5 points. The gate file gives 14 of 20 for one answer; 2026-10-10-choir-answer-quality.md gives 15 of 20 (a different run).
- G4 Data Union (gates/gate-4-private-data-union.md, 2026-10-10-e2e2-data-union.md): the acceptance pair ran (bill photo on B, certificate on A). First-time questions, n = 9: first word median 4191 ms (1405 to 5774), total median 6585 ms (2737 to 12912); under 2 s in 2 of 9, under 6 s total in 4 of 9. Retrieval is small: embedding 23 to 50 ms, local search 1 to 7 ms, slowest peer 71 to 116 ms. Private files stayed private in every case tried. The second run checked 332 signed answers, 0 wrong.
- G5 MemoryCloud (gates/gate-5-memorycloud.md, 2026-10-10-memorycloud-inapp.md): the 18.6 GB model ran across three phones (C driver, A and B lenders; layers 0 to 15, 16 to 31, 32 to 47). Load 319 s cold and 65 s warm, first token 1.5 to 2.8 s, 5.7 to 6.4 tok/s, 5.2 to 5.3 GB still free per phone. A lender stopped mid-answer: DEGRADED named the phone after 1.8 s. One run.
- G6 polish (gates/gate-6-expertmesh-polish.md): OPEN. Build work is done: ExpertMesh screen; of 17 first-pass screen defects 10 fixed, 2 partly, 5 not checked; 8 more fixes seen fixed on Phone C. Final-build smoke (2026-10-10-final-build-smoke.md, one run): 32 of 32 jobs, 39.62 tok/s on three phones. The rehearsal list is for the founder (section 10).
- G7 agent endpoint (gates/gate-7-agent-endpoint.md, 2026-10-10-agent-endpoint.md) and G8 tools and file search (gates/gate-8-agent-tools-files.md, 2026-10-10-agent-tools-files.md): both closed 2026-10-10; see section 8.
- G9 hardening (gates/gate-9-hardening.md, docs/review/2026-10-10-adversarial-audit.md, 2026-10-10-hardening-checks.md): seven audits; 13 defects that could lose work, leak data or hang, and about 50 hardening items, fixed on master; unit tests 970 before, 1406 after. Keep-busy mode (section 7). Measured on phones B and C only.
- After gate 9 (docs/benchmarks/2026-10-10-evening-checks.md, 2026-10-10-prompt-fixed-cost.md, 2026-10-10-ngram-speculation.md): batch jobs 7.5 to 9.1 % faster, a 45-minute keep-busy soak, two further checks, one idea rejected (last section).
- Unit tests by gate: 618 (Choir), 699 (gate 6), 830 (gate 7), 970 (gate 8), 1406 (gate 9, b8b4777), 1424 (after gate 9, 7962ff1).

## 4. Efficiency gains with a measured before and after

Only pairs measured on the phones. Where the two sides came from different builds or different heat, the last columns say so. "Not controlled" means the file itself says the comparison is not controlled.

### In short

Most rows are one run per side. The table after this one has the phones, the number of runs and the source file for each.

| Area | Change | Before | After |
|---|---|---|---|
| One phone | Chat on the GPU instead of the CPU | reads prompts at 71.6 tok/s, first word 7.20 s, 5952 MB | 201.9 tok/s, 2.59 s, 1032 MB |
| One phone | Faster CPU build (fallback path) | decode 8.1 tok/s | 11.8 tok/s |
| One phone | Batch jobs skip a checkpoint they never reuse | 10.33 (B), 10.08 (C) tok/s | 11.11, 10.99 (about 8 % and 9 %) |
| One phone | Model's own tool-call format for agent loops | 13 to 15 s per loop | 9 to 10 s |
| One phone | GPU model start with a program cache | 17.9 to 20.6 s | 13.1 to 13.4 s rested, up to 16.5 s after repeated starts |
| Several phones | Three phones instead of the fastest one | 12.03 tok/s | 30.91 tok/s, mean of 5 runs |
| Several phones | Scheduler on two phones, share of their sum | 85 % | 99.6 % (heat not controlled) |
| Several phones | Agent Choir on three phones instead of one phone sampling three times | 32.3 s per problem, 18 of 20 right | 20.1 s, 16 of 20 right |
| Answers | Data Union first word on the GPU | 12.4 s and 11.8 s | 5.8 s and 2.6 s |
| Answers | Same question asked again (prompt cache) | 4.5 to 5.3 s | 0.3 to 0.55 s |
| Answers | Saved card instead of a live search | 5090 ms | 35 to 47 ms |
| Answers | Knowledge Bank pushes a new card to the other phones | not there after 48 s | 0.5 to 0.75 s |
| Answers | Leaner sources (a switch, off by default) | first word 5.09 s, whole answer 8.93 s | 2.71 s, 6.63 s, same accuracy |
| Link | Wi-Fi Direct on the access point's channel | 10.3 Mbps | 426 Mbps, later 650 to 960 |
| Link | Wi-Fi Direct instead of the access point | 89 to 102 Mbps | 641 to 725 Mbps |
| Big model | Second load of a split model (cached pieces) | 319 s for the 30B model | 65 s |
| Big model | Worker threads matched to usable cores | 0.3 tok/s | 6.9 to 7.0 tok/s |
| Big model | A model one phone cannot run | no word in 134 s, 23 apps killed | 5.7 to 6.4 tok/s across three phones |
| Safety | Memory check before loading | 134 s of thrashing | refused in 0.4 s with the numbers |
| Safety | Frozen model server | not noticed in 120 s | restarted within 30 s |
| Safety | Phone dies in the middle of a batch | not measured before | dropped after 2.3 s, 32 of 32 jobs finished |
| Keep busy | Filler work while the phone is otherwise idle | none | about half the time generating over two hours, 0 failed jobs |
| Keep busy | Refused MemoryCloud start | filler stopped for 36 minutes | 22 filler jobs in the next 60 s |

The last three rows are not in the table below. Their sources: 2026-10-10-evening-checks.md section 8 (phone dying in a batch) and section 9 (two hours of keep busy), and 2026-10-10-throttle-passive.md section 1 (refused MemoryCloud start).

Not a gain, a trade: a duty rule that keeps a warm phone out of its slow GPU throttle step gave real work 13.5 tok/s against 10.8 and filler 36 % of the time against 56 % (Phone B against Phone C, 50 minutes, 2026-10-10-throttle-passive.md section 7). It is not merged; the founder sets the level of usage himself with the Hammer button (2026-10-11-hammer.md). What was tried and rejected is in section 5.

### With runs and sources

| Change | Before | After | Phones and runs | Source |
|---|---|---|---|---|
| Chat on the GPU instead of the CPU build, in the app | prefill 71.6 tok/s, decode 16.47, first token 7.20 s, server memory 5952 MB | prefill 201.9, decode 17.31, first token 2.59 s, 1032 MB | Phone C, one run each, cool | 2026-10-10-inapp-llm.md |
| Same, standalone llama-bench | prefill 32.5 +- 0.8, decode 13.58, peak memory 5,385 MB | prefill 127.95 +- 2.3, decode 13.55, 1,177 MB | Phone C, 2 repetitions, cool start | 2026-10-10-throughput-tuning.md |
| CPU build with i8mm instead of the default Android build | prefill 10.4, decode 8.1 tok/s | prefill 38.2, decode 11.8 | Phone C, 6 threads, screen off, heat not recorded | 2026-10-10-llama-cpu-baseline.md |
| Three phones in the swarm instead of the fastest single phone | 12.03 tok/s (C alone; B 10.09, A 10.29) | mean 30.91 over 5 runs (28.90 to 31.94); two phones 18.83, one run | A, B, C; 32 short prompts; GPU; thermal 0 or 1 | 2026-10-10-e2e2-swarm-throughput.md |
| Same, first end-to-end run (CPU, superseded) | A alone 7.66 (7.87, 7.45) | A plus C 15.38 (20.45, 10.32) | 2 runs each; the two swarm runs differ by 2 times | 2026-10-10-e2e-swarm-throughput.md |
| Phone A on the GPU build instead of the CPU build | 7.66 tok/s | 10.29 | A alone; builds and heat differ, not controlled | 2026-10-10-e2e2-swarm-throughput.md item 4 |
| Data Union first word, GPU instead of CPU | electricity 12.4 s, salary 11.8 s | electricity 5.8 s, salary 2.6 s | first run A asking, second run B and C asking; one ask each | 2026-10-10-e2e-data-union.md, 2026-10-10-e2e2-data-union.md |
| Same question asked again (prompt cache) | first word 4473 ms (CPU run), 5336 ms (GPU run) | 486 and 295 ms (CPU run), 554 ms (GPU run) | first run A asking, second run B asking | docs/CLAIMS.md section 3 quoting e2e-data-union.md; e2e2-data-union.md |
| Saved card instead of live search, author's phone stopped | 5090 ms | 35 ms (second run), 40 and 47 ms (third run) | C asking, B stopped; one run each | 2026-10-10-e2e2-knowledge-bank.md, 2026-10-10-knowledge-bank.md |
| Knowledge Bank push after a shared save or revoke | card not on C after 48 s watched; revoke not after 60 s | listed on both other phones at the first poll: saves 497 to 754 ms, revokes 420 to 506 ms | second run two phones, third run three, no sync call | same two files |
| Typed Choir answers and strict checks | one phone alone 4 of 10; three samples 6 of 10 | 7 of 10 and 9 of 10 (all 20 problems, phones A and B: 15 and 18) | same 10 problems, one run; lenient then strict scorer; direction only | 2026-10-10-choir-answer-quality.md |
| Choir on three phones against one phone sampling three times | 32.3 s per problem, 18 of 20 | 20.1 s per problem, 16 of 20 | B coordinating, A and C; one run | 2026-10-10-e2e2-choir.md |
| Wi-Fi Direct group on the access point's channel | 10.3 Mbps (group on 5220 MHz); TCP echo average 37 to 60 ms | 426 Mbps (later 650 to 960); echo 6.3 to 8.3 ms (5765 MHz) | A and C; one 100 MiB run each | 2026-10-10-swarmlink-throughput.md sections 1, 2 |
| Wi-Fi Direct instead of the access point | 89.2 to 102.2 Mbps raw | 640.7 to 724.9 raw; 790 to 920 with 4 to 8 streams | A and C, 200 MiB; older A and B: 96.5 to 103.7 against 305.0 to 502.9 | swarmlink-throughput.md section 3; swarmlink-g1.md |
| Second load of a split with the rpc tensor cache | 4B in the app 28 s; 30B 319.2 s; native spike 153 s (1.57 GB sent) | 12.0 s; 65.3 s; 41 s (187 MB sent) | 4B: C driving A; 30B: C, A, B; spike: A and B | 2026-10-10-memorycloud-inapp.md, 2026-10-10-rpc-split-spike.md |
| rpc-server threads follow the cores the app may use | 0.3 tok/s (64 tokens in 3.5 min), first token 7.99 s | 6.98 and 6.86 tok/s, first token 2.31 and 2.59 s | C driving A, 4B; one run before, two after | 2026-10-10-memorycloud-inapp.md |
| Three phones for a model one phone cannot run | one phone: no token in 134 s, 23 background apps killed; two phones: did not load (0.47 of 9.3 GB in 8 min) | three phones: 5.7 to 6.4 tok/s, first token 1.5 to 2.8 s | C alone; C with A; C with A and B; one run each | gates/gate-5-memorycloud.md, 2026-10-10-rpc-split-spike.md |
| OpenCL program cache for GPU server start | 20.6, 17.9, 17.9 s | 13.4, 13.1, 13.1 s (later 12.1 and 13.1 s) | Phone C, 3 reloads each; later C and B | 2026-10-10-hardening-checks.md section 2 |
| Memory check before loading a model | 18.6 GB load on one phone: swap thrash, no token in 134 s, 23 apps killed | refused in 0.4 s with the numbers in the message | Phone C, one run each | rpc-split-spike.md; hardening-checks.md section 2 |
| Watch for a frozen model server | not noticed in 120 s (build 71e2839) | three missed pings in 30 s, then killed and restarted | Phone C, one run each | hardening-checks.md section 2 |
| Scheduler on two phones, share of the sum of each alone | 85 % (18.83 against 22.1, B and C) | 99.6 % (17.40 and 17.41 against 17.47) | B and C; one run before, two after; heat differs, not controlled | hardening-checks.md section 5 |
| Leaner sources in a Data Union answer (switch, off by default) | first token 5.09 s, whole answer 8.93 s, median | 2.71 s, 6.63 s; fact right 11 of 12 in both | B asking, C as peer; 12 asks per arm; lean arm ran hotter | hardening-checks.md section 6 |
| Model's own tool-call format for the agent loop | 13 to 15 s per loop (branch build, 12:54) | about 9 to 10 s (c2ca589) | Phone C, 3 loops | 2026-10-10-agent-tools-files.md |
| Batch jobs skip the checkpoint they never reuse | 10.33 tok/s on B, 10.08 on C; prompt time per job 562 and 667 ms | 11.11 and 10.99 tok/s; 398 and 400 ms | B and C each alone, 32 jobs, one run each, cool start; 3 of 32 answers reworded | 2026-10-10-evening-checks.md section 1 |

Not a pair, so not in the table: the three-phone batch on later builds read 39.62 tok/s (final-build smoke) and 39.8 (agent endpoint note), above the 28.90 to 31.94 of the second run, with decode 18.1 to 18.3 against 12.9 to 14.2 per phone. No file isolates the cause.

## 5. Ideas tried and rejected by measurement

- Two model slots per phone: 5.45 to 6.24 tok/s in total against 10.39 to 11.40 with one slot (Phone C, batch of 32, alternating order, 2026-10-10-slots-experiment.md). Native test: 12.4, 11.7, 11.7 tok/s for 1, 2, 4 slots (2026-10-10-throughput-tuning.md). Capacity stays 1.
- Five server settings (`-ub 1024`, int8 prefill kernel, split decode kernel, flash attention on and off): all within 2 % of baseline on Phone C. The third baseline, ten minutes in, was 18 % slower on prefill and 20 % slower on decode with nothing changed but heat (2026-10-10-gpu-settings-sweep.md; its thermal columns are blank).
- N-gram speculation without a draft model, Phone B GPU, 12 prompts (2026-10-10-ngram-speculation.md): the one setting that fired often (`ngram-simple` 4 and 8) cut decode by 25 to 43 % (Data Union 7.57 tok/s against 11.54 to 13.34; Choir 8.88 against 11.90 to 13.17). 29 % and 19 % of drafted tokens were accepted. It changed one answer in twelve. The default (`ngram-mod`) never fired. Left off.
- Removing the fixed cost of a short prompt, Phone C (2026-10-10-prompt-fixed-cost.md): `-ctxcp 0` cuts the fixed cost from 485 to 194 ms, but a repeat of a 202-token prompt then costs 1713 ms instead of 343, and an A, B, A pattern re-reads 209 tokens. `-ctxcp 1`, `-cram 0` and `cache_prompt` off do not remove it. Switching checkpoints off for everything was therefore rejected; skipping them only for one-shot batch jobs was built instead (section 4, last row). The checkpoint costs about 290 ms per request and pays only when more than one request in five repeats a prompt of that size.
- Partial GPU offload (`-ngl 16` of 33): prefill 82.2 and decode 7.48 tok/s against 127.95 and 13.55 for full offload (throughput-tuning.md).
- Using Wi-Fi and Wi-Fi Direct together: 280 to 465 Mbps against 762 to 959 for Direct alone, 0.3 to 0.5 times (swarmlink-throughput.md section 3). Wi-Fi low-latency lock: no consistent change in round trip. A keep-warm ping: no benefit, removed (same file, section 2).
- Splitting a model that fits one phone: 6.86 and 6.98 tok/s against 10.80 and 14.69 alone (memorycloud-inapp.md). MemoryCloud is for capacity only.
- KleidiAI (47.4 against 48.9 tok/s prefill without it), SVE2 (prefill 31.1 against 38.2, decode 11.8 for both) and pinning to the prime cores (10.2 against 11.7 tok/s at 6 threads) gave nothing (2026-10-10-llama-cpu-baseline.md).
- A second model family for Choir: Gemma 4 E4B produced 3 empty outputs in 10 schema calls (2026-10-10-inapp-llm.md). Choir uses one model.
- First tool-call design (our own constrained JSON for every call): it passed the 12-prompt test and failed the real loop. The model's own format gave 20 of 20 on the same prompts and replaced it for `tool_choice` "auto" (2026-10-10-agent-tools-files.md).
- Rejected by the reviewers' arithmetic, not by a phone run: compressing payloads, int8 vectors, approximate search, more parallel streams, a slower heartbeat (docs/review/2026-10-10-adversarial-audit.md item 26).
- The biggest effect of all is heat, not a setting: GPU decode 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10, with Thermal Status 4 at 590 s (llama-cpu-baseline.md). A 20-problem Choir run took B to status 3 in pass 1 and all three phones to status 2 in pass 2 (e2e2-choir.md, item 8); gate 3 says all three reached 2 to 3 in eight minutes.

## 6. Failure handling now

"Measured" means run on a phone. "Tests" means unit-tested only (gates/gate-9-hardening.md, hardening-checks.md, adversarial-audit.md).

- Phone leaves or freezes: the heartbeat timeout went from 1000 to 2500 ms and any verified request counts as a beat (tests; no real LAN stall was run). A force-stopped peer was marked away in 2.1 to 2.2 s (measured, older build). A lost reply is retried on the same phone first, then on the next path, with the same request id, so nothing runs twice (measured: 700 of 700). A stream that already sent text is not restarted elsewhere.
- A job runs long: the budget is 30 s plus max tokens at 5 per second, separate from liveness, so a healthy phone is no longer marked down (tests). One batch runs at a time (409). Measured on the new build (2026-10-10-evening-checks.md section 8): a peer killed mid-batch was dropped after 2.3 s and all 32 jobs still finished; a peer that joined mid-batch took 10 of 32 jobs once its model was loaded; a killed coordinator left a report marked "interrupted" and the next batch started at once. Nothing resumes an interrupted batch.
- Heat: routing penalises status 2 and excludes 4 with hysteresis (tests). Benchmarks and evaluations refuse to start on a hot phone. Keep busy pauses at 3 or more.
- Model server dies: it restarts by itself, READY again in 15.9 and 17.4 s (measured). Dies twice in two minutes: it runs on the CPU build with a notice, and the GPU switch restores it in 13.7 s (measured). Frozen: three missed pings in 30 s, killed, restarted (measured, second build). A stream that ends before its done marker is an error, not an answer. Killed mid-answer returned an error and the server was back in 16 s (measured). Commit 102dbd4 (16:08) words that error as "the model stopping mid-answer"; it has a new unit test and no phone run is recorded.
- Model will not fit: the load is refused before anything is stopped unless size times 1.2 plus 1.5 GB is free; 0.4 s with the numbers (measured). Low-memory reaction: the system signal sent by hand stopped the idle embedder within 4 s and the next question restarted it (measured, Phone B, 2026-10-10-evening-checks.md). Free-space check before a download: tests.
- Split (MemoryCloud): a lender stopped mid-answer shows DEGRADED naming the phone after 1.2 s (4B) and 1.77 s (30B), keeps the tokens, and a re-plan reloads in 12.0 s and 65.3 s (measured). Driver killed two seconds into a start: the lender had its own models back after 27 s (measured). A loan is reserved before models stop and carries an ownership token; a stale stop got 409, a request for 999999 MB got 400, a bad layer split got 400 (measured). A held port is retried (tests; the one port-in-use failure at the first 30B start was not reproduced). The 10 s lease: tests.
- Network: a Direct group removed from under the app was re-formed in 2.2 s from stored credentials; a new passphrase or no stored credentials took 4.2 s (measured). Bluetooth carried small signed requests with both Wi-Fi paths silent, 39 ms average (measured with a fault hook, not with Wi-Fi off). A phone that changes address reconnects on its first verified request. A changed node id replaces the old session. A protocol version field marks old builds "runs an older version". Two would-be hosts break the tie by node id. These four are tests only; none was measured, and Phone A is old.
- Data and features: folder privacy and revocation are single transactions with a repair at start (privacy measured: private again after a kill 0.18 s later). An ask has a deadline and Stop. An absent phone gives `incomplete` and the answer says "Phone A did not answer" (measured). A phone that drops mid-round leaves a Choir round at once and a failed run keeps its votes (tests; one live row ran on 2 of 3 phones). A failed audit write no longer breaks the chain. The placeholder model never answers another app (503).
- Not handled: a coordinator that dies mid-batch is not taken over. A start does not retry by itself when a lender reports its port in use after the retries. OriginOS kills the app with the screen off (the battery exemption is untested).

## 7. The keep-busy mode

- What: a Settings switch, off by default. An idle phone runs short filler jobs on its own model so it does not sit idle. Filler is Choir problems and benchmark prompts, "Answer in one short sentence.", temperature 0, at most 48 tokens, reply thrown away (busy/BusyPrompts.kt, BusyPolicy.kt).
- Rules (busy/BusyPolicy.kt): it starts after 5 s without real work. It waits 120 s after a chat server start and 300 s after one died under filler. It runs only with Ustaad in front, or charging with the battery exemption granted. Full duty at Thermal Status 0 or 1, half duty at 2, paused at 3 or more. Paused under 30 % battery when not charging. Real work cancels the filler job in flight. Filler never goes to another phone and is never counted in benchmark or scoreboard figures. The audit has `busy.on`, `busy.off` and a summary every 5 minutes, no entry per job.
- Measured (hardening-checks.md section 1; 16 minutes, charging, app in front): Phone B generated 63 % of the time (600 of 953 s; 220 jobs, 4722 tokens, 0 failed, 2 cancelled by real work). Phone C 84 % (796 of 952 s; 275 jobs, 5928 tokens, 0 failed, 3 cancelled). At status 0 or 1 it generated 99.5 % of the time; at 2 about half (49 to 51 %). Neither phone reached 3.
- Cost to a real request: time outside the model was 0.07 to 0.59 s with filler running (6 requests, final build) against 0.04 to 0.13 s with the mode off. 9 of 9 real requests were answered, each cancelling the filler in flight.
- Cost in heat: Phone C decoded 10.8 to 10.9 tok/s after 16 minutes of filler against 13.4 to 14.1 earlier in the day. Phone B sat at status 2 on half duty for the last 11 minutes. Leave it off before a timed demo run (gate 9).
- 45-minute soak (2026-10-10-evening-checks.md section 2): generating 49.0 % of the time on B and 57.8 % on C, Thermal Status 2 for 41.5 and 35.0 minutes, 0 failed jobs, 0 server restarts, chat server memory 1.1 to 1.6 GB without growth. Real requests during it: B 13.4 to 13.6 tok/s (13.9 cool), C 10.8 to 10.9 (13.6 cool). So over a long run the share is about half.
- Two-hour run (same file, section 9): the same half share with the app on screen, 0 failed jobs, 0 server restarts, memory under 1.9 GB. Phone C's screen was switched off by hand at 19:51 and the phone then did nothing for 61 minutes, no filler and no answers to other phones, until it was woken; it resumed by itself. Real work decodes at about 13.5 tok/s or about 10.8 at Thermal Status 2, on either phone.
- With the app in the background it pauses within 20 s and resumes when the app is back (measured, Phone C).
- Since 1ac22c5 the Settings sheet says under the Keep busy switch whether the mode can run with the screen off, and offers "Allow with the screen off", which opens the system's battery-exemption dialog (before, only the first-run setup screen could ask). Seen on Phone C; the dialog round trip was not exercised.
- Not measured: battery use, the battery exemption (not granted on B or C; whether it keeps the mode alive with the screen off is unknown), the pause at status 3.

## 8. The agent endpoint for other apps (docs/AGENT-API.md)

- Offers: an OpenAI-format server at `http://127.0.0.1:8790/v1` for apps on the same phone. `GET /v1/models` lists `ustaad-local` (this phone's chat model, one call at a time) and `ustaad-swarm` (the best free phone; the prompt then travels to paired phones). Chat with streaming and `json_schema`, embeddings (256 numbers), tool calling (`tools`, `tool_choice`, `tool_calls`; the calling app runs the tools), and `POST /v1/files/search` behind a second switch. At most 1024 tokens, one chat call at a time.
- Tool calls: with "auto" the model's own `<tools>` and `<tool_call>` format is used; each call is checked for valid JSON, a declared name and required keys, and repaired once under a schema constraint. "required" and a named tool are schema-constrained.
- File search: scope "phone" (default) or "swarm". An answer holds phone, title, date, kind and a snippet of at most 400 characters. It returns exactly what a paired phone would get; files in private folders never appear, not even as a count. No score, path, hash or file bytes.
- Protection: off by default. Switching it on makes a key (32 random bytes) and opens the port; switching it off closes the port and forgets the key; "New key" cancels the old one. It listens on 127.0.0.1 only and refuses other source addresses and unexpected Host headers. The Bearer key is compared in constant time, there are no CORS headers, and every route asks for the key first. Limits: 429 on overload, 1 MiB body, 16 KiB schema, 30 s write timeout, 300 s idle timeout. The model servers on 8080 and 8081 now need their own key. Audit entries hold counts, never prompt, answer, query or snippet text. The demo build ignores the debug intent extras that can switch it on.
- Measured (agent-endpoint.md, agent-tools-files.md; Phone C, one run each): no key 401, wrong key 401, right key 200; refused from the Wi-Fi; another UID on the phone was answered; streamed first token 0.39 s; JSON to a schema 2.77 s; embeddings 0.09 s for two; the unmodified OpenAI Python client worked; same speed for 3 minutes in the background (first token 0.34 to 0.38 s). Tools: right tool 8 of 8, right argument 8 of 8, direct answer 4 of 4 on 12 prompts, mean 2.5 s. File search 0.11 s on the phone, 0.19 s across three; a private salary slip was never returned; the whole loop was correct 3 of 3 in about 9 to 10 s.
- Limits: a key holder can read every file the owner shares with the swarm, as often as it likes, and a paired phone's owner cannot tell it from a person's question. No third-party Android app has called it. In debug builds any app on the phone can switch it on through intent extras. The repair path and the 429 limits never fired on a phone.

## 9. State right now

- Code: master is 7962ff1 plus documents. Unit run at 16:49: 1424 tests, 0 failures; debug and demo APKs built. Everything is committed except the founder's `brag-output/` folder.
- Phone A (192.168.242.89): reserved by the founder since 13:12; released at 15:54 and held back minutes later, before anything ran on it (CLAUDE.md). It carries the older build c2ca589, installed 13:07, so B and C show it as "runs an older version". Nothing was sent to it in gate 9. From 17:30 it showed a vivo Remote Control session into a computer, and at the founder's request random taps were injected on its screen for 120 minutes; nothing else was done on it.
- Phones B (192.168.242.23) and C (192.168.242.76): debug build of 7962ff1 installed 17:31 and 17:32, then rebuilt with the app icon (10c7145: the letter U as one ink stroke joining three phone tokens) and installed on both at 19:11, then with a Settings row for the battery exemption (1ac22c5, 1425 unit tests) at 21:02. Verified at 18:28, 19:12 and 21:03: chat READY on the GPU, embedder READY, keep busy ON, agent access off. Installed on Phone B at 22:51 and Phone C at 22:52, when the founder said so: the pairing message (b975f55) and the keep-busy fix (1b9d60b). The fix was checked on Phone C at 22:55: a refused MemoryCloud start left the phase FAILED and the filler ran on, 22 jobs in the next 60 s. Phone B also holds 5.2 MB of OpenCL cache under `/data/local/tmp/llama.cpp/cl-cache/`, left by an experiment.
- Models: Qwen3.5-4B and the embedder on all three. Phone C holds two copies of the 30B file (37 GB). Lender caches reported: A 6792 MB, B 5679 MB. The demo build `com.ustaad.app` is not installed on any phone.
- Network: PC and phones on the access point "Chulbul" (192.168.242.x).

| Switch | Default | Where |
|---|---|---|
| Use GPU for chat | on | Models tab; `POST /v1/models/gpu` |
| Load models at start | on | Settings |
| Agent access | off | Settings |
| File search for other apps | off; works only while Agent access is on | Settings |
| Keep busy | off | Settings; debug extra `keepBusy` |
| Lean sources (fewer sources in a Data Union answer) | off; no Settings row | debug extra `leanSources`; stored `pdu_lean_sources` |
| Choir skips round 2 on a two-of-three majority | off; no Settings row | debug extra `choirMajority`; stored `choir_majority_skips_round2` |

The last two stay off until three phones can judge them (gate 9). Debug builds share a fixed development secret; the demo build makes a random one. HANDOFF.md's phone table still says the 10:27 build is on A, B and C; the newer facts above come from BUILD.md and gate 9.

## 10. What is open or not proven, most important first

1. Phone A is on the older build and reserved. Until it is updated: no versioned signature (a captured request can be replayed within 30 s on a shared Wi-Fi), no Direct tie-break with it, none of the gate 9 fixes on it.
2. Everything that needs the third phone (Phone A): the 30B split on the current build (no repeat of the 319 s cold load, nothing sustained beyond 40 s an answer, nothing at status 2 or above); Choir and three-phone throughput on the current build; judging the two switches that are off; the Bluetooth fixes; a Knowledge Bank phone that is away at save time; the 3-minute timer.
3. The demo build (what judges see) has never run end to end on a phone: no pairing, Choir run, split, swarm batch or Data Union ask on `com.ustaad.app`. The three curl checks in docs/BUILD.md have no recorded result.
4. Only the founder can do, on every phone: first-run setup taps (nearby devices, notifications, battery exemption, "Allow background start"); QR pairing by camera (never exercised); indexing folders with the picker; scanning a paper document; dictation; tapping a citation; airplane mode with the Direct group; a screen-off soak; one timed rehearsal; Office Kit mirroring. Decisions: hackathon track, closing ask, stage rules for three phones and a laptop, and whether to file anything before further public disclosure (India has no general grace period; not legal advice, docs/research/07).
5. Heat: sustained work takes phones to status 2 to 3 in minutes, and keep busy holds status 2. Demo segments must stay short, phones cool, Ustaad in front.
6. Everything is one run on the debug build with the development secret. The 30B result is one run. No Wi-Fi-off failover run. No byte count to the internet.
7. Data Union misses its targets (2 of 9 under 2 s to first word, 4 of 9 under 6 s total). The relevance margin is 0.014. One Hindi answer was partly wrong. Unrelated sources can still be listed.
8. Choir is not more accurate than three samples on one phone. The JSON-fragment rejection and rounding (08a205f) are unit-tested only. Problem m2 was wrong in both Choir passes.
9. Unexplained: decode alternating between about 14 and 18 tok/s on Phone C with no change (agent-endpoint.md); the signed API on 8787 stopped answering several times on B while a hand-started server ran (ngram-speculation.md); first word 5.2 to 5.8 s against 556 ms for the same question earlier (knowledge-bank.md, third run); Phone B restarted at 14:16 (hardening-checks.md); Phone A once left the access point by itself (e2e-choir.md F5); a chat server kill on B at status 3; other real work ran on Phone B from 17:26 to 17:29 that the check scripts did not send (evening-checks section 2).
10. Agent endpoint: no third-party app has called it; background use beyond 3 minutes, on battery or with the screen off is not measured.
11. Left undone: two thermal decision functions still exist. A short batch job still spends about 0.4 s of prompt time inside the model server; a prefill pass of a few tokens costs 250 to 300 ms and the cause is not identified (prompt-fixed-cost note). Both phones show a fast level (about 13.5 tok/s) and a slow level (about 10.8) inside Thermal Status 2; not explained. With the screen off a phone drops out of the swarm entirely (evening-checks section 9).
12. Documents out of date: docs/PRD.md sections 4, 5 and 9 against its section 12; README.md "Start here" points at gate 5; HANDOFF.md phone table; docs/models.md and docs/PRD.md R5.3 on the 30B run.

## 11. Build and run the checks

```
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug        # com.swarmos.app; demo: :app:assembleDemo
bash scripts/gradle-locked.sh "$PWD/app" :app:testDebugUnitTest    # 1424 tests at 7962ff1
$ADB -s 192.168.242.76:5555 install -r app/app/build/outputs/apk/debug/app-debug.apk
$ADB -s 192.168.242.76:5555 shell am start -n com.swarmos.app/.MainActivity --es name 'Phone C' --ez setupDone true --ez autoload true
bash scripts/adb-connect.sh                                        # reconnect wireless adb
bash scripts/swarm-call.sh 192.168.242.76 GET /link/peers          # signed call; set SWARM_SECRET if not the dev secret
```

Needs JDK 17, NDK 28.2 and `app/local.properties` with `sdk.dir`. Do not use Phone A while it is reserved. Install steps for the demo build are in docs/BUILD.md. Each check writes raw results to its out-dir; keep them under docs/benchmarks/raw/.

```
bash scripts/e2e2-check.sh [--steps d,p|k|t,t2|c] <asker-ip> <peer-ip> [third-ip]   # Data Union, Knowledge Bank, throughput, Choir
python scripts/bench-batch.py <ip> <local|swarm> <tag> <out-dir>                      # bench32 batch, one line
python scripts/phone-probe.py <ip> state|reload <role>|busy [s]|get <path>|post <path> <json>
python scripts/hardening-check.py <ip> <out-dir> <g|m|t|h|c>                          # GPU start, memory, cut stream, hung server, GPU deaths
python scripts/memcloud-check.py <driver-ip> <lender-ip> <lender-name> <out-dir> <v|n|s|k>
python scripts/privacy-check.py <owner-ip> <peer-ip> <out-dir>
python scripts/agent-check.py <adb-serial> <phone-wifi-ip> <key> <out-dir> [a-j]      # endpoint v1
python scripts/agent-check2.py <adb-serial> <key> <out-dir> <t|u|v|w|p|x|o>          # tools and file search
python scripts/lean-sources-ab.py <asker-ip> <out-dir>
python scripts/gpu-bench-sweep.py <ip> <out-dir>
python scripts/prompt-cost-probe.py <ip> <out-dir> [variants]
python scripts/spec-probe.py <ip> <out-dir> ...
python scripts/make-demo-corpus.py [--push-only]
bash scripts/probe-device.sh <serial>
```

The three-phone 30B run is a procedure in docs/benchmarks/2026-10-10-memorycloud-inapp.md (start, ask, replan, stop through signed calls to `/memcloud/*`). Native rebuild: `native/build-llama-android.sh`, `native/fetch-models.sh`, `native/bench/`.

## 12. Index of documents

- README.md: overview, repository map, status by gate (its gate table stops at 6).
- HANDOFF.md: gate verdicts, open items, founder tasks, contradictions (its lower parts are older than its top paragraph).
- CLAUDE.md: scope, devices, hard facts, gates, rules.
- docs/SUMMARY.md: this file.
- docs/PRD.md: requirements; section 12 overrides earlier sections. docs/CONTRACTS.md: ports, node API, module contracts, addenda.
- docs/CLAIMS.md: claims against evidence, binding (118 rows). docs/models.md: pinned model files and hashes. docs/llama-server-api-notes.md: working server command lines.
- docs/BUILD.md: build types, install, checks. docs/SETUP.md: clean phone to ready swarm. docs/DEMO.md: 6-minute and 90-second script. docs/PITCH.md: pitch and limits. docs/DECK.md: slide outline. docs/AGENT-API.md: the endpoint for other apps.
- gates/gate-0 to gate-9: one artifact per gate (decisions, deltas, open risks, ledger). The newest is gate-9-hardening.md.
- plans/: 2026-10-09-build-plan.md, 2026-10-10-integration.md, 2026-10-10-agent-endpoint.md, 2026-10-10-agent-tools-files.md, 2026-10-10-hardening-audit.md (with the 16:00 continuation). plans/briefs/: one brief per worker.
- docs/review/: 2026-10-10-merged-app-review.md and 2026-10-10-second-review.md (code reviews), 2026-10-10-adversarial-audit.md (gate 9 triage), 2026-10-10-codex-audit.txt (raw text of one audit).
- docs/research/01 to 08: stack, networking, distributed inference, Data Union, Choir, hackathon context, novelty (07), applications (08). docs/device-probes/: raw adb probes of A, B, C. docs/demo-corpus/: synthetic files for Data Union. docs/design/: DESIGN.md, stitch-prompts.md, screenshots in shots/ and qa/ to qa4/.
- docs/benchmarks (all dated 2026-10-10 unless shown): 2026-10-09-swarmlink-lan-baseline.md access point baseline; swarmlink-g1.md link probe and soak; swarmlink-throughput.md channel finding, round trips, striping, failover, Bluetooth.
- llama-cpu-baseline.md and throughput-tuning.md: CPU and GPU standalone, threads, slots, the 10-minute run. inapp-llm.md: the runtime in the app, GPU against CPU, restart, OS kills.
- choir-answer-quality.md (and its -rows.json): why answers were degenerate, and the fix. e2e-choir.md, e2e-data-union.md, e2e-swarm-throughput.md: first end-to-end run, superseded. e2e2-swarm-throughput.md, e2e2-choir.md, e2e2-data-union.md, e2e2-knowledge-bank.md: second run on three phones.
- knowledge-bank.md: design, procedure, third run. memorycloud-inapp.md and rpc-split-spike.md: the split, 4B and 30B. final-build-smoke.md: smoke test of 0a71f33. agent-endpoint.md and agent-tools-files.md: gates 7 and 8.
- hardening-checks.md, slots-experiment.md, gpu-settings-sweep.md: gate 9. evening-checks.md, prompt-fixed-cost.md and ngram-speculation.md: after gate 9. raw/: raw results (a README is inside). Three PNG files hold early Choir frames.
- scripts/: adb helpers, signed API caller, the check scripts of section 11, demo corpus, tput/ throughput probe. native/: llama.cpp build, patches, prebuilt arm64 executables, benchmark scripts. ui-kit/: Stitch export of 21 screens (reference, not code). A hidden folder at the root holds the worker definitions.

## After gate 9 (continuation, 2026-10-10 evening)

Runs on phones B and C between 16:00 and 18:30. Evidence: docs/benchmarks/2026-10-10-evening-checks.md, 2026-10-10-prompt-fixed-cost.md, 2026-10-10-ngram-speculation.md, and the addendum at the end of gates/gate-9-hardening.md. One run per figure unless a count is given.

- Batch jobs are faster. The model server read every prompt in two passes with a checkpoint between them, about 0.29 s per request; batch jobs never reuse that checkpoint, so they now skip it. The 32-job batch went from 10.33 to 11.11 tok/s on Phone B and from 10.08 to 10.99 on Phone C. 3 of the 32 answers are worded differently on the new build, with the same facts. Every other caller is untouched.
- Rejected: speculative decoding without a second model (25 to 43 % slower where it fired) and checkpoints off for every request (a repeated prompt five times slower).
- Keep busy over 45 minutes: generating 49.0 % of the time on B and 57.8 % on C, 0 failed jobs, 0 server restarts, memory flat. Real work during it ran at 13.4 to 13.6 tok/s on B (13.9 cool) and 10.8 to 10.9 on C (13.6 cool).
- First phone checks of three gate 9 fixes: the low-memory signal stops the idle embedder and the next question restarts it; keep busy pauses within 20 s in the background; an answer cut by a dying server now reads "the model stopped mid-answer".
- Regression on the new build: fault injection (5 of 5), MemoryCloud with two phones (4 of 4), folder privacy, the agent endpoint (7 of 7) with tools and file search, and the Knowledge Bank between B and C (card listed on C after 0.31 s, revocation after 0.32 s) all pass. The Data Union script cannot give a clean verdict while Phone A is missing, because it counts an absent paired phone as a failure.
- A batch across phones survived a peer dying, a peer joining and the coordinator dying with no failed job (section 6). During those runs Phone A's app came online by itself and Phone B's scheduler gave it 29 jobs; three phones on mixed builds ran the 32-job batch at 28.81 and 29.01 tok/s.
- GPU model start reads 12.7 to 16.5 s depending on how many starts came just before, on the old build and the new build alike; "13 s" is the rested-phone figure.
- Pairing, about 21:10 to 21:20: after the two phones were paired again with a code made on the phones, Phone C said the secret was adopted and the host not reachable. Phone C was on another Wi-Fi network (its address 192.168.113.47, Phone B 192.168.242.23). After the founder moved Phone C to the same network each phone listed the other over the LAN, and a 32-job batch across both finished 32 of 32 at 19.42 tok/s (raw/gate-9b/repair/batch-after-repair.json). The message now names the case: another network, no Wi-Fi address, or same network (b975f55, 1430 unit tests). Phones B and C no longer use the development secret; scripts read the secret from the phone and keep it in memory.
- Read-only watch of both phones, 21:21 to 22:01 (2026-10-10-throttle-passive.md): Phone C ran no filler from 21:35 to the end, 26 minutes and more, cool and with its model loaded. A MemoryCloud start had been refused there (correctly: two phones do not have the memory for the 30B model), and keep busy counted the resulting FAILED phase as work in progress. Fixed in 1b9d60b (1431 unit tests, 0 failures). Phone B generated 45.6 % of the window, 406 jobs, 0 failed. The thermal report also shows a cooling step (gpu / cpu-cluster0 / cpu-cluster1: 4/7/14, 5/10/15, 7/13/17). Both phones sat at 7/13/17 above 40 C skin temperature; the one real request known there decoded at 10.94 tok/s and the one known at 5/10/15 at 13.43. That fits the slow level being the top step, and it is one reading on each side, not proof.
- A second window (22:06 to 22:34, same note, section 5) settled the two decode levels inside Thermal Status 2: they follow the `gpu` cooling step of the thermal report. Phone C at Thermal Status 2 decoded real requests at 13.3 to 13.4 tok/s at step 5 and at 10.7 to 11.3 at step 7; Phone B sat at step 7 for all 73 minutes watched, 10.8 to 11.0 tok/s. A cold phone (step 0) decoded at 18.2 to 18.3. The present duty rule cannot see the step; a rule that can is written on a branch (f3cd864, 1448 unit tests) and waits for a before and after on a phone. Phone C's stuck state was cleared at 22:11:43 with the app's own MemoryCloud stop call, without a restart, and its filler ran again within 10 s.
- Installed on Phone B at 22:51 and Phone C at 22:52, when the founder said so: the pairing message (b975f55) and the keep-busy fix (1b9d60b). The fix was checked on Phone C at 22:55: a refused MemoryCloud start left the phase FAILED and the filler ran on, 22 jobs in the next 60 s.
- The duty rule that reads the GPU throttle step was tried on Phone B for 50 minutes against Phone C on master (2026-10-10-throttle-passive.md section 7): real requests decoded at 13.5 tok/s against 10.8 and Phone B never entered the slow step, but it generated filler 36.4 % of the time against 56.3 % and made 75 % of Phone C's filler tokens. Not merged: the founder decided the app should not trade usage for speed by itself.
- Hammer (2026-10-11-hammer.md, merge f62da67, 1453 unit tests): a button at the top and at the bottom of Settings that keeps filler at full duty on a warm phone. It is per phone, off at every start of the app, still gives way to real work and still pauses while the phone is too hot or the battery is low, and when it is stopped the phone rests 90 seconds before normal keep busy continues. Installed on Phone B at 00:06 and Phone C at 00:07 on 2026-10-11. On Phone B: both buttons seen, on gave full duty also at Thermal Status 2 (94 to 98 % of the time generating, where the normal rule had gone to half), off gave the 90 s rest and then normal work. Not measured for longer than a minute.
- Unit tests: 1453, 0 failures (master f62da67, 00:05 on 2026-10-11).
- Phone A: untouched by any of this. At the founder's request random screen input was injected on it (drags and letters from 17:17 to 17:21, then one tap every 1 to 20 seconds from 17:30 for 120 minutes, and again from 19:58 to 21:58). It was showing a vivo Remote Control session, so that input went to a computer.
- Still open after tonight: everything in section 10 that needs Phone A; the cause of other work seen on Phone B's model at 17:26; Hammer for longer than a minute (share, heat, pauses, real decode); why a restart of the app resets the throttle step; keep busy with the screen off.
