# Adversarial audit of master c2ca589 (2026-10-10, 13:25 to 13:45)

Seven read-only audits ran in parallel: Codex (requested model gpt-6.1-sol at high effort; it identified itself as "GPT-6 (Codex)", 88 files read, raw output in `2026-10-10-codex-audit.txt`) and six Sonnet reviewers, one each for scheduling and throughput, resources, link and protocol, feature failure handling, data path and compression, and inference. None ran a build or touched a phone. This file is the orchestrator's triage. "Fix" means a builder has it on a branch for gate 9; nothing here is fixed on master until gates/gate-9-hardening.md says so. Line numbers are as reported against c2ca589.

No audit found a defect that breaks the measured demo path in normal use. The findings are what happens when a phone dies, freezes, overheats or loses the network at a bad moment, plus where time and memory go.

## P0: can lose or corrupt work, leak data, or hang
| # | Where | What | Found by | Decision |
|---|---|---|---|---|
| 1 | llm/LlamaServerClient.kt:63-77 | A model stream that ends before its done marker is reported as a complete answer; a truncated answer can be shown as final and stored | Codex | Fix (runtime builder) |
| 2 | pdu/PduStore.kt:136, 227 | Making a folder private is two writes; a kill between them leaves snippets served to peers from a folder its owner sees as private | Codex | Fix first (data builder), with a repair at start |
| 3 | sched/RemoteLlmClient.kt:50-53, SwarmLlm.kt:72 | A swarm job longer than 60 s (remote) or 75 s is cancelled, re-run on every other phone, and the healthy phone is marked down; 1024 tokens need 77 to 150 s | Codex, scheduling, link | Fix: budget 30 s + maxTokens / 5 per second, separate from liveness |
| 4 | memcloud/SplitCoordinator.kt:393-398 | The driver stops a lender's models before the lease exists; a driver lost in between leaves that phone with no models for good | Codex, features | Fix (MemoryCloud builder) |
| 5 | memcloud/RpcLender.kt:92, MemCloudModule.kt:48 | Two drivers can both be granted a loan; a stale stop from an old driver kills a new loan | Codex | Fix: reserve before suspending, generation token |
| 6 | pdu/PduEngine.kt:600 | An ask has no overall deadline and no Stop; a frozen model server holds the only chat slot | features | Fix |
| 7 | kb/KbEngine.kt:141, pdu/PduEngine.kt:169-193 | Background loops with no exception handler: an I/O or database error can end the process | features | Fix |
| 8 | kb/KbService.kt:131, 221 | Revoke is two writes; a kill between them means the revocation never spreads, or a receiver keeps a usable card | Codex, features | Fix: one transaction, repair at start |
| 9 | llm/LlamaRuntime.kt:293-297 | No memory check before a load; loading the 18.5 GB model on one phone thrashes swap and kills other apps | resources, Codex | Fix: refuse unless free memory covers size x 1.2 + 1.5 GB; the split and the deliberate "does not fit" demonstration stay possible |
| 10 | sched/SwarmLlm.kt:269, 383 | Two dispatchers waiting for a slot wake each other in a busy loop (inferred, not measured) | scheduling | Fix, with a wake-up count test |
| 11 | link/PeerLinks.kt:59, Heartbeat.kt:46 | Heartbeat timeout of 1000 ms against measured idle-LAN stalls of 1035 to 2080 ms: a stall marks a healthy phone away and degrades a running split | link | Fix: 2500 ms, and any verified inbound request counts as a beat |
| 12 | link/NodeClient.kt:76 | A peer reachable only over Bluetooth gets a malformed address and the call fails | Codex | Fix |
| 13 | link/Ble.kt:388, BleProtocol.kt:42 | The Bluetooth handshake is tried once; a lost fragment desynchronises the channel for good. Likely causes of the unexplained flapping | link | Fix: retry, length bound, drop and re-dial |

## P1: throughput, latency, memory (each needs a phone measurement before it becomes a default)
| # | Change | Expected | Risk | Decision |
|---|---|---|---|---|
| 14 | Two model slots per phone | none, by the earlier native test | throughput halves | Rejected by measurement: on Phone C a 32-job batch ran at 10.4 to 11.4 tok/s with one slot and 5.5 to 6.2 with two (docs/benchmarks/2026-10-10-slots-experiment.md) |
| 15 | OpenCL program cache for the GPU server (`GGML_OPENCL_KERNEL_CACHE_DIR`) | seconds off each GPU start (13 to 24 s today) | low | Build, then measure start time |
| 16 | Bound the model server's checkpoints and host prompt cache (`-ctxcp 6 -cram 1024`) | chat server memory stays near 1 GB instead of growing to 3 to 3.9 GB | repeat-question cache hit must survive | Build, then measure memory and the repeat ask |
| 17 | Data Union gives the model fewer sources (score gate, cap 4) | median first token 4.2 s to about 3.0 s (717 ms per source dropped) | a needed second file dropped | Build behind a switch, off by default; measure with three phones |
| 18 | Choir skips round 2 when two of three agree | round 2 is about half the wall-clock of a two-round problem | changes the debate; accuracy unknown | Build behind a switch, off by default; measure with three phones |
| 19 | Dispatch on cached capabilities, not under a lock held across network calls | removes 1.5 to 3 s stalls when one peer blips | low | Fix |
| 20 | Worker count follows the live set of phones during a batch | a phone that joins mid-batch contributes | low | Fix |
| 21 | Heat-aware routing: mild penalty at status 2, exclude at 4, hysteresis | heat moved decode from 18 to 13 tok/s between runs, more than any scheduling change | none | Fix |
| 22 | Query-vector cache, Knowledge Bank cache in memory, cheaper term counting, OCR pipelined with embedding, caret redraw without recomposition | 23 to 50 ms per ask; indexing about 40 % faster; less CPU while streaming | stale cache if invalidation is missed | Fix (tests on invalidation) |
| 23 | Prompt-cache reuse made visible (`cache_n`), per-item prompt and wait times in batch results | nothing by itself; it explains the 25 % of wall-clock outside decoding | none | Fix |
| 24 | Micro-batch 1024, the int8 prefill kernel, n-gram speculation, multi-token-prediction head, a Q4_0 file | unmeasured | new code paths; the last two need a download | Experiments only, through a debug-only way to pass arguments and environment; no default changes |
| 25 | Lender layer counts weighted by link speed | cold load of the split 319 s to about 290 s | lender memory margin | Not now: needs a three-phone run |
| 26 | Compression of payloads between phones, int8 vectors, approximate search, more parallel streams, a slower heartbeat | none or negative here | several would hurt | Rejected, with the reviewer's arithmetic: hot payloads are 0.3 to 2.7 KB against 40 to 60 ms round trips; search already takes 1 to 7 ms; multipath measured 0.3 to 0.5 times one path |

## P2: hardening
Resources: GPU server that dies twice falls back to CPU; a hung model server is detected by a health ping; CPU thread count follows the cores the app may use; internal callers never receive the placeholder model's text; wake and Wi-Fi locks follow "work running" and time out; a reaction to system low-memory signals; free-space check before a download; a GPU failure is remembered; file and /proc reads off the main thread. All: fix (runtime builder).
Scheduler: a stream that already delivered text is not restarted on another phone; one batch at a time (409), finished reports only are evicted, a batch report survives an app restart as "interrupted"; a retry asks the same phone first so a lost reply does not cause a second run; cancellation is not counted as a missed ping; dispatch has an overall deadline. All: fix (scheduler builder).
Link: a phone that changes address reconnects on its first verified request; a changed node id replaces the old session instead of sitting beside it; a protocol version field and a separate "incompatible" state; the Direct group join loop stops when the host is not hosting; two would-be hosts break the tie by node id; the heartbeat pause during probes is capped; duplicate requests wait at most 30 s; loops survive I/O errors; stop really stops. All: fix (link builder).
MemoryCloud: unconfirmed restores are retried and shown; a held port is retried; a plan that does not fit is refused unless overridden, and a lender checks its own memory and heat; a hung rpc-server is detected by a stall timeout; the lease uses elapsed time; a lender dropped by a re-plan is released; the tensor cache size is reported with a clear action. All: fix (MemoryCloud builder).
Data Union, Knowledge Bank, Choir, audit log: deleted or moved files leave the index; an index run can be stopped and has a per-file timeout; the vector matrix grows in place; peer responses are size-capped; failed pushes are retried and shown; tombstones are offered first and bounded safely; cards survive a change of pairing secret; a phone that drops mid-round stops holding the round; a failed run keeps the votes gathered; a failed audit write no longer breaks the chain or the caller; an interrupted prune is finished at start. All: fix (data builder).

## Deferred, with the reason
- Binding the request id and node id into the request signature (a captured request can be replayed within 30 s under a fresh id on a shared Wi-Fi). It changes the signed string, and Phone A stays on c2ca589 until the founder releases it. To do when all phones take one build: a second, versioned signature header required from peers that advertise the new protocol version.
- Resuming a batch after the coordinating phone dies. The small version is being built (the report survives as "interrupted"); takeover by another phone is not.
- Fetching a model from a paired phone instead of the internet: out of the scope fixed in CLAUDE.md.
- Code shrinking of the demo APK (20 to 30 MB): after the demo freeze; it needs a full regression on a phone.
- A second copy of the 18.56 GB model on Phone C and lender tensor caches of 5.7 to 6.8 GB: storage only; removal is left to the founder (deletes on the phones are not done from this session).

## The never-idle mode
Design in plans/2026-10-10-hardening-audit.md. Two audit inputs changed it: the vendor OS froze and killed this app 6 and 11 minutes after screen-off while it was generating, so filler work runs only with the app in front, or charging with the battery exemption granted; and filler must not evict the prompt cache of real work or use up the model server's single automatic restart.

## What held (things the audits tried to break and could not)
Job bookkeeping within one phone (a repeated job id shares one run; cancelled work frees its slot); allocation by measured speed, queue and battery rather than round robin; signed streams reject a missing or wrong final signature; requests with a wrong secret, a skewed clock or an oversized body are refused before the body is read; stop during start cannot leave a stale server; index writes are one transaction per file and resumable; Knowledge Bank ids are content hashes, so replays and modified cards are harmless; the lender's lease restores its models without any stop message; the agent endpoint's loopback, key and file-search checks.
