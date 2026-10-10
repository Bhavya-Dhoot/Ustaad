# Gate 9 — Adversarial audit, hardening and the keep-busy mode (closed 2026-10-10 by the orchestrator for what two phones can show; three-phone checks are open until Phone A is released)

Founder goal of 2026-10-10 13:25: a proper adversarial end-to-end audit with Codex and cheap subagents; throughput and efficiency gains; production-grade failure handling with fallbacks; memory and thermal handling; and a mode that keeps the phones from sitting idle. Evidence: docs/review/2026-10-10-adversarial-audit.md (the audit and its triage), docs/benchmarks/2026-10-10-hardening-checks.md (everything measured on phones B and C), docs/benchmarks/2026-10-10-slots-experiment.md and 2026-10-10-gpu-settings-sweep.md (ideas rejected by measurement). Master b8b4777: 1406 unit tests, 0 failures (970 before the gate), debug and demo APKs built, debug build on phones B and C since 15:01.

## Decisions
1. Seven audits in parallel (Codex and six Sonnet reviewers), one orchestrator triage, five builders split by package, each finding confirmed in the code by its builder before being fixed. 13 findings that could lose work, leak data or hang, and about 50 hardening items, are fixed on master. Nothing changed the wire format: Phone A stays on the older build and is shown by B and C as "runs an older version".
2. Keep busy: a Settings switch, off by default. An idle phone runs short filler jobs through its own model; real work cancels the filler in flight; heat steers the duty (full at thermal status 0 to 1, half at 2, pause at 3 or more); it runs only with the app in front, or charging with the battery exemption, because the vendor OS has killed this app when it generated with the screen off.
3. Optimisations become defaults only when a phone measures them better. Kept: the OpenCL program cache (GPU start 18 s to 13 s), heat-aware routing, dispatch that never waits on a slow peer, a bounded model-server cache. Rejected by measurement: two model slots per phone (throughput halves) and five model-server settings (all within 2 %). Built but left off until three phones can judge them: leaner sources for Data Union answers, and Choir skipping round 2 on a two-of-three majority.
4. Failure handling now has a rule per case, each with a test: a model that will not fit is refused before anything is stopped; a GPU server that dies twice falls back to the CPU; a frozen server is killed and restarted; a cut answer is an error, not an answer; a lender gets its models back when its driver disappears; a private folder is private atomically; a revocation is atomic; a batch survives its coordinator as "interrupted"; a long job has its own time budget and no longer marks a healthy phone down.

## Measured (phones B and C; details and limits in the hardening-checks file)
| What | Value |
|---|---|
| Keep busy, 16 minutes | Phone B generated 63 % of the time, Phone C 84 %; full duty until thermal status 2, then half; never reached status 3; 495 filler jobs, 0 failed |
| Delay keep busy adds to a real request | 0.07 to 0.59 s outside the model against 0.04 to 0.13 s with the mode off; 9 of 9 real requests answered |
| GPU server start | 17.9 to 20.6 s before, 12.1 to 13.4 s now |
| Oversized model load | refused in 0.4 s with the numbers in the message |
| Server killed mid-answer | error returned, restart in 16 s |
| Server frozen | killed and restarted after 30 s (failed on the first build of the fix, passed on the second) |
| Two GPU deaths in two minutes | CPU build with a notice; GPU restored by its switch |
| Driver killed two seconds into a split start | lender had its own models back after 27 s |
| Folder made private, app killed 0.18 s later | still private after restart; a peer gets nothing |
| Two phones against the sum of each alone | 17.4 against 17.47 tok/s (99.6 %) in two runs; 85 % in the one earlier run |
| Lean sources (off by default) | first token 5.09 s to 2.71 s median over 12 asks each, same facts right (11 of 12) |
| Agent endpoint and tool calling on the final build | all checks pass |

## Deltas from plan
- The hung-server detection did not work on a phone the first time; the builder could not name the cause but found that a frozen process was being sent a signal it cannot act on, and that calls in flight excused silence for ten minutes. The second build passed.
- Phone B restarted at 14:16 during the first keep-busy test; its boot reason is an ordinary reboot and the founder was handling the phones. The later 16-minute runs on both phones completed without incident.
- Two thermal decision functions now exist (one in `core` for loads and benchmarks, one in `sched` with hysteresis for routing). They agree on the thresholds; merging them is left.
- The orchestrator's own device watcher ran a read-only `getprop` on Phone A every 20 s from 13:12 to 14:07 in breach of the reservation. It is replaced by scripts/watch-devices.sh, which only parses the device list.

## Open
- Phone A is on the older build (c2ca589). Until it is updated: no Wi-Fi Direct tie-break with it if it also hosts, no versioned request signature (a captured request can be replayed within 30 s on a shared Wi-Fi), and none of the fixes on that phone.
- Not measured, because they need three phones or radio changes: the large-model split, Choir and three-phone throughput on this build; the two switches left off; the Bluetooth path fixes; an address change; a real LAN stall against the new heartbeat timeout.
- Keep busy holds a phone at thermal status 2 on half duty. Real work that arrives then runs on a warm phone: Phone C decoded at 10.9 tok/s after 16 minutes of filler against 13 to 14 when cool. The mode trades peak speed for steady use; leave it off before a timed demo run.
- The time outside decoding in short jobs (about 0.8 s per job) is inside the model server; not yet reduced.
- A coordinator that dies mid-batch leaves an "interrupted" report; nothing resumes the batch.
- Every measurement is one run on the debug build.

## Ledger
- Audits: Codex (requested gpt-6.1-sol, high effort; it reported itself as GPT-6) and six Sonnet reviewers, about 2.0M tokens. Fixes: five Sonnet builders in worktrees (link, runtime, MemoryCloud, data, scheduler with keep busy), about 2.7M tokens. Triage, experiments (slots, GPU settings), all phone checks, merges and this artifact: orchestrator.
