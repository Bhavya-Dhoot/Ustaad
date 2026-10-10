# HANDOFF

As of 2026-10-11 00:30, master f62da67 plus documents (gates 0 to 5, 7 and 8 closed; gate 9 closed for what two phones can show, with an evening addendum; gate 6 open for the founder's rehearsal list). One document gives the whole picture: docs/SUMMARY.md. It builds with 1453 unit tests, 0 failures (docs/BUILD.md, 2026-10-11 00:05, debug APK); phones B and C carry the build of f62da67 since 00:07 on 2026-10-11 (the Hammer button at the top and bottom of Settings, which holds keep busy at full duty on a warm phone, is off at every start and rests 90 s when stopped: docs/benchmarks/2026-10-11-hammer.md; app icon, Settings row to grant the battery exemption, clearer pairing message, and the keep-busy fix for a refused MemoryCloud start, which had stopped keep busy on Phone C from 21:35 to 22:11: docs/benchmarks/2026-10-10-throttle-passive.md), keep busy on, agent access off, each listing the other; the same note shows that real decode speed follows the GPU throttle step (18.2 tok/s cold, 13.4 at step 5, 10.9 at step 7) and a duty rule that reads the step was tried on Phone B for 50 minutes (real work 13.5 tok/s against 10.8, filler 36 % of the time against 56 %) and is not merged, by the founder's decision; it stays on branch `worktree-agent-a718f09290a81f34a` (e2452fe); the slow step starts when the kernel zone `tz_shell` reaches 40.0 C, see step 6 of plans/2026-10-10-hardening-audit.md; phones B and C were paired again at about 21:15 with a secret made on the phones, so a script must read it from the phone (scripts/throttle-watch.py shows how) and never print it; the only APK kept on the PC is app/app/build/outputs/apk/debug/app-debug.apk (the demo variant is rebuilt with `:app:assembleDemo` when needed); Phone A is reserved by the founder since 13:12 and still carries the older build c2ca589 (CLAUDE.md), which the newer phones show as "runs an older version"; with chat on the GPU and the embedder ready on all three, each phone listing the other two. The finale runs 2026-10-09 to 2026-10-11. Read this file, then the newest file in gates/ (gate-9-hardening.md), then CLAUDE.md. Everything before the newest gate artifact is dead context (CLAUDE.md). What may be claimed is in docs/CLAIMS.md and is binding.

Product name: Ustaad (code still says SwarmOS until the final rename, docs/CONTRACTS.md section 10).

## Gates (one-line verdicts)

| Gate | Verdict | File |
|---|---|---|
| G0 research and PRD | Closed. Scope locked. | gates/gate-0-research-prd.md |
| G1 Swarm link | Closed. Wi-Fi Direct on the station channel, 640 to 920 Mbps; failover 700 of 700; camera QR scan untested. | gates/gate-1-swarmlink.md, docs/benchmarks/2026-10-10-swarmlink-throughput.md |
| G2 inference baseline | Closed. Qwen3.5-4B in the app, GPU by default with CPU fallback (prefill 201.9 against 71.6 tok/s, one run each). | gates/gate-2-inference-baseline.md, docs/benchmarks/2026-10-10-inapp-llm.md |
| G3 Agent Choir | Closed on three phones. Parallel speed and cross-checking: 16 of 20 in 20.1 s against 18 of 20 in 32.3 s for one phone sampling three times; never "more accurate". | gates/gate-3-agent-choir.md, docs/benchmarks/2026-10-10-e2e2-choir.md |
| G4 Data Union | Closed. The acceptance pair ran (bill photo on Phone B, certificate on Phone A); first-time questions median 4.2 s to the first word and 6.6 s total; the 2 s and 6 s targets met by 2 of 9 and 4 of 9. | gates/gate-4-private-data-union.md, docs/benchmarks/2026-10-10-e2e2-data-union.md |
| G5 MemoryCloud | Closed. The 18.6 GB Qwen3-30B-A3B ran across three phones: load 319 s cold and 65 s warm, first token 1.5 to 2.8 s, 5.7 to 6.4 tok/s; one phone gave no token, two did not load. One run. | gates/gate-5-memorycloud.md, docs/benchmarks/2026-10-10-memorycloud-inapp.md |
| G6 polish | OPEN, see below. | docs/design/qa2/REPORT.md, docs/design/qa/REPORT.md |
| G7 agent endpoint (added by the founder 2026-10-10) | Closed 2026-10-10: other apps on the phone use the model through a keyed OpenAI-format endpoint on loopback, off by default; the model servers themselves now need a key too. No file access for other apps. | gates/gate-7-agent-endpoint.md, docs/benchmarks/2026-10-10-agent-endpoint.md, docs/AGENT-API.md |
| G8 tool calling and file search for other apps (added by the founder 2026-10-10) | Closed 2026-10-10: OpenAI-format tool calls (8 of 8 right tool on 12 prompts) and a file search route behind its own switch that returns only what a paired phone would get; the private file never came back; 3 of 3 full loops correct in about 9 to 10 s. | gates/gate-8-agent-tools-files.md, docs/benchmarks/2026-10-10-agent-tools-files.md, docs/AGENT-API.md |
| G9 adversarial audit, hardening, keep busy (founder goal 2026-10-10) | Closed 2026-10-10 on two phones: seven audits triaged, 13 work-losing or leaking defects and about 50 hardening items fixed, a keep-busy mode (63 to 84 % of the time generating, at most 0.6 s added to a real request), GPU start 18 s to 13 s; two ideas rejected by measurement; two switches left off until three phones can judge them. Open: update Phone A, three-phone re-checks. | gates/gate-9-hardening.md, docs/review/2026-10-10-adversarial-audit.md, docs/benchmarks/2026-10-10-hardening-checks.md |

Other evidence: throughput on three phones, 30.91 tok/s (28.90 to 31.94) against 12.03 for the fastest single phone, two phones 18.83 in one run (docs/benchmarks/2026-10-10-e2e2-swarm-throughput.md); Knowledge Bank on three phones with no sync call: two shared saves and three revokes were each listed on both other phones at the first poll, under 0.8 s; with the author's phone stopped the other phone answered from a card in 40 and 47 ms, with the author, the time and two sources; the receiving phones log kb.sync entries (docs/benchmarks/2026-10-10-knowledge-bank.md, "Third run"); in the second run a card answered in 35 ms, a revoke stopped its use and a tampered copy was refused (docs/benchmarks/2026-10-10-e2e2-knowledge-bank.md). Not measured: a phone that is away when the card is saved, the 3-minute timer, more than three phones. ExpertMesh: the 30B model of the three-phone run is a mixture-of-experts model (48 layers, 128 experts, 8 used per token) and ran split by whole layer ranges; individual experts were not placed on different phones, and the screen's remote-experts figures are arithmetic estimates.

## What is open (gate 6)

- The second visual pass is docs/design/qa2/REPORT.md: of the 17 first-pass defects 10 are fixed, 2 partly and 5 not checked, and new ones were listed. Eight further screen fixes were merged in 0a71f33 and all eight were seen fixed on Phone C (docs/design/qa3, 12 screenshots; small leftovers are listed in gates/gate-6-expertmesh-polish.md). The same session ran a smoke test of the final build: docs/benchmarks/2026-10-10-final-build-smoke.md.
- The demo build has never been exercised end to end: no Choir run, no split, no swarm batch, no Data Union ask on `com.ustaad.app` (docs/BUILD.md lists three curl checks run on phone C with no recorded result).
- Two things only the founder can do by hand, on every phone: the battery exemption in first-run setup (untested as a fix, gates/gate-2-inference-baseline.md), and QR pairing in the demo build (the camera scan has never been exercised; docs/CLAIMS.md LK9).
- The phones overheat within minutes of sustained work: a 20-problem Choir run took all three to Thermal Status 2 to 3 in eight minutes (gate-3-agent-choir.md); decode on the GPU fell from 12.3 to 6.8 tok/s over ten minutes (docs/benchmarks/2026-10-10-llama-cpu-baseline.md). Demo segments stay short; Ustaad stays in front with the screen on.
- Not measured: the Knowledge Bank with a phone that is away when the card is saved, the 3-minute timer and more than three phones (docs/benchmarks/2026-10-10-knowledge-bank.md, "Third run").
- Not run, no file: a repeat of the 30B run (cold load, 5 minutes of generation, Thermal Status 2 or above), the 20-problem Choir run repeated after 08a205f, airplane mode and bytes to the internet, document scan and dictation by a person, the 20-minute screen-off soak, Office Kit mirroring (docs/CLAIMS.md section 4).

## Done (on master)

- All six gate artifacts above, the claims ledger (docs/CLAIMS.md, updated at 0ca3a92 and for MemoryCloud at the commit that carries this file), the novelty and applications reports (docs/research/07, 08), two code reviews (docs/review/), the visual QA (docs/design/qa/REPORT.md).
- App: Swarm link with signed requests and responses, pairing confirmation, Wi-Fi Direct on the station channel, Bluetooth control path, failover with dedup; llama-server and rpc-server as child processes, GPU with CPU fallback, restart policy; Agent Choir with typed answers, validation, retry and the strict scorer (JSON-fragment rejection and rounding merged after the run, unit-tested only); Data Union with Hindi OCR, audit page, private by default; MemoryCloud with planner, 10 s rpc lease (unit-tested only), degraded state and re-plan; scheduler with the throughput panel embedded in the Swarm screen; Knowledge Bank page off the Data tab; the ExpertMesh explanation screen (the mixture-of-experts model ran split by layer ranges, experts not placed on different phones); first-run setup, settings, Stop Ustaad; the `demo` build type (docs/BUILD.md).
- Documents written this session: docs/SETUP.md, docs/DEMO.md, docs/PITCH.md, docs/DECK.md, README.md and this file, updated three times against the evidence.

## State of the phones

As reported by the orchestrator at the second pass, with the three-phone MemoryCloud run (09:18 to 09:37) added from docs/benchmarks/2026-10-10-memorycloud-inapp.md. I have not touched or read a phone, so every line is "reported", not "verified".

| Item | A (10BFC41T2N001UZ, 192.168.242.89) | B (10BFAU14Q6000XR, 192.168.242.23) | C (10BFAT216Z000XQ, 192.168.242.76) |
|---|---|---|---|
| App installed | Debug base app `com.swarmos.app` only, no test variants; the debug build of 0a71f33 was installed on A, B and C at 10:27 with chat on the GPU, the embedder ready and each phone listing the other two (reported, docs/BUILD.md) | same | same |
| Demo build `com.ustaad.app` | Not installed (reported). docs/BUILD.md says build, install and three curl checks ran on phone C, so it may have been removed afterwards | not installed | see A |
| Models | Reported: the default chat and embedder in `/sdcard/Android/data/com.swarmos.app/files/models/`; READY on all three after the memory run | same | same, plus the 30B file, copied into the app's models folder and left there (docs/benchmarks/2026-10-10-memorycloud-inapp.md); a copy also remained in `/data/local/tmp/llama/models/` before that run |
| Warm caches | rpc tensor cache from the 30B run, 6792 MB (includes 1.4 GB of 4B tensors); gives the 65 s warm load | rpc tensor cache 5679 MB | not applicable (the driver reads the file) |
| Demo corpus | Folders named `SwarmDemo` under Pictures and Documents (scripts/make-demo-corpus.py); `IMG_2231.jpg` is on B in the second run | same, with the bill photo `IMG_2231.jpg`; all-files access for `com.swarmos.app` was set to allow there (left in place, docs/benchmarks/2026-10-10-e2e2-data-union.md) | same |
| Pairing secret | The debug dev secret `swarmos-dev-secret` on all three in the second run; unknown since | see A | see A |
| Left over | At 06:21 `/data/local/tmp/tput` and some other workers' files remained on A and C (docs/benchmarks/2026-10-10-swarmlink-throughput.md); confirm against the clean-up rule (one Ustaad app, the models, the corpus, plans/briefs/_common.md) | Phone B was reserved from 03:40 to about 07:20, then released | The Wi-Fi Direct group of the memory run was stopped on all three |

Network: the PC and the phones were on the access point "Chulbul" (192.168.242.x). Phone A once left it by itself for a saved network "iQOO" at 06:22, cause not found (docs/benchmarks/2026-10-10-e2e-choir.md, F5).

## What the founder must do by hand

- On each phone, first-run setup: allow nearby devices, allow notifications, turn off battery limits for Ustaad (the Android dialog); on vivo and iQOO also "Allow background start" (docs/SETUP.md section 4). The demo build ignores adb extras, so there is no adb path for this.
- Install the demo build on all three phones, push the two models into `com.ustaad.app`'s own folder and, for step 4 of the demo, the 30B file into Phone C's `com.ustaad.app` models folder (18.6 GB, USB cable); name the phones in Settings (docs/SETUP.md sections 2, 3, 5). Load the 30B once cold (319 s) before the judges arrive so the lenders keep their caches.
- Pair by QR scan in the demo build: host on one phone, scan on the others; or the paste route (docs/SETUP.md section 7). Rehearse it: the camera scan has never been exercised.
- Index each phone in the Data tab (Photos "Index", Documents "Pick folder"): the folder picker cannot be driven over adb.
- Keep Ustaad in front with the screen on on every phone during the demo; phones cool, on chargers, fan on.
- Decide the hackathon track and the closing ask (docs/PRD.md section 10 question 1 is unanswered; docs/DECK.md slide 12).

## Next (in this order)

1. Install the demo build on all three phones, run the three curl checks in docs/BUILD.md, and run every demo step once on it: a swarm batch, the warranty question with the pair, Choir `m3`, the 30B split. Record the results in docs/benchmarks/ (a new e2e note) and update docs/CLAIMS.md.
2. Rehearse docs/DEMO.md once with a recording and the thermal state logged; time the fresh start of the 30B split after a Stop with warm caches (only a re-plan, 65 s, was timed).
3. Second visual pass (gate 6): reshoot the frames listed in docs/DECK.md on the demo build, including one real Choir run and one real swarm batch; fix the top defects in docs/design/qa/REPORT.md and the open items in docs/review/2026-10-10-second-review.md (the Data Union index guard when the embedder stops mid-run; the debug-only intent extras).
4. Reshoot the frames the deck uses from the final build if wanted (docs/design/qa3 has the fixed screens); run the founder rehearsal list in gates/gate-6-expertmesh-polish.md; then measure the Knowledge Bank with a phone away at save time (docs/CLAIMS.md KB3).
5. By a person: camera QR scan, document scan, dictation, citation tap, a Verify of the audit log, the 20-minute screen-off soak, airplane mode with the Direct group, Office Kit mirroring (docs/CLAIMS.md M6 to M8).
6. Do the final rename to `com.ustaad.app` for the debug build and the code identifiers last.

## Contradictions between documents (decide, then fix the older one)

| Where | Says | Other says |
|---|---|---|
| docs/PRD.md section 12, MemoryCloud row | The 30B run on three phones "is pending" | gates/gate-5-memorycloud.md: it ran, one run |
| docs/models.md "Large model status" | The 30B file is still to be pushed to phone C | It was copied into C's app models folder (docs/benchmarks/2026-10-10-memorycloud-inapp.md) |
| docs/PRD.md R5.3 | 6 to 10 tok/s for the 30B on three phones | Measured 5.7 to 6.4 tok/s; the memory note calls this "inside the research estimate" |
| docs/PRD.md sections 4, 5.1, 9 | Hotspot first, Ed25519, 4-byte framing; tap a citation and the photo opens on B; "0 bytes to WAN" | Section 12, gate-1 and docs/CONTRACTS.md: Wi-Fi Direct first, HMAC, HTTP and JSON; a notification the owner taps; a "No internet" stamp (docs/CLAIMS.md PS2) |
| docs/PRD.md R3.1, section 7 | Alt-solver from a second model family | Built: one model, different prompt and temperature (docs/CLAIMS.md CH5) |
| docs/PRD.md R5.2 | Qwen3-14B for two phones | docs/models.md pins no 14B file; gate-5 criterion was written for two phones and met on three |
| CLAUDE.md, gate-0 | Wi-Fi Aware "feature flag absent" | Probes list `WIFI_FEATURE_AWARE` and a `wifi-aware0` interface; only the service is absent |
| gates/gate-3-agent-choir.md | One-phone score 14 of 20 | docs/benchmarks/2026-10-10-choir-answer-quality.md: 15 of 20 on phones A and B (a different run); the e2e2 file notes it |
| gates/gate-4-private-data-union.md "Deltas from plan" | Acceptance pair not run | "Second run" in the same file: it ran |
| docs/BUILD.md | An adb deep link works only on debug builds | MainActivity.kt: the `swarmos://pair` link is read by every build; the demo build asks for confirmation (not verified on a demo install) |
| docs/CLAIMS.md section 0 | 572 unit tests (inapp-llm) | docs/BUILD.md: 699 at 0a71f33 (695 at 1f514b6); 618 on the Choir branch earlier; counts of different commits |

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

## Where the evidence lives

- Gates: gates/gate-0 to gate-5. Benchmarks: docs/benchmarks/ (the root README.md lists them by topic). Claims: docs/CLAIMS.md. Novelty and applications: docs/research/07, 08. Screenshots: docs/design/qa/ (QA on phone C), docs/design/shots/ (module frames, and the two measured MemoryCloud frames).
- Raw results of the runs: docs/benchmarks/raw/ (e2e-run1, e2e-run2, memorycloud; docs/benchmarks/raw/README.md).
- Scripts that produced the numbers: scripts/e2e-check.sh (first run), scripts/e2e2-check.sh with scripts/e2e/e2e2.py (second run); the three-phone MemoryCloud procedure is in docs/benchmarks/2026-10-10-memorycloud-inapp.md.

## Open questions for the founder

1. Hackathon track: Productivity, Developer Tools or Open Innovation (docs/PRD.md section 10, question 1; not answered in any file).
2. Whether the organisers allow three phones on stage and a laptop for Office Kit, how HackTracker counts Office Kit and creative phone use, and which of Red Light (phone only) or Green Light (phone and laptop) a mirror-only laptop is (docs/research/06-hackathon-context-and-landscape.md).
3. The closing ask on the last slide (docs/DECK.md slide 12).
4. Whether to file anything before further public disclosure: the novelty report notes India has no general grace period for a public demo and that this is not legal advice (docs/research/07-novelty-and-prior-art.md section 4).

## Tools

- `scripts/adb-connect.sh` reconnects the phones; `scripts/watch-phones.sh` watches the set.
- `scripts/swarm-call.sh <ip[:port]> <METHOD> <path> [json]` sends a signed Node API request with the dev secret (debug builds only); `scripts/e2e-check.sh` and `scripts/e2e2-check.sh` drive the end-to-end checks (`e2e-check.sh` refuses phone B, docs/CLAIMS.md section 4; `e2e2-check.sh` takes up to three addresses).
- `scripts/gradle-locked.sh <gradle root> <args>` caps concurrent builds at two.
- `scripts/make-demo-corpus.py --push-only` pushes the demo corpus; `scripts/probe-device.sh <serial>` writes a full device probe; `scripts/tput/` is the native throughput probe.
- `native/build-llama-android.sh`, `native/package-llama-android.sh`, `native/fetch-models.sh` and `native/bench/` rebuild the runtime and rerun the benchmarks.
