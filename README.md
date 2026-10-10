# Ustaad

Ustaad is an Android app that makes two or three iQOO 15 phones on one local link work as one system, with no cloud model in the loop. It was built for the iQOO Hackathon 2026 Grand Finale (Bengaluru, 2026-10-09 to 2026-10-11, 48 hours). The coordination layer is called Swarm; on top of it sit four modules: Agent Choir, Data Union, MemoryCloud and ExpertMesh. A Knowledge Bank prototype hangs off the Data tab.

Naming: the product is Ustaad. It was called SwarmOS until 2026-10-10, and code identifiers keep the old name (package `com.swarmos.app`, deep link `swarmos://pair`, service type `_swarmos._tcp`) until a final rename step (docs/CONTRACTS.md section 10). The demo build's application id is `com.ustaad.app`.

Status (2026-10-10, master e2d41e4, 08:25 IST). Every module runs on real phones with real models. Gates 0, 1, 2 and 4 are closed with measured numbers; gate 5 (MemoryCloud) is open; Agent Choir has no gate artifact. Every workload was measured on two phones (A and C); three phones were measured only at link level. A second end-to-end run is in progress (second run pending, 2026-10-10-e2e2-*.md). What may be said about the project, and what may not, is in docs/CLAIMS.md.

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
docs/benchmarks/      measured numbers only (and the e2e notes)
docs/review/          two read-only reviews of the merged app
docs/research/        eight research reports (07 novelty, 08 applications)
docs/device-probes/   raw adb probes of the three phones
docs/design/          design system (DESIGN.md), UI kit gallery, shots/, qa/ (visual QA on phone C)
docs/demo-corpus/     synthetic files for the Data Union demo (A, B, C)
gates/                one artifact per closed gate (0, 1, 2, 4, 5)
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
| 2026-10-10-e2e-choir.md, e2e-data-union.md, e2e-swarm-throughput.md | First end-to-end run on A and C (debug build, CPU chat server, lenient Choir scorer) |
| 2026-10-10-memorycloud-inapp.md, 2026-10-10-rpc-split-spike.md | 4B split over two phones (6.86 and 6.98 tok/s against 10.80 and 14.69), the 30B result on one and two phones, the pending three-phone procedure |
| 2026-10-10-knowledge-bank.md | What was built and the procedure; not yet run |
| 2026-10-10-e2e2-*.md | Second end-to-end run (second run pending; files do not exist yet) |
| gates/ | Decisions, deltas from plan, open risks |
| docs/review/, docs/design/qa/REPORT.md | Two code reviews and one visual QA of the app |

## Status by gate

| Gate | State | Evidence | Open risks |
|---|---|---|---|
| G0 research and PRD | Closed 2026-10-09 | gates/gate-0-research-prd.md | Superseded in places by PRD section 12 |
| G1 Swarm link | Closed 2026-10-10; phase 2 merged | gates/gate-1-swarmlink.md, 2026-10-10-swarmlink-throughput.md | Camera QR scan untested; screen-off freezing; B to C not retaken quiet; no airplane-mode run |
| G2 inference baseline | Closed 2026-10-10; GPU build in the app | gates/gate-2-inference-baseline.md, 2026-10-10-inapp-llm.md | No clean cool-start in-app table; battery exemption untested; download screen not run on a phone |
| G3 Agent Choir | Built and merged; no gate artifact | 2026-10-10-choir-answer-quality.md, 2026-10-10-e2e-choir.md | Two-phone arm not re-run after the answer-quality fix; roles are one model with different prompts |
| G4 Data Union | Closed 2026-10-10, latency target missed | gates/gate-4-private-data-union.md, 2026-10-10-e2e-data-union.md | Target met only on repeated questions (CPU build); PRD pair (photo on B) not run; camera scan, folder picker and citation tap not exercised by a person |
| G5 MemoryCloud | OPEN | gates/gate-5-memorycloud.md, 2026-10-10-memorycloud-inapp.md | 30B over three phones never run; the split of a model that fits is slower; the memory lease is unit-tested only |
| G6 ExpertMesh and polish | Explanation screen only | none | No mixture-of-experts model has run split; visual defects listed in docs/design/qa/REPORT.md |

The gate rule stays: a gate without measured numbers on the real phones does not close (CLAUDE.md).

## Hard facts that bound the design

- No Wi-Fi Aware service on the phones, although the framework feature list still names it; Wi-Fi Direct and softAP exist (docs/device-probes/, 2026-10-10-swarmlink-g1.md). 6 GHz is not available (country IN, 2026-10-10-swarmlink-throughput.md).
- No NNAPI service (CLAUDE.md); the app runs on the CPU and the Adreno GPU, not the NPU.
- OriginOS freezes and kills background work: keep screens on with Ustaad in front for any demo (2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md).
- The phones throttle within minutes; every speed carries its thermal state (2026-10-10-llama-cpu-baseline.md).
