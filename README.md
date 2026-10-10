# Ustaad

Ustaad is an Android app that makes two or three iQOO 15 phones on one local link work as one system, with no cloud model in the loop. It was built for the iQOO Hackathon 2026 Grand Finale (Bengaluru, 2026-10-09 to 2026-10-11, 48 hours). The coordination layer is called Swarm; on top of it sit four modules: Agent Choir, Data Union, MemoryCloud and ExpertMesh. A Knowledge Bank prototype hangs off the Data tab.

Naming: the product is Ustaad. It was called SwarmOS until 2026-10-10, and code identifiers keep the old name (package `com.swarmos.app`, deep link `swarmos://pair`, service type `_swarmos._tcp`) until a final rename step (docs/CONTRACTS.md section 10). The demo build's application id is `com.ustaad.app`.

Status (2026-10-10, master c2ca589). Gates 0 to 5 are closed with measured numbers on the real phones; gate 6 (polish) has its build work done and a rehearsal list open for the founder (gates/gate-6-expertmesh-polish.md); gate 7 (agent endpoint: other apps on the phone use the model through a keyed OpenAI-format endpoint on loopback, docs/AGENT-API.md) is closed, and so is gate 8 (tool calling and file search for those apps, gates/gate-8-agent-tools-files.md). The master build passes 970 unit tests with 0 failures and builds the debug and demo APKs (docs/BUILD.md, 13:05); the debug build was installed on phones A, B and C at 13:07 with chat on the GPU and the embedder ready on all three, each phone listing the other two. Every module ran on real phones with real models, each workload in one run on the debug build: swarm throughput, Agent Choir (20 problems twice) and Data Union on three phones through the access point, an 18.6 GB mixture-of-experts model split across three phones through the app (docs/benchmarks/2026-10-10-memorycloud-inapp.md), and Knowledge Bank replication on three phones with no sync call (docs/benchmarks/2026-10-10-knowledge-bank.md, "Third run"). The second visual pass is docs/design/qa2/REPORT.md (10 of the 17 first-pass defects fixed, 2 partly, 5 not checked); eight further screen fixes were merged after it and their screenshot check is running (docs/design/qa3). What may be said about the project, and what may not, is in docs/CLAIMS.md.

## Start here

| If you want to | Read |
|---|---|
| Continue the build | HANDOFF.md, then the newest file in gates/ (gate-5-memorycloud.md), then CLAUDE.md |
| Know what may be claimed | docs/CLAIMS.md (binding), docs/research/07-novelty-and-prior-art.md |
| Build, install and set up three phones | docs/BUILD.md, docs/SETUP.md |
| Present at the finale | docs/DEMO.md (script), docs/PITCH.md (one page), docs/DECK.md (slides) |
| Know what each module must do | docs/PRD.md (section 12 overrides earlier sections), docs/CONTRACTS.md |

## Repository map

```
CLAUDE.md             project doctrine, device facts, gates
HANDOFF.md            done, in progress, next, state of the phones
docs/PRD.md           requirements; section 12 = as built and measured
docs/CONTRACTS.md     build contracts, ports, node API, addenda
docs/BUILD.md         build types (debug, demo), install, models, pairing, checks
docs/SETUP.md         clean phone to ready swarm, checklist, troubleshooting
docs/DEMO.md          6-minute and 90-second demo, pending box, do-not-claim box
docs/PITCH.md         pitch, novelty statements, limits, applications, Knowledge Bank
docs/DECK.md          slide-by-slide outline and styling note
docs/CLAIMS.md        claims versus evidence ledger (binding)
docs/models.md        pinned model files, sizes, SHA-256
docs/llama-server-api-notes.md   working llama-server and rpc-server command lines
docs/benchmarks/      measured numbers only (and the e2e notes); raw/ holds the raw results of the runs
docs/review/          two read-only reviews of the merged app
docs/research/        eight research reports (07 novelty, 08 applications)
docs/device-probes/   raw adb probes of the three phones
docs/design/          design system (DESIGN.md), UI kit gallery, shots/, qa/ and qa2/ (visual passes 1 and 2 on phone C), qa3/ (check of the eight fixes on the final build: all fixed)
docs/demo-corpus/     synthetic files for the Data Union demo (A, B, C)
gates/                one artifact per closed gate (0 to 5)
plans/                dated plans and plans/briefs/ (one brief per worker)
app/                  Gradle root; module :app is the Android app
native/               llama.cpp build scripts, patches, prebuilt arm64 executables, bench scripts
scripts/              adb helpers, signed API caller, e2e checks, demo corpus, throughput probe (tput/)
ui-kit/               Google Stitch export of the 21 screens (visual reference, not code)
.claude/agents/       worker definitions
models/, third_party/ gitignored (GGUF files, fetched sources)
```

App source is in `app/app/src/main/java/com/swarmos/app/`: `core/` (registry, health, audit log, foreground service), `link/` (Swarm: discovery, heartbeat, pairing, Wi-Fi Direct, Bluetooth control path, failover, throughput probe), `llm/` (llama.cpp runtime, GPU and CPU builds, model store, benchmark), `choir/`, `pdu/` (Data Union), `kb/` (Knowledge Bank), `memcloud/` (split, lender, ExpertMesh explanation), `sched/` (scheduler, throughput panel), `shell/` (setup, settings), `ui/` (the "Whiteboard" kit).

## Build and run

```
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDemo      # id com.ustaad.app, no debug routes, random secret
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug     # id com.swarmos.app, dev secret, debug routes
bash scripts/gradle-locked.sh "$PWD/app" :app:testDebugUnitTest
```

Install, model push and pairing steps for each build are in docs/BUILD.md and docs/SETUP.md. Needs JDK 17 and an `app/local.properties` with `sdk.dir`. The llama.cpp executables (CPU and OpenCL builds) are committed under `app/app/src/main/jniLibs/arm64-v8a/`. Stack: Kotlin and Jetpack Compose, minSdk 31, target 36. Inference is llama.cpp v0.6.0 `llama-server` and `rpc-server` run as child processes and called over loopback HTTP; there is no JNI inference wrapper (gate-2-inference-baseline.md). Nodes talk HTTP and JSON with an HMAC from the pairing secret; traffic is not encrypted (docs/CONTRACTS.md, docs/PITCH.md).

## Where the evidence is

Every number shown to judges comes from a run on the iQOO phones and is logged under docs/benchmarks/.

| File | What it holds |
|---|---|
| 2026-10-10-swarmlink-g1.md | Phase 1 link probe, discovery, away detection, the screen-off soak |
| 2026-10-10-swarmlink-throughput.md | Wi-Fi Direct on the station channel (640 to 920 Mbps), round trips, striping, failover 700 of 700, Bluetooth, re-forming the group, hardening checks |
| 2026-10-10-llama-cpu-baseline.md, 2026-10-10-throughput-tuning.md | CPU variants, thread sweep, GPU against CPU standalone, 10-minute sustained decode |
| 2026-10-10-inapp-llm.md | The model runtime in the app, GPU against CPU in the app (prefill 201.9 against 71.6 tok/s), restart test, the `pem_freezer` kills |
| 2026-10-10-choir-answer-quality.md | Why Choir answers were degenerate, the fix, one phone alone 15 of 20 and three samples 18 of 20 |
| 2026-10-10-e2e-choir.md, e2e-data-union.md, e2e-swarm-throughput.md | First end-to-end run on A and C (debug build, CPU chat server, lenient Choir scorer); superseded by the second run for every figure it covers |
| 2026-10-10-memorycloud-inapp.md, 2026-10-10-rpc-split-spike.md | 4B split over two phones (6.86 and 6.98 tok/s against 10.80 and 14.69); the 30B model on one phone (no token in 134 s), two phones (did not load) and three phones (load 319 s cold, 65 s warm, first token 1.5 to 2.8 s, 5.7 to 6.4 tok/s) |
| 2026-10-10-knowledge-bank.md | What was built, the procedure, and the third run: a shared save and a revoke listed on both other phones at the first poll, under 0.8 s, with no sync call; a card answered in 40 and 47 ms with the author's phone stopped |
| 2026-10-10-e2e2-swarm-throughput.md, e2e2-choir.md, e2e2-data-union.md, e2e2-knowledge-bank.md | Second end-to-end run, GPU on three phones: batch of 32 prompts 30.91 against 12.03 tok/s; Choir 16 of 20 in 20.1 s against 18 of 20 in 32.3 s for three samples on one phone; Data Union acceptance pair and first-time latency (median 4.2 s to the first word); Knowledge Bank on two phones |
| gates/ | Decisions, deltas from plan, open risks |
| docs/review/, docs/design/qa/REPORT.md | Two code reviews and one visual QA of the app |

## Status by gate

| Gate | State | Evidence | Open risks |
|---|---|---|---|
| G0 research and PRD | Closed 2026-10-09 | gates/gate-0-research-prd.md | Superseded in places by PRD section 12 |
| G1 Swarm link | Closed 2026-10-10; phase 2 merged | gates/gate-1-swarmlink.md, 2026-10-10-swarmlink-throughput.md | Camera QR scan untested; screen-off freezing; B to C not retaken quiet; no airplane-mode run |
| G2 inference baseline | Closed 2026-10-10; GPU build in the app | gates/gate-2-inference-baseline.md, 2026-10-10-inapp-llm.md | No clean cool-start in-app table; battery exemption untested; download screen not run on a phone |
| G3 Agent Choir | Closed 2026-10-10 on three phones | gates/gate-3-agent-choir.md, 2026-10-10-e2e2-choir.md | Not more accurate than one phone sampling three times (16 against 18 of 20, one problem is 5 points); a 20-problem run heats the phones to Thermal Status 2 to 3 in eight minutes; no run on the demo build |
| G4 Data Union | Closed 2026-10-10; second run on three phones | gates/gate-4-private-data-union.md, 2026-10-10-e2e2-data-union.md | The 2 s and 6 s targets are met by 2 and 4 of 9 first-time questions (median 4.2 s and 6.6 s); camera scan, folder picker and citation tap not exercised by a person; private by default on a fresh install not re-tested |
| G5 MemoryCloud | Closed 2026-10-10: an 18.6 GB model ran across three phones | gates/gate-5-memorycloud.md, 2026-10-10-memorycloud-inapp.md | One run; no repeat of the 5.3 minute cold load, no generation beyond 40 s per answer, nothing at Thermal Status 2 or above; the demo build has not carried a split; the memory lease is unit-tested only |
| G6 polish (ExpertMesh explanation screen) | OPEN | docs/design/qa2/REPORT.md, docs/design/qa/REPORT.md | Build work done (gates/gate-6-expertmesh-polish.md; eight screen fixes seen fixed in docs/design/qa3). Open: the demo build never exercised end to end; battery exemption and QR pairing need the founder's taps; phones overheat within minutes of sustained work; ExpertMesh: a mixture-of-experts model ran split by layer ranges, but individual experts were not placed on different phones and the screen's remote-experts figures are estimates |

The gate rule stays: a gate without measured numbers on the real phones does not close (CLAUDE.md).

## Hard facts that bound the design

- No Wi-Fi Aware service on the phones, although the framework feature list still names it; Wi-Fi Direct and softAP exist (docs/device-probes/, 2026-10-10-swarmlink-g1.md). 6 GHz is not available (country IN, 2026-10-10-swarmlink-throughput.md).
- No NNAPI service (CLAUDE.md); the app runs on the CPU and the Adreno GPU, not the NPU.
- OriginOS freezes and kills background work: keep screens on with Ustaad in front for any demo (2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md).
- The phones throttle within minutes; every speed carries its thermal state (2026-10-10-llama-cpu-baseline.md).
