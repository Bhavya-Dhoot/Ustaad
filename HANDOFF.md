# HANDOFF

As of 2026-10-10, master e2d41e4 (08:25 IST). The finale runs 2026-10-09 to 2026-10-11. Read this file, then the newest file in gates/ (gate-5-memorycloud.md; there is no gate-3 file), then CLAUDE.md. Everything before the newest gate artifact is dead context (CLAUDE.md). What may be claimed is in docs/CLAIMS.md and is binding.

Product name: Ustaad (code still says SwarmOS until the final rename, docs/CONTRACTS.md section 10).

## Done (on master)

- G0 closed: research, PRD, scope lock (gates/gate-0-research-prd.md).
- G1 closed, phase 2 merged: discovery, heartbeat, pairing with confirmation, signed requests and responses, Wi-Fi Direct group on the station channel (640 to 920 Mbps), striped bulk, request-id dedup and failover (700 of 700 executed once, paths silenced with a fault hook), Bluetooth control path, group re-forming (docs/benchmarks/2026-10-10-swarmlink-throughput.md, gates/gate-1-swarmlink.md).
- G2 closed: llama-server and rpc-server as child processes, model store, benchmark view, restart policy, cancellable chat; the chat model runs on the Adreno GPU with CPU fallback (prefill 201.9 against 71.6 tok/s, one run each, phone C, docs/benchmarks/2026-10-10-inapp-llm.md).
- G4 closed with the latency target missed: Data Union, Hindi OCR, audit page, private by default (gates/gate-4-private-data-union.md, docs/benchmarks/2026-10-10-e2e-data-union.md).
- Agent Choir built and merged: typed answers, validation and retry, strict scorer; one phone alone 15 of 20, three samples 18 of 20 (docs/benchmarks/2026-10-10-choir-answer-quality.md). The two-phone arm was not re-run after the fix. No gate-3 file.
- MemoryCloud module (planner, rpc lender with a 10 s lease, split coordinator, degraded and re-plan, ExpertMesh explanation screen). 4B over two phones: 6.86 and 6.98 tok/s against 10.80 and 14.69 alone (docs/benchmarks/2026-10-10-memorycloud-inapp.md). Gate 5 is open.
- Scheduler with the throughput panel embedded in the Swarm screen (15.38 against 7.66 tok/s on two phones, baseline throttled, docs/benchmarks/2026-10-10-e2e-swarm-throughput.md).
- Knowledge Bank prototype (cards, replication, revocation, saved-answer search, Data tab page); 27 unit tests; not run on a phone (docs/benchmarks/2026-10-10-knowledge-bank.md).
- Shell: first-run setup, settings sheet, Stop Ustaad, audit log rotation; the `demo` build type (docs/BUILD.md).
- Documents: docs/CLAIMS.md (claims ledger), docs/research/07 and 08, docs/review/2026-10-10-second-review.md, docs/design/qa/REPORT.md, and, written in two passes this session, docs/SETUP.md, docs/DEMO.md, docs/PITCH.md, docs/DECK.md, README.md and this file.
- Every branch that existed at the first pass of this file has been merged (`git branch --no-merged master` lists none at 08:25).

## In progress

- The second end-to-end run (Data Union with the PRD pair, Knowledge Bank two-phone procedure, throughput, Choir on two or three phones): scripts/e2e2-check.sh, scripts/e2e/e2e2.py and scripts/e2e/queue2.sh exist and are not yet committed; results will land as docs/benchmarks/2026-10-10-e2e2-*.md (second run pending, names not fixed). The three-phone 30B run of MemoryCloud is written as a procedure in docs/benchmarks/2026-10-10-memorycloud-inapp.md ("Pending") and has not run: the end-to-end tester took the device markers in the same second the waiter polled it.

## State of the phones

As reported by the orchestrator at the second pass; I have not touched or read a phone, so every line is "reported", not "verified". Where a benchmark file records an older state I say so.

| Item | A (10BFC41T2N001UZ, 192.168.242.89) | B (10BFAU14Q6000XR, 192.168.242.23) | C (10BFAT216Z000XQ, 192.168.242.76) |
|---|---|---|---|
| App installed | Debug base app `com.swarmos.app` only, no test variants | same | same |
| Demo build `com.ustaad.app` | Not installed (reported). docs/BUILD.md says build, install and three curl checks ran on phone C, so it may have been removed afterwards | not installed | see A |
| Models | Reported: in the app folder `/sdcard/Android/data/com.swarmos.app/files/models/` (default chat and embedder; which other files remain is not recorded; phone C once also held Gemma 4 E4B, docs/benchmarks/2026-10-10-inapp-llm.md) | same | same, plus the 30B file in `/data/local/tmp/llama/models/` (not in the app folder; the in-app 30B run needs it copied there, docs/benchmarks/2026-10-10-memorycloud-inapp.md) |
| Demo corpus | Folders named `SwarmDemo` under Pictures and Documents (scripts/make-demo-corpus.py). Whether `IMG_2231.jpg` is on B is not recorded: the first run found the photo on no phone (docs/benchmarks/2026-10-10-e2e-data-union.md) | `SwarmDemo` folders; check `IMG_2231.jpg` | `SwarmDemo` folders |
| Pairing secret | The dev secret `swarmos-dev-secret` unless Host was pressed since; the first e2e run had rotated it and put both phones back on the dev secret (docs/benchmarks/2026-10-10-e2e-choir.md, F9) | unknown | see A |
| Left over | At 06:21 `/data/local/tmp/tput` and some other workers' files (`llama`, `router`, `human.*`, `devcap.sh`, `ui_*`) remained on A and C; Bluetooth had been switched off again and the Wi-Fi Direct group removed (docs/benchmarks/2026-10-10-swarmlink-throughput.md). The clean-up rule says the end state is one Ustaad app, the models and the corpus (plans/briefs/_common.md); confirm | Phone B was reserved from 03:40 to about 07:20, then released (plans/briefs/_common.md) | see A |

Network: the PC and the phones were on the access point "Chulbul" (192.168.242.x). Phone A once left it by itself for a saved network "iQOO" at 06:22, cause not found (docs/benchmarks/2026-10-10-e2e-choir.md, F5).

## What the founder must do by hand

- On each phone, first-run setup: allow nearby devices, allow notifications, and turn off battery limits for Ustaad (the Android dialog); on vivo and iQOO also "Allow background start" (docs/SETUP.md section 4). No adb path exists for this on the demo build, and the battery exemption is untested (gates/gate-2-inference-baseline.md).
- Install the demo build on all three phones, push the two models into `com.ustaad.app`'s own folder, name the phones in Settings (docs/SETUP.md sections 2, 3, 5).
- Pair by QR scan in the demo build: host on one phone, scan on the others; or the paste route. The camera scan has never been exercised (docs/CLAIMS.md LK9); rehearse it.
- Index each phone in the Data tab (Photos "Index", Documents "Pick folder"): the folder picker cannot be driven over adb.
- Keep Ustaad in front with the screen on on every phone during the demo. The OS freezes it otherwise (docs/benchmarks/2026-10-10-swarmlink-g1.md). Phones cool, on chargers, fan on.
- Decide the hackathon track and the closing ask (docs/PRD.md section 10 question 1 is unanswered; docs/DECK.md slide 11).

## Open gates

| Gate | What closes it |
|---|---|
| G3 Agent Choir | A gate-3 file with the two-phone arm re-measured on 20 problems with the strict scorer, both check settings, and a run on the demo build (docs/CLAIMS.md M3, CH11) |
| G5 MemoryCloud | A three-phone run of the 30B model through the app that produces tokens, with load time, first token, decode tok/s, memory per phone and thermal state; fallback a 13 to 15 GB file of the same model (gates/gate-5-memorycloud.md) |
| G6 ExpertMesh and polish | A measured ExpertMesh figure or the explanation kept as an explanation; the visual defects in docs/design/qa/REPORT.md reshot after fixes; a recorded rehearsal |
| G1, G2 retakes | B to C over Direct on quiet phones; an in-app cool-start decode table and a 10-minute sustained run in the app (gates/gate-1-swarmlink.md, gates/gate-2-inference-baseline.md) |
| G4 follow-up | Data Union timing on the GPU build, the PRD pair with the photo on B, an absent-phone ask that raises Incomplete, a person tapping a citation (gates/gate-4-private-data-union.md) |

## Next (in this order)

1. Let the second end-to-end run finish, commit scripts/e2e2-check.sh and its notes, and update docs/CLAIMS.md rows from the results.
2. Run the three-phone 30B procedure (docs/CLAIMS.md M1). If it does not load, use the smaller file of the same model.
3. Install the demo build on all three phones, run the three curl checks in docs/BUILD.md, and rehearse docs/DEMO.md once with a recording, thermal state logged. Record Choir `m2`, a Data Union ask and a Memory split on the demo build and reshoot the screenshots listed in docs/DECK.md.
4. Run, by a person: camera QR scan, document scan, dictation, citation tap, a Verify of the audit log, the 20-minute screen-off soak, airplane mode with the Direct group, Office Kit mirroring (docs/CLAIMS.md M6 to M8).
5. Fix the top visual defects (docs/design/qa/REPORT.md) and the open review items (docs/review/2026-10-10-second-review.md), starting with the Data Union index guard (an embedder stopping mid-run) and the debug-only intent extras.
6. Do the final rename to `com.ustaad.app` for the debug build and the code identifiers last.

## Contradictions between documents (decide, then fix the older one)

| Where | Says | Other says |
|---|---|---|
| docs/PRD.md sections 4, 5.1, 9 | Hotspot first, Ed25519, 4-byte framing; tap a citation and the photo opens on B; "0 bytes to WAN" | Section 12 of the same file, gate-1 and docs/CONTRACTS.md: Wi-Fi Direct first, HMAC, HTTP and JSON; a notification the owner taps; a "No internet" stamp (docs/CLAIMS.md PS2) |
| docs/PRD.md R3.1, section 7 | Alt-solver from a second model family | Built: one model, different prompt and temperature (docs/CLAIMS.md CH5) |
| docs/PRD.md R5.2 | Qwen3-14B for two phones | docs/models.md pins no 14B file |
| CLAUDE.md, gate-0 | Wi-Fi Aware "feature flag absent" | Probes list `WIFI_FEATURE_AWARE` and a `wifi-aware0` interface; only the service is absent |
| gates/gate-2 "sustained decode 4.9 to 6.1 tok/s" | Reads as a 10-minute run | docs/benchmarks/2026-10-10-inapp-llm.md: minutes 1 and 2 of a run killed at 6 min 18 s; the sustained GPU series is 12.3, 10.6, 6.8 tok/s (docs/benchmarks/2026-10-10-llama-cpu-baseline.md) |
| docs/models.md "Large model status" | The 30B file is still to be pushed to phone C | The spike and the memory note ran it on C from `/data/local/tmp/llama/models/` |
| docs/BUILD.md | An adb deep link works only on debug builds | MainActivity.kt: the `swarmos://pair` link is read by every build; the demo build asks for confirmation (not verified on a demo install) |
| docs/CLAIMS.md section 0 | "Phone B took no part in any end-to-end run" | docs/design/qa/32-data-audit.png and fix-shell-choir.png show Phone B answering a Data Union ask and listed in Choir; those are QA frames, not benchmark runs |
| docs/CLAIMS.md section 0 | 572 unit tests (inapp-llm) | 618 on the Choir branch (choir-answer-quality); the counts are of different commits |
| docs/CLAIMS.md CH5 | "also remove from docs/PRD.md sections 3, 7 and R3.1" | Still in the PRD body; section 12 overrides it |

## Worker briefs (plans/briefs/) and reviews

| File | Worker | Task |
|---|---|---|
| _common.md | all | Shared rules: worktrees, build lock, device etiquette, report format, clean-up, device queue |
| uikit.md | swarm-android-builder | "Whiteboard" theme and shared composables, gallery screen |
| link.md, link2.md | swarm-network-engineer | Swarm link phase 1 (G1) and phase 2 (several transports, failover, throughput) |
| llm.md | swarm-inference-engineer | Model runtime (G2): llama-server and rpc-server in the app, model store, LlmClient |
| choir.md | swarm-choir-engineer | Agent Choir (G3) |
| pdu.md | swarm-rag-engineer | Data Union (G4) and the demo corpus |
| memcloud.md | swarm-inference-engineer | MemoryCloud and ExpertMesh (G5, G6) |
| sched.md | swarm-android-builder | Scheduler: any phone runs a request on any other, batch runner |
| shell.md | swarm-android-builder | First-run setup, settings sheet, status strip fixes |
| restyle.md | swarm-android-builder | Restyle one module with the kit, behaviour unchanged |

Also: plans/2026-10-09-build-plan.md and plans/2026-10-10-integration.md. Reviews: docs/review/2026-10-10-merged-app-review.md (first review; the status of its findings is at the top of the second review), docs/review/2026-10-10-second-review.md (open items by owner), docs/design/qa/REPORT.md (visual QA, 17 defects), docs/CLAIMS.md (claims ledger).

## Open questions for the founder

1. Hackathon track: Productivity, Developer Tools or Open Innovation (docs/PRD.md section 10, question 1; not answered in any file).
2. Whether the organisers allow three phones on stage and a laptop for Office Kit, how HackTracker counts Office Kit and creative phone use, and which of Red Light (phone only) or Green Light (phone and laptop) a mirror-only laptop is (docs/research/06-hackathon-context-and-landscape.md).
3. The closing ask on the last slide (docs/DECK.md slide 11).
4. Whether to file anything before further public disclosure: the novelty report notes India has no general grace period for a public demo and that this is not legal advice (docs/research/07-novelty-and-prior-art.md section 4).

## Tools

- `scripts/adb-connect.sh` reconnects the phones; `scripts/watch-phones.sh` watches the set.
- `scripts/swarm-call.sh <ip[:port]> <METHOD> <path> [json]` sends a signed Node API request with the dev secret (debug builds only); `scripts/e2e-check.sh` and `scripts/e2e2-check.sh` drive the end-to-end checks (`e2e-check.sh` refuses phone B, docs/CLAIMS.md section 4; `e2e2-check.sh` takes up to three addresses).
- `scripts/gradle-locked.sh <gradle root> <args>` caps concurrent builds at two.
- `scripts/make-demo-corpus.py --push-only` pushes the demo corpus; `scripts/probe-device.sh <serial>` writes a full device probe; `scripts/tput/` is the native throughput probe.
- `native/build-llama-android.sh`, `native/package-llama-android.sh`, `native/fetch-models.sh` and `native/bench/` rebuild the runtime and rerun the benchmarks.
