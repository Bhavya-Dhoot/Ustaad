# Ustaad demo runbook (judges, 6 minutes maximum)

Rewritten 2026-10-10 against master e2d41e4 (08:25 IST). docs/CLAIMS.md is binding: a sentence it marks UNPROVEN or FALSE AS STATED is not said here; it appears only in the "What we do not claim" box or in the "If measured by then" box. A number in parentheses is followed by the file it comes from. Setup is in docs/SETUP.md, the one-page pitch in docs/PITCH.md, the slides in docs/DECK.md.

The script uses only steps that have run on phones. Its time slots are a plan. No rehearsal has been recorded, so do not state a demo time as a result until one has (docs/CLAIMS.md R20, UX6). The second end-to-end run is in progress (second run pending: 2026-10-10-e2e2-*.md); where this file says "pending" it names the file that will hold the number.

## Layout on the table

- The presenter holds one phone and coordinates (A or C; B never coordinated, docs/CLAIMS.md SC6). Say "A or C", not "any phone".
- The other phones lie face up, charging, fan on them, Ustaad in front, screens on. The demo build, paired by QR (docs/SETUP.md sections 2 and 7).
- Stop rule: if the strip says Severe or shows the Hot stamp, stop the live step and use its fallback. Sustained use reached Thermal Status 4 within 10 minutes and decode fell from 12.3 to 6.8 tok/s on the GPU (2026-10-10-llama-cpu-baseline.md).
- Say "numbers on the Swarm and Models screens are measured by the app" and nothing broader (docs/CLAIMS.md R20).

## Timed script (planned slots)

| Slot | Step | Shown | State |
|---|---|---|---|
| 0:00 to 0:50 | 1. The swarm | Swarm tab, Measure link | Proven on A and C |
| 0:50 to 2:20 | 2. Data Union | Ask, sources, audit | Proven on A and C, CPU build |
| 2:20 to 3:50 | 3. Agent Choir | One run of `m2` | Mechanism proven; accuracy not claimed |
| 3:50 to 5:10 | 4. MemoryCloud | 4B split over two phones, then the measured 30B result | Mechanism proven; slower by design |
| 5:10 to 5:50 | 5. Close and limits | none | |
| 5:50 to 6:00 | Buffer | | |

## Step 1. The swarm (0:00 to 0:50)

Presenter (phone in hand): Swarm tab. The topology shows the phones and the links. Tap a peer, then "Measure again (100 MB)" and read the Mbps, round trip and Frequency rows.

Judges see: phone tokens (A teal, B tangerine, C magenta), the strip with thermal, battery, memory and link, a live Mbps figure and round-trip rows.

Say: "Phones that find each other, and a Wi-Fi Direct link between them. This speed is measured by the app right now."

Numbers: Wi-Fi Direct 640.7 to 724.9 Mbps raw and 790 to 920 Mbps with 4 to 8 streams, A and C, 200 MiB; through the access point 89.2 to 102.2 Mbps (2026-10-10-swarmlink-throughput.md). TCP round trip averages 6.3 to 8.3 ms on Direct; a signed request through the app averages 20 to 28 ms (same file). If asked about the channel: the group must sit on the station's channel; off it the link fell to 10.3 Mbps against 426 Mbps, one run each (same file, section 1). Say "we measured this, we did not invent it".

Do not say: the link is Wi-Fi 7 (the standard of the Direct interface cannot be read), or that it works with no internet (airplane mode was not run).

Fallback:
- Measure reads low or fails: the peer is busy, hot or frozen. Bring it to the front, press "Quick check (10 MB)", then read the earlier-runs list.
- No Direct group: "Host a swarm", "Use current Wi-Fi instead", and quote the access-point figure above.
- Peer missing: type its IP in "Peer IP or ip:port" and Add.
- Pairing is done before the judges arrive. The camera scan has never been exercised; the paste route is the backup (docs/SETUP.md section 7).

## Step 2. Data Union (0:50 to 2:20)

Presenter: Data tab, "Ask the swarm". Type or dictate "Where is the warranty bill for my laptop?" and tap Ask. Then open "Query audit". Optional, if time allows: the control question "Boiling point of liquid nitrogen", and one question that matches a folder you left unshared.

Judges see: a timing strip (embed, search, one round-trip figure per peer, first word, total), source cards with phone tokens, the answer, then in the audit page "Sent from this phone" (the question text only, with its byte count) and "Returned by each phone" (docs/design/qa/32-data-audit.png, a frame from the QA run).

Say (novelty statement 3, exactly as worded in docs/research/07-novelty-and-prior-art.md): "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file." Caveat to say if asked: peer-to-peer snippet retrieval is published (DRAG, 2025); the claim is this attribution and consent behaviour shipped on phones, for which no earlier description was found, and the search was limited.

Numbers (A and C, real models, CPU build, debug build; 2026-10-10-e2e-data-union.md and gate-4-private-data-union.md):
- Source cards appear after 41 to 143 ms, before the model writes.
- The first word of the answer took 3.2 to 12.4 s on new questions; a repeated question started after 0.30 and 0.49 s. The first ask of the warranty question took 4.5 s to the first word and 8.4 s in total. Say that a repeat is faster (cause not checked). The 2 s and 6 s target was met only on repeats.
- The certificate on A was found in 3 of 3 asks. The Hindi form of the warranty question found the English certificate (cosine 0.552, next 0.375). An unrelated question returned no sources and no model call in 94 ms; one question about a missing fact showed four sources while the answer said nothing relevant was found.
- Before a folder was shared, Phone C withheld 1 item and nothing from C reached A. Three cases were tried.
- Query embedding 25 to 57 ms, peer round trip 54 to 232 ms (gate-4-private-data-union.md).

Do not say: "fully private"; "first visible text in 2 s and the full answer in 6 s"; that tapping a citation opens the file (it sends the owner a notification the owner taps, and no person has tried it, docs/CLAIMS.md R10); that an absent phone is flagged Incomplete as a demonstrated result (the stamp is built, no run produced it, R25); anything about the photo on B (pending, below).

Fallback:
- A peer does not answer: the answer is flagged and the phone is named; read the sources that arrived.
- Model slow: the source cards are already on screen; read the cited snippets.
- Index empty: do not re-index live. Show docs/design/qa/30-data-ask-answer.png and 32-data-audit.png and say they are frames from the QA run on phone C.

## Step 3. Agent Choir (2:20 to 3:50)

Presenter: Choir tab. Pick `m2` from "Try one" (rehearse it; in the first run it took three rounds and ended on the correct 384 with a "consistent" label, 2026-10-10-e2e-choir.md). Leave "Check against the problem's test" off and say so. Tap Run. Do not demo `m8`: that run ended on a wrong answer (docs/CLAIMS.md CH1).

Judges see: one lane per phone, answer chips, a Disagree stamp if the answers differ, round labels, the tool-check line, a baseline row with its wall clock, and totals for rounds, tokens and wall clock.

Say (novelty statement 2, exactly as worded in the novelty report): "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Caveat to say if asked: tool-checked selection, self-consistency and equal-compute comparison are published methods; the claim is the product presentation, and it holds only for problems that have a deterministic checker. With the test off the checker only recomputes the arithmetic the models wrote; on built-in problems with the test on, it compares against a stored answer key (docs/CLAIMS.md R6).

Numbers:
- One phone alone got 15 of 20 built-in problems right (mean 9.2 s) and one phone sampling three times got 18 of 20 (mean 26.0 s), strict scorer, no degenerate answers, one run per problem, phones A and B mixed (2026-10-10-choir-answer-quality.md). This is one-phone numbers only.
- The two-phone Choir arm was not re-run after the answer-quality fix. In the earlier run on 10 problems with a lenient scorer, Choir took 27,762 ms per problem against 14,458 ms for one phone (2026-10-10-e2e-choir.md). Do not say Choir is faster or more accurate than one phone.
- Roles differ by prompt and temperature (0.3 and 0.6) on one model, Qwen3.5-4B. If no answer has a majority a judge pass runs, on the coordinator with two phones (docs/CLAIMS.md R7).

Do not say: two model families; critic or rotating judge; "smarter"; that Choir verifies independently.

Fallback:
- A lane shows No answer, Timed out or Away: it is left out of the vote and the run goes on (choir/ChoirScreen.kt); say so.
- All phones agree in round 1: say they agree and read the stamp as shown.
- Phone hot: the eval refuses to start; talk over the stored numbers above instead.

## Step 4. MemoryCloud (3:50 to 5:10)

Do this step last: starting a split stops the chat and embedding servers on both phones and restores them on Stop (2026-10-10-memorycloud-inapp.md).

Presenter (driving phone C, worker phone A, the pair that was measured): Memory tab, pick `Qwen3.5-4B-Q4_K_M.gguf`, "Load across 2 phones", wait for READY (12.0 s with the worker's cache warm, 28 s cold), ask for a short answer, read the tok/s and first-token time. The screen itself says the model fits one phone and splitting it is slower. Then "Stop".

Judges see: a one-row layer strip with a colour per phone, per-phone memory, the streamed answer and the speed.

Say: "This model fits on one phone, so splitting it is slower, and the app says so. We show it to prove the mechanism. The case it is for is a model that does not fit." Then: "The 30B model, 18.6 GB, gave no token on one phone in 134 s and did not load across two. A three-phone run is not done yet."

Numbers (phones C and A, Wi-Fi Direct, 4B, debug build; 2026-10-10-memorycloud-inapp.md):
- Split: 6.86 and 6.98 tok/s, first token 2.31 and 2.59 s; the same model on C alone 10.80 and 14.69 tok/s (about 0.47 to 0.64 of the one-phone rate, docs/CLAIMS.md MC2).
- The worker's cache cut the load from 28 s to 12.0 s (same file).
- 30B: no token in 134 s, swap thrashing, 23 background apps killed on one phone; two phones did not load, the driver thrashed and the worker received 0.47 of 9.3 GB in 8 minutes (2026-10-10-rpc-split-spike.md, gate-5-memorycloud.md).

Optional, only with a laptop and adb on the table and rehearsed: force-stop Ustaad on the worker mid-answer. In one run the driver showed DEGRADED naming the phone after 1.2 s and a re-plan was READY after 12.0 s; the "resume" is a reload, not a continued answer (2026-10-10-memorycloud-inapp.md). Say "one run".

Do not say: that a model too big for one phone runs split (not proven); ExpertMesh figures as measurements (they are estimates, no mixture-of-experts model ran split); that the 10 s lease works (unit-tested only); the 4 to 10 tok/s estimates from the PRD.

Fallback: if the split does not load, open the Models tab benchmark and show the one-run table (GPU prefill 201.9 against CPU 71.6 tok/s, first token 2.59 against 7.20 s, memory 1032 against 5952 MB; 2026-10-10-inapp-llm.md), and say the split result from the spike: 4B over two phones 3.18 tok/s against 14.86 on one (2026-10-10-rpc-split-spike.md).

## Step 5. Close and limits (5:10 to 5:50)

No phone action. Say, in this order:
1. Novelty statement 1, exactly as worded in the novelty report, then its caveat in the same breath: "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root. Each part has prior art; this is the combination; the search was limited; the lease is unit-tested only and the 30B run on three phones is pending."
2. Limits: "Phones throttle within minutes. Android freezes the app when the screen is off, so Ustaad works while it is in front. Splitting a model that fits one phone is slower."
3. "Every number we showed is from a run on these phones; the ones we have not run are marked pending."

Do not close with the emergency-mesh line from the PRD: it needs a run in airplane mode that does not exist (docs/CLAIMS.md PS3).

## If measured by then (not part of the script)

Use a row only when its file exists and holds the number. Until then say nothing about it and use the fallback.

| Item | File that will hold it | Fallback that is already proven |
|---|---|---|
| 30B model across three phones: load time, first token, tok/s, memory per phone, 5-minute thermal, pull test | 2026-10-10-memorycloud-inapp.md ("Pending" section, procedure written) | Step 4 as written, with the measured 134 s result |
| Data Union on the GPU build and the PRD pair (bill photo on B, certificate on A): first word and total | 2026-10-10-e2e2-data-union.md (second run pending) | Step 2 numbers (CPU build) |
| 20 problems with the strict scorer on two phones, four arms | 2026-10-10-e2e2-choir.md (second run pending) | One-phone arms: 15 and 18 of 20 |
| Aggregate throughput, one phone against two and three, GPU on | 2026-10-10-e2e2-swarm-throughput.md (second run pending) | Earlier result, A and C, CPU: 15.38 against 7.66 tok/s, ratio 2.01, the two swarm runs 2.0 times apart, and the one-phone baseline was throttled (2026-10-10-e2e-swarm-throughput.md). Not a live step: 32 prompts took 116 to 123 s on one phone |
| Knowledge Bank: save on A, sync to C, stop A, answer on C, revoke | 2026-10-10-knowledge-bank.md ("procedure, not yet run"); results from the second run | Do not show it. Built, 27 unit tests, never installed on a phone |
| Airplane mode with the Direct group formed; bytes to the internet per app | none yet (docs/CLAIMS.md M6) | Do not say "no internet" as a result |
| Camera QR scan, document scan, dictation by a person; demo build run end to end | none yet (docs/CLAIMS.md M8) | OCR 161 and 165 ms per image is the only creative-use number (2026-10-10-e2e-data-union.md) |
| 20-minute screen-off soak with the battery exemption | none yet | "Works while in front with the screen on" |
| Office Kit mirroring of the presenter phone | none yet | Do not claim |

## 90-second version

| Time | Beat | Say | Number |
|---|---|---|---|
| 0:00 to 0:15 | Swarm tab, last Measure result already on screen | "Phones that find each other, and a Wi-Fi Direct link, measured by the app." | 790 to 920 Mbps with 4 to 8 streams (2026-10-10-swarmlink-throughput.md) |
| 0:15 to 0:55 | Data Union: the warranty question, then the audit page | Novelty statement 3, exactly as worded | Source cards after 41 to 143 ms (2026-10-10-e2e-data-union.md) |
| 0:55 to 1:20 | Choir: show a run finished before the judges arrived (that the tab keeps the last run after you leave it is not verified; test it) | Novelty statement 2, exactly as worded | One phone alone 15 of 20, three samples 18 of 20 (2026-10-10-choir-answer-quality.md) |
| 1:20 to 1:30 | The limit | "Splitting a model that fits one phone is slower: 6.86 and 6.98 tokens a second against 10.80 and 14.69." | 2026-10-10-memorycloud-inapp.md |

## What we do not claim

- "First", "only" or "we invented": LinguaLinked (2023), prima.cpp (ICLR 2026), Voltron (July 2026), edge-llm-cluster and exo's 2024-25 demos are prior art for splitting a model over phones; channel pinning, QR-started Wi-Fi Direct and Bluetooth hand-over are in earlier patents and Nearby Connections; hash-chained logs date from 1998; DRAG (2025) retrieves snippets peer to peer (docs/research/07-novelty-and-prior-art.md). Say "we measured it", "we use", not "we invented". Say "executed once", not "exactly-once delivery". Do not call the log tamper-proof: it is local and unsigned.
- Faster than one phone, or Choir smarter or faster than one phone (docs/CLAIMS.md CH2, CH3, MC2).
- Two model families, a critic, a rotating judge (CH5, CH6).
- Wi-Fi 7, 6 GHz, or an NPU speedup; Wi-Fi and Wi-Fi Direct together adding bandwidth (it was 0.3 to 0.5 times the speed of Direct alone, 2026-10-10-swarmlink-throughput.md) (LK14, LK16, RT9).
- "0 bytes to the internet", "no internet" as a result, or airplane-mode operation (PS2, PS3).
- "Fully private" or "zero-trust" (PS9). Say: private by design, files and embeddings stay on the phone, the question and short snippets cross the link signed but not encrypted.
- Works in the background, or the battery setting fixes it (LK7, UX3).
- Three phones as a workload; any model too big for one phone running split; any ExpertMesh measurement (X1, MC3, EM1).
- Camera, document scan or dictation as demonstrated; Office Kit; the in-app model download; the memory lease as proven; the Knowledge Bank as used on phones (R15, R17, R26, R20).

## Judging-criteria map

Weights are from docs/research/06-hackathon-context-and-landscape.md. Creative phone use and Office Kit usage are read from HackTracker device data; how it counts them is unknown (same file, open questions). The black "HackTracker by" pill in the QA screenshots is the hackathon's own telemetry app, not Ustaad (docs/design/qa/REPORT.md).

| Line | Weight | What earns it | Evidence | Honest gap |
|---|---:|---|---|---|
| End product quality | 30 | Steps 1 to 4 run on real phones with real models; the app says when a split is slower; degraded states are shown | gate-1, gate-2, gate-4, gate-5; docs/design/qa/ | 17 visual defects at 71f2359, several fixed since and not re-shot; Choir has no gate artifact |
| Novelty and impact | 20 | Novelty statements 1 to 3 with their caveats; private cross-phone search with owner-notification open; the honest split result; applications in docs/PITCH.md | docs/research/07, 08 | Every part has prior art; the claim is the combination |
| Creative phone use | 15 | On-device OCR including Hindi (161 and 165 ms per image), on-device LLM on the Adreno GPU, Wi-Fi Direct. Camera scan, document scan and dictation are built and not yet run by a person | 2026-10-10-e2e-data-union.md, 2026-10-10-inapp-llm.md | docs/CLAIMS.md R15 |
| Technical depth | 15 | Channel pinning finding, striped bulk on Direct, 700 of 700 requests executed once across two path failovers (paths silenced with a fault hook, Wi-Fi not switched off), GPU 2.8 times prefill and about 5.8 times less memory, signed requests and responses, the 30B negative result | 2026-10-10-swarmlink-throughput.md, 2026-10-10-inapp-llm.md, 2026-10-10-rpc-split-spike.md | One run each for several of these |
| Office Kit usage | 10 | Nothing claimed. To earn it: mirror the presenter phone to the laptop with vivo Office Kit and paste the pairing link by Office Kit clipboard. Neither has been tried; no vivo SDK is used (docs/research/06). Ask the organisers how HackTracker counts it and which of Red Light (phone only) or Green Light (phone and laptop) a mirror-only laptop is | docs/research/06 | Not verified |
| Demo and presentation | 10 | A script of steps that have run, a fallback for each, a separate box for what is pending | this file | No rehearsal recorded |
| Local model bonus | extra | Qwen3.5-4B (Apache-2.0) and EmbeddingGemma-300M (open-weight under the Gemma Terms of Use) run on the phone through llama.cpp v0.6.0; only the model download goes to the internet, and the download has not run on a phone; file hashes pinned | docs/models.md | Do not call EmbeddingGemma open-source (docs/CLAIMS.md R19) |

## Numbers you may say

| Claim | Value | File |
|---|---|---|
| Direct link, raw and striped, A and C | 640.7 to 724.9 Mbps raw; 790 to 920 with 4 to 8 streams | 2026-10-10-swarmlink-throughput.md |
| Access point, same phones | 89.2 to 102.2 Mbps | 2026-10-10-swarmlink-throughput.md |
| TCP round trip on Direct; signed request | 6.3 to 8.3 ms; 20 to 28 ms | 2026-10-10-swarmlink-throughput.md |
| Requests across two path failovers (a path was silenced with a fault hook, Wi-Fi was not switched off) | 700 sent, 700 ok, 700 executed once on C | 2026-10-10-swarmlink-throughput.md |
| Peer away after a force-stop; back after restart | 2.1 to 2.2 s; 1.0 s | 2026-10-10-swarmlink-g1.md |
| GPU against CPU, one run each, phone C | prefill 201.9 against 71.6 tok/s; first token 2.59 against 7.20 s; memory 1032 against 5952 MB | 2026-10-10-inapp-llm.md |
| Decode when sustained | 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10 on the GPU | 2026-10-10-llama-cpu-baseline.md |
| Choir answer quality, one phone | alone 15 of 20, three samples 18 of 20 | 2026-10-10-choir-answer-quality.md |
| Data Union | source cards 41 to 143 ms; first word 3.2 to 12.4 s on new questions | 2026-10-10-e2e-data-union.md |
| Split 4B over two phones against one | 6.86 and 6.98 against 10.80 and 14.69 tok/s | 2026-10-10-memorycloud-inapp.md |
| 30B on one phone | no token in 134 s | 2026-10-10-rpc-split-spike.md |
