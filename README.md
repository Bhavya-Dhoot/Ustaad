# Ustaad

Ustaad is an Android app that makes two or three iQOO 15 phones on one local link work as one system, with no cloud model in the loop. It was built for the iQOO Hackathon 2026 Grand Finale (Bengaluru, 2026-10-09 to 2026-10-11, 48 hours). The coordination layer is called Swarm; on top of it sit four modules: Agent Choir, Data Union, MemoryCloud and ExpertMesh.

Naming: the product is Ustaad. It was called SwarmOS until 2026-10-10, and code identifiers keep the old name (package `com.swarmos.app`, deep link `swarmos://pair`, service type `_swarmos._tcp`) until a final rename step (docs/CONTRACTS.md section 10).

Status in one paragraph (2026-10-10, master b661cdf): the link, the model runtime, Agent Choir and Data Union run on real phones with real models. Gates 0, 1 and 2 are closed with measured numbers. Agent Choir and Data Union are built but have no gate artifact and no benchmark table. MemoryCloud has a measured spike, a measured negative result for a model that fits one phone, and a module on an unmerged branch; the Memory tab on master is a placeholder.

## Start here

| If you want to | Read |
|---|---|
| Continue the build | HANDOFF.md, then the newest file in gates/, then CLAUDE.md |
| Set up three phones | docs/SETUP.md |
| Present at the finale | docs/DEMO.md (timed script) and docs/PITCH.md (one page, claims policy) |
| Know what each module must do | docs/PRD.md, docs/CONTRACTS.md |

## Repository map

```
CLAUDE.md             project doctrine, device facts, gates
HANDOFF.md            done, in progress, next
docs/PRD.md           requirements, build order, original demo script
docs/CONTRACTS.md     build contracts, ports, node API, addenda
docs/SETUP.md         clean phone to ready swarm, checklist, troubleshooting
docs/DEMO.md          6-minute and 90-second demo, judging-criteria map
docs/PITCH.md         one-page pitch, limits, claims we do not make
docs/models.md        pinned model files, sizes, SHA-256
docs/llama-server-api-notes.md   working llama-server and rpc-server command lines
docs/benchmarks/      measured numbers only
docs/review/          read-only review of the merged app
docs/research/        six research reports
docs/device-probes/   raw adb probes of the three phones
docs/design/          design system (DESIGN.md), UI kit gallery, screenshots
docs/demo-corpus/     synthetic files for the Data Union demo (A, B, C)
gates/                one artifact per closed gate
plans/                dated plans and plans/briefs/ (one brief per worker)
app/                  Gradle root; module :app is the Android app
native/               llama.cpp build scripts, patches, prebuilt arm64 executables, bench scripts
scripts/              adb helpers, signed API caller, demo corpus, throughput probe (tput/)
ui-kit/               Google Stitch export of the 21 screens (visual reference, not code)
.claude/agents/       worker definitions
models/, third_party/ gitignored (GGUF files, fetched sources)
```

App source is in `app/app/src/main/java/com/swarmos/app/`: `core/` (registry, health, audit log, foreground service), `link/` (Swarm: discovery, heartbeat, pairing, Wi-Fi Direct, throughput probe), `llm/` (llama.cpp runtime, model store, benchmark), `choir/`, `pdu/` (Data Union), `memcloud/` (placeholder on master), `sched/` (scheduler), `shell/` (setup, settings), `ui/` (the "Whiteboard" kit).

## Build and run

```
cd app
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb -s 192.168.242.89:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and an `app/local.properties` with `sdk.dir`. The llama.cpp executables are committed under `app/app/src/main/jniLibs/arm64-v8a/`. The `demo` build type (not debuggable, application id `com.ustaad.app`) is on a branch and not on master; see docs/SETUP.md section 2. Models and the demo corpus are pushed with adb: docs/SETUP.md sections 5 and 6.

Stack: Kotlin and Jetpack Compose, minSdk 31, target 36. Inference is llama.cpp v0.6.0 `llama-server` and `rpc-server` run as child processes and called over loopback HTTP; there is no JNI inference wrapper (gate-2-inference-baseline.md). Nodes talk HTTP and JSON with an HMAC signature (docs/CONTRACTS.md).

## Where the evidence is

Every number shown to judges comes from a run on the iQOO phones and is logged under docs/benchmarks/.

| File | What it holds |
|---|---|
| docs/benchmarks/2026-10-09-swarmlink-lan-baseline.md | Access-point baseline, 50 to 61 Mbps, 11 ms RTT |
| docs/benchmarks/2026-10-10-swarmlink-g1.md | In-app link probe: Wi-Fi Direct 305 to 503 Mbps against LAN 96 to 104 Mbps, discovery, away detection, screen-off soak |
| docs/benchmarks/2026-10-10-llama-cpu-baseline.md | CPU variants, thread sweep, thermal behaviour, cool-start decode 14.86 tok/s |
| docs/benchmarks/2026-10-10-inapp-llm.md | Same model through the app, quality checks, the `pem_freezer` kills |
| docs/benchmarks/2026-10-10-rpc-split-spike.md | MemoryCloud feasibility: 4B split 3.18 tok/s against 14.86 on one phone; 30B on one phone fails |
| gates/gate-0 to gate-2 | Decisions, deltas from plan, open risks, ledger |
| docs/review/2026-10-10-merged-app-review.md | Blockers and fixes for the merged app, tagged by owner |

## Status by gate

| Gate | State | Evidence | Open risks |
|---|---|---|---|
| G0 research and PRD | Closed 2026-10-09 | gates/gate-0-research-prd.md | Superseded in places by gate 1 (see HANDOFF.md, contradictions) |
| G1 Swarm link | Closed 2026-10-10 (phase 1); phase 2 in progress on a branch | gates/gate-1-swarmlink.md | RTT 12 to 26 ms against an 8 ms target; OS freezes the app when the screen is off; camera QR scan untested; official numbers to retake quiet |
| G2 inference baseline | Closed 2026-10-10 | gates/gate-2-inference-baseline.md | No clean cool-start in-app table; `pem_freezer` kills; battery exemption untested; download screen unexercised |
| G3 Agent Choir | Built, merged, not closed | No gate artifact; no 20-problem table | Table owed; roles are one model with different prompts |
| G4 Data Union | Built, merged, not closed | No gate artifact; no timing note | Index wiped by a fake embedder on master (fix on a branch); privacy findings in the review |
| G5 MemoryCloud | Spike measured; app module on a branch | docs/benchmarks/2026-10-10-rpc-split-spike.md | 30B three-phone split never run; rpc-server has no authentication |
| G6 ExpertMesh and polish | Not started as a gate | none | Stretch |

The gate rule stays: a gate without measured numbers on the real phones does not close (CLAUDE.md).

## Hard facts that bound the design

- No Wi-Fi Aware service on the phones, although the framework feature list still names it; Wi-Fi Direct and softAP exist (docs/device-probes/, 2026-10-10-swarmlink-g1.md).
- No NNAPI service (CLAUDE.md); the app ships the CPU build (2026-10-10-inapp-llm.md).
- OriginOS freezes and kills background work; keep screens on with Ustaad in front for any demo (2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md).
- The phones throttle within minutes; every speed carries its thermal state (2026-10-10-llama-cpu-baseline.md).
