# Gate 9 on the phones: keep-busy mode, fault injection, and what the changes cost or gained (2026-10-10, 13:27 to 15:45)

Phones B (192.168.242.23) and C (192.168.242.76), iQOO 15, debug build `com.swarmos.app`. Phone A was reserved by the founder and was not used; its app was not answering whenever B and C were asked for their peers (it was absent from their lists at 14:33 and "away, runs an older version" at 15:25 and 15:37), so nothing was sent to it. Builds: the checks below ran on master b8b4777 (all five hardening branches, 1406 unit tests, 0 failures; installed on B and C at 15:01) unless a row names another build. Chat `Qwen3.5-4B-Q4_K_M` on the GPU, embedder `embeddinggemma-300M-Q8_0`. One run of each check unless a count is given. Raw results: `raw/gate-9/`. Scripts: `scripts/hardening-check.py`, `memcloud-check.py`, `privacy-check.py`, `phone-probe.py`, `preempt-probe.py`, `bench-batch.py`, `lean-sources-ab.py`, `agent-check.py`, `agent-check2.py`.

Phone B restarted at 14:16 during the first keep-busy test (lost at 14:07). Its boot reason reads "reboot", the ordinary requested kind, not a thermal or crash shutdown, and its thermal status was 1 to 2 when contact was lost; the founder was handling the phones at the time. Whether the restart was the founder's or the phone's cannot be told from here.

## 1. Keep busy (never idle)
Switch on, app in front, phone charging. Sampled every 15 s through `GET /v1/sched/busy` with the thermal status over adb.

| Phone, run | Span | Time generating filler | Thermal status seen | Filler jobs, tokens | Failed | Cancelled by real work |
|---|---|---|---|---|---|---|
| B, final build | 953 s | 600 s (63 %) | 0 for 2 min, 1 for 3 min, 2 for 11 min | 220 jobs, 4722 tokens | 0 | 2 |
| C, final build | 952 s | 796 s (84 %) | 0 for 3.5 min, 1 for 8 min, 2 for 4.5 min | 275 jobs, 5928 tokens | 0 | 3 |
| B, first build of the mode (4bde740) | 523 s until contact was lost | 373 s (71 %) | 0, 1, then 2 | 141 jobs, 2964 tokens | 0 | 3 |

What it did: at status 0 or 1 the phone generated 99.5 % of the time; on reaching status 2 it dropped to half duty (49 to 51 %) and went back to full when the phone read 1 again. Phone B settled at status 2 on half duty for the last 11 minutes; Phone C moved between 1 and 2. Neither reached status 3 in 16 minutes, so the pause at 3 was not exercised on a phone.

What it costs a real request: the same 48-token request through the signed chat route, sent while filler was running and again with the mode off.

| | Time outside the model (wall-clock minus prompt and generation time) |
|---|---|
| Mode off, 6 requests | 0.04 to 0.13 s |
| Filler running, 6 requests on the final build | 0.07 to 0.59 s |
| Filler running, 3 requests on the first build | 0.08 to 0.36 s |

Every one of the 9 real requests cancelled the filler job in flight and was answered; none was refused. Other phones never saw a phone as busy because of filler (its `running` count stays 0). Not measured: the mode with the app in the background (it pauses by design), on battery, or over hours.

## 2. Fault injection on the model runtime (Phone C)
| Injected fault | Before (c2ca589) | Now |
|---|---|---|
| Reload of the GPU chat server | 20.6, 17.9, 17.9 s | 13.4, 13.1, 13.1 s with the OpenCL program cache (5 cached programs); 12.1 and 13.1 s on later starts on C and B |
| Load of the 18.6 GB model on one phone | started, thrashed swap, killed other apps (earlier spike) | refused in 0.4 s: "it needs about 22.2 GB free ... this phone has 10.0 GB available" |
| Chat server killed six seconds into a long answer | audit finding: a cut answer could be returned as complete | the call fails (HTTP 500, "EOFException"); the server is back in 16 s |
| Chat server frozen (`kill -STOP`) | never noticed | first try on build 71e2839: still not noticed in 120 s. After the fix (b8b4777): three missed pings in 30 s, killed, restarted |
| Chat server killed twice inside two minutes | restarted on the GPU, then gave up | runs on the CPU build with a notice; the GPU comes back when its switch is toggled (13.7 s) |

The error text for the killed-mid-answer case is the bare exception name, not a plain sentence; the behaviour is right and the wording is not.

## 3. MemoryCloud with a driver (Phone C) and one lender (Phone B), small model
| Check | Result |
|---|---|
| Start with a bad layer split `[0, 32]` | HTTP 400 "every phone needs at least one layer" |
| A loan asking the lender for 999999 MB | HTTP 400 "this phone has about 6269 MB available"; the lender's models untouched |
| Split start, lender cache cold, through the access point | READY in 88 s; 76 tokens at 3.9 tok/s (first try: my script gave up at 120 s on an earlier, slower start) |
| Stop sent to the lender with a wrong loan token | HTTP 409 "the loan belongs to Phone C"; the loan stays |
| Clear the lender's cache while lending | HTTP 409 |
| Stop | driver back in 17 s, lender in 1 s |
| Driver app force-stopped two seconds into a start (the lender had already stopped its own models) | the lender had its chat and embedding models back by itself after 27 s; the driver recovered 14 s after relaunch |

The 18.6 GB three-phone split was not repeated on this build (Phone A reserved).

## 4. Folder privacy when the app is killed (Phone C as owner, a peer-style query)
A peer's query for the electricity bill returned the photo. The folder was set private and the app force-stopped 0.18 s after that call started. After relaunch the folder read private and the same query returned nothing, with one file withheld. Sharing the folder again brought the photo back. The call had returned before the kill, so this shows the change survives a kill; the half-written window the fix closes is covered by a unit test of the repair, not by this run.

## 5. Scheduler: before and after
| Batch of 32 short jobs | Result |
|---|---|
| Phone B alone | 9.39 tok/s (decode 12.8) at thermal status 2 |
| Phone C alone | 8.07 and 8.08 tok/s (decode 10.8 to 10.9) at status 1 |
| B and C together, runs 1 and 2 | 17.40 and 17.41 tok/s; jobs 18 and 14, the faster phone took more |
| B and C together, run 3 (both decoding at 10.9) | 15.46 tok/s; jobs 16 and 16 |

Against the sum of the two phones alone (17.47) the first two runs are at 99.6 %. The one earlier two-phone run (docs/benchmarks/2026-10-10-e2e2-swarm-throughput.md) was at 85 % of its sum. Different temperatures and one earlier run, so this is not a controlled comparison; it does show no loss from the scheduler changes.
Phone C decoded at 10.8 to 10.9 tok/s in these runs against 13.4 to 14.1 earlier in the day with the same model and code path: it had just done 16 minutes of filler. Heat, again, is the largest effect.
The new per-job fields show where the rest of the wall-clock goes: prompt processing took a median of 0.81 s per job, and jobs waited 0 ms for dispatch. The time outside decoding is inside the model server per request, not in the network or the scheduler.

## 6. Leaner sources for a Data Union answer (switch off by default)
Phone B asking, Phone C as the only online peer, six questions, each arm twice (12 asks per arm), every ask preceded by a different question.

| | First token, median (range) | Whole answer, median | Expected fact in the answer |
|---|---|---|---|
| Sources as before | 5.09 s (0.60 to 6.88) | 8.93 s | 11 of 12 |
| Lean sources | 2.71 s (1.83 to 4.53) | 6.63 s | 11 of 12 |

The one miss in each arm is the same question (the restaurant total, answered 960.00 instead of 1,056.00), so it is not caused by the switch. The lean arm ran later and hotter (status 2 against 1), which works against it. Not run: the two-file warranty question with Phone A's certificate, which is the case the gate could harm; the switch stays off by default until that is checked with three phones.

## 7. Agent endpoint on the final build (Phone C)
`agent-check.py` a, b, c, d, f, g, j and `agent-check2.py` t, u, v, w, x, s (searches on this phone only) all passed: key and loopback checks, chat, stream, schema, embeddings, one call at a time, tool selection 8 of 8 and 4 of 4, tool_choice modes, the OpenAI client's streamed tool call, file search, one full loop, refused schemas.

## Rejected by measurement in this gate
Two model slots per phone (docs/benchmarks/2026-10-10-slots-experiment.md) and five model-server settings (docs/benchmarks/2026-10-10-gpu-settings-sweep.md).

## Not measured
Anything with three phones on this build (the split of the large model, Choir, the "majority skips round 2" switch, three-phone throughput); Bluetooth path fixes (they need Wi-Fi switched off on the phones); the changed heartbeat timeout under a real LAN stall; an address change of a phone; the low-memory reaction; long runs. Chat server memory over a session with the bounded prompt cache: the resident size read 0.8 GB after load in every state read, but a session-long figure was not taken.
