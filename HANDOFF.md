# HANDOFF

As of 2026-10-10, master b661cdf (03:50 IST). The finale runs 2026-10-09 to 2026-10-11; the demo is in a few hours of wall clock, not days. Read this file, then the newest file in gates/ (gate-2-inference-baseline.md), then CLAUDE.md. Everything before the newest gate artifact is dead context (CLAUDE.md).

Product name: Ustaad (code still says SwarmOS until the final rename, docs/CONTRACTS.md section 10).

## Done (on master)

- G0 closed: research, PRD, scope lock (gates/gate-0-research-prd.md).
- G1 closed, phase 1: discovery, heartbeat, pairing (QR payload, deep link), Wi-Fi Direct group, in-app throughput probe. Wi-Fi Direct 305 to 503 Mbps and 12 to 20 ms average RTT between two quiet phones; same Wi-Fi 96 to 104 Mbps (docs/benchmarks/2026-10-10-swarmlink-g1.md, gates/gate-1-swarmlink.md).
- G2 closed: llama-server and rpc-server run as child processes of the app, model store with SHA-256 check, benchmark view, selftest, restart policy, cancellable chat. Decode 14.8 tok/s in the app on phone B, 14.86 tok/s cool standalone (docs/benchmarks/2026-10-10-inapp-llm.md, 2026-10-10-llama-cpu-baseline.md, gates/gate-2-inference-baseline.md).
- Built and merged, no gate artifact yet: Agent Choir (rounds, tool checker, 20 built-in problems, scoreboard, voice, review fixes) and Data Union (on-device index, OCR, federated ask, audit page, scan, voice).
- Shell: first-run setup and settings sheet with audit-log verify. UI kit v2 ("Whiteboard") and restyled Models, Choir and Data Union screens (docs/design/shots/).
- Scheduler backend (`sched/`): remote chat, batch runner, signed routes. Its throughput panel is not reachable from the UI on master (`showInNav = false`, no embedding yet).
- MemoryCloud feasibility spike: RPC split works on Android; 4B split 3.18 tok/s against 14.86 on one phone; 30B on one phone produces no token (docs/benchmarks/2026-10-10-rpc-split-spike.md).
- Docs written this session: docs/SETUP.md, docs/DEMO.md, docs/PITCH.md, README.md, this file.
- Review of the merged app, tagged by owner: docs/review/2026-10-10-merged-app-review.md.

## In progress (branches ahead of master, per `git log master..<branch>` at 03:53)

| Branch (worktree) | Commits ahead | What | Merge note |
|---|---:|---|---|
| worktree-agent-a603d19fc13a9fb9b | 3 | `demo` build type, audit log rotation and repair, Stop Ustaad, service locks follow foreground, startup skeleton (8 uncommitted files in the worktree) | Needed for the demo build |
| worktree-agent-aa03d1aa2341f4949 | 4 | Data Union review fixes: embedder guard, private by default, peer open by notification, on-device dictation, ML Kit upload code removed | Fixes review blockers 1 and 2 |
| worktree-agent-aa47143252c31db53 | 4 | Swarm link phase 2, marked work in progress: authenticated responses, pairing confirmation, local-address rule, failover with dedup, striped bulk, Bluetooth, latency lock (5 uncommitted files) | Fixes review item 5; brief plans/briefs/link2.md |
| worktree-agent-a8395c82c6a8a106b | 5 | Scheduler: streaming over SSE, one slot gate, embeddable throughput panel | Panel still needs a place in the UI |
| worktree-agent-ac34ad0bc1b4ddd46 | 7 | MemoryCloud and ExpertMesh module, rpc-server bound to the link address with watchdog | Locked worktree; no benchmark note yet |

Devices: since 2026-10-10 03:40 phone B is reserved for the founder and workers send it no adb command; heavy device work is serialised through a marker file (plans/briefs/_common.md addendum). The end state of each phone is one Ustaad app, the model files and the demo corpus.

## Next (in this order)

1. Merge the branches above one at a time, running `:app:assembleDebug` and `:app:testDebugUnitTest` after each (plans/2026-10-10-integration.md). Re-read docs/review/ "Fix first" items 1 to 5 against master after the merges and update the Status column.
2. Install one build on A, B and C, push models and corpus, and run the pre-demo checklist (docs/SETUP.md section 8). Prefer the `demo` build; rehearse on it.
3. Retake official numbers on cool idle phones: link (including the C pairs), in-app decode and a 10-minute sustained run (open items in gate-1 and gate-2).
4. Measure and write the missing benchmark notes, then write gate-3 and gate-4 artifacts:
   - Choir: "Run all 20" on cool phones, one phone alone against Choir and Choir plus tool check.
   - Data Union: time to first text and full answer for "Where is the warranty bill for my laptop?" on the Direct link (target 2 s and 6 s, docs/PRD.md R4.4).
5. MemoryCloud (gate 5): valid three-phone split of the 4B model (the earlier run used `-ts 1,1`), then the 30B model across three phones, the pull-a-phone test, and docs/benchmarks/2026-10-10-memorycloud-inapp.md. Commands are in docs/benchmarks/2026-10-10-rpc-split-spike.md. If it does not work by the cut-off, the demo uses the fallback already written in docs/DEMO.md step 4.
6. Run the 20-minute screen-off soak with the battery exemption set; pass is zero away transitions (gate-1 open risk).
7. Test, on the phones, the things nobody has: QR scan through the camera, airplane mode with Wi-Fi Direct, Office Kit mirroring.
8. Record a full 6-minute rehearsal with thermal state logged (plans/2026-10-09-build-plan.md, step 5), then do the final rename to `com.ustaad.app`.

## Contradictions found between documents (decide, then fix the older one)

| Where | Says | Other says |
|---|---|---|
| docs/PRD.md sections 4, 5.1, 9 | Hotspot first, Ed25519, 4-byte framing | gate-1-swarmlink.md and docs/CONTRACTS.md: Wi-Fi Direct group first, HMAC, HTTP and JSON |
| CLAUDE.md, gate-0 | Wi-Fi Aware absent, "feature flag absent" | docs/device-probes/iqoo-A-*.md lists `WIFI_FEATURE_AWARE` and a `wifi-aware0` interface; only the service is absent (noted in 2026-10-10-swarmlink-g1.md). We still do not use it |
| docs/PRD.md R5.2 | Qwen3-14B for the two-phone split | docs/models.md pins no 14B file; only the 30B (three phones) and the 4B |
| docs/PRD.md R3.1, section 7 | Alt-solver from a second model family | Built: one model, different prompt and temperature (choir/ChoirPrompts.kt); Gemma 4 E4B is chat only (gate-2) |
| docs/PRD.md sections 8, 9 | "0 bytes to WAN" on the stats bar | ui/StatsBar.kt counts the model downloader only (review item 4) |
| gate-2 "sustained decode 4.9 to 6.1 tok/s" | Reads as a 10-minute sustained run | 2026-10-10-inapp-llm.md: minutes 1 and 2 of a run that was killed at 6 min 18 s |
| docs/llama-server-api-notes.md, 2026-10-10-llama-cpu-baseline.md | Cite `2026-10-10-throughput-tuning.md` | File is not in docs/benchmarks/ |
| docs/models.md "Large model status" | The 30B file is still to be pushed to phone C | 2026-10-10-rpc-split-spike.md ran the 30B file on phone C (from `/data/local/tmp/llama/models/`) |
| docs/PRD.md section 9 | Tap a citation and the photo opens on B | The PDU branch changes this to a notification the owner taps (review section 1) |
| README.md and HANDOFF.md before this rewrite | "No app code exists", "app/ not created" | Replaced in this commit |

## Worker briefs (plans/briefs/)

| File | Worker | Task |
|---|---|---|
| _common.md | all | Shared rules: worktrees, build lock, device etiquette, report format, clean-up, phone B reserved |
| uikit.md | swarm-android-builder | "Whiteboard" theme and shared composables, gallery screen |
| link.md | swarm-network-engineer | Swarm link (G1): discovery, pairing, heartbeat, in-app Mbps and RTT |
| link2.md | swarm-network-engineer | Swarm link phase 2: several transports, failover, throughput |
| llm.md | swarm-inference-engineer | Model runtime (G2): llama-server and rpc-server in the app, model store, LlmClient |
| choir.md | swarm-choir-engineer | Agent Choir (G3) |
| pdu.md | swarm-rag-engineer | Data Union (G4) and the demo corpus |
| memcloud.md | swarm-inference-engineer | MemoryCloud and ExpertMesh (G5, G6) |
| sched.md | swarm-android-builder | Scheduler: any phone runs a request on any other, batch runner, aggregate tokens per second |
| shell.md | swarm-android-builder | First-run setup, settings sheet, status strip fixes |
| restyle.md | swarm-android-builder | Restyle one module with the kit, behaviour unchanged |

Also: plans/2026-10-09-build-plan.md (original plan, gates and steps), plans/2026-10-10-integration.md (merge order and failure modes). The review file is docs/review/2026-10-10-merged-app-review.md.

## Open questions for the founder

1. Hackathon track: Productivity, Developer Tools or Open Innovation (docs/PRD.md section 10, question 1; not answered in any file). It decides which Choir task and corpus lead.
2. Whether the organisers allow three phones on stage and a laptop for Office Kit mirroring, and how HackTracker counts Office Kit and creative phone use (docs/research/06-hackathon-context-and-landscape.md). The Red Light and Green Light build categories also need an answer.
3. Third phone: answered. Phone C joined 2026-10-10 (CLAUDE.md), so the 30B three-phone split is possible in principle.
4. Which phone drives the 30B split in the demo, and where its copy of the file lives. The spike used phone C and a folder outside the app (2026-10-10-rpc-split-spike.md); an in-app run needs the file in the app's models folder, pushed over the USB cable (docs/models.md).

## Tools

- `scripts/adb-connect.sh` reconnects the phones; `scripts/watch-phones.sh` watches the set.
- `scripts/swarm-call.sh <ip[:port]> <METHOD> <path> [json]` sends a signed Node API request with the dev secret; it works on debug builds only.
- `scripts/gradle-locked.sh <gradle root> <args>` caps concurrent builds at two.
- `scripts/make-demo-corpus.py --push-only` pushes the demo corpus; `scripts/probe-device.sh <serial>` writes a full device probe; `scripts/tput/` is the native throughput probe.
- `native/build-llama-android.sh`, `native/package-llama-android.sh`, `native/fetch-models.sh` and `native/bench/` rebuild the runtime and rerun the benchmarks.
