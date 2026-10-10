# Visual pass 2 on phone C (2026-10-10, 09:42 to 10:01, master 3a55086 code 1f514b6, debug build)

35 screenshots in this folder (`NN-name.png`), taken on phone C with real models on the GPU, the demo corpus and both peers online. Written by the orchestrator from the device tester's text (the tester could not write this file). Phones A and B were driven through the API only; their screens were not captured. The black "HackTracker by" pill is the hackathon's telemetry app.

Install: `adb install -r` on A, B and C, all "Success", no `pm clear`. Chat READY on GPU and embedder READY on all three at 09:42 and 10:00. Thermal status 0 at every read.

## First-pass defects (docs/design/qa/REPORT.md)
| # | Defect | Now |
|---|---|---|
| 1 | Hatched ONLINE stamp | fixed (plain "online") |
| 2 | Choir shows FAKE with a real model loaded | fixed |
| 3 | Settings: clipped Save, duplicate switch | fixed |
| 4 | Connector crosses the caption, lines straight | fixed; the right-hand line is still nearly straight |
| 5 | Swarm home developer text, "1 links", two primaries | fixed |
| 6 | Host page QR and band text | not checked |
| 7 | Join: stale message, bare scanner | partly: corner brackets drawn; stale message not checked |
| 8 | Throughput panel header and empty hero | fixed |
| 9 | Models stopped state | not checked |
| 10 | Models ready state | fixed |
| 11 | Benchmark numbers after unload | not checked |
| 12 | Choir compose hairlines and wording | fixed |
| 13 | No solo Choir run | not checked |
| 14 | Ask: keyboard, answer placement | fixed |
| 15 | Audit Verify result off screen | fixed |
| 16 | Scan review | not checked |
| 17 | Memory tab title, caption, buttons, subtitle | partly: title is the file name and switches show state; the subtitle "too big for one phone" is still unconditional |

## New defects
### [pdu]
- The saved-card block inside a live answer cuts its second source to "Phone A ·" with no file name (07, 08, 10, 11).
- The query audit lists every file twice and says "1 phones" (28, 30).
- Knowledge bank rows are identical, with no time on them; only the stamp tells them apart (12).
- Saved-answer markers [1] [2] are plain text, and an uncited [3] stays plain beside chips (04, 09).
- "Phone A did not answer" is small text under the answer (10).
- A live answer with Phone A away talked about its sources wrongly ("Source [3] incorrectly references source [2]") (09). Model behaviour, not layout.

### [shell]
- The navigation bar floats above the on-screen keyboard (03).

### [llm]
- The embedder name wraps as "Q8_" / "0"; the "22 tok/s prefill" figure comes from a 13-token request (23).

### [choir]
- Problem rows waste space (15); "refuted" has no legend (19). The result screen is good (21).

### [memcloud]
- The file name breaks mid-word beside the TOO BIG stamp (25).

## Not reached
Host and QR, the Join stale message, Swarm with no peers, Models stopped and loading, Benchmark thermal rows, a solo Choir run, Data "not indexed", Scan, Memory loading, running, dropped and ExpertMesh (see docs/design/shots/memcloud-ready-30b.png and memcloud-degraded-30b.png for the running and dropped states), the too-hot and NO INTERNET stamps.

## Status
A polish pass for the [pdu], [shell], [llm] and [memcloud] items above is in progress; the [choir] items are left as they are.
