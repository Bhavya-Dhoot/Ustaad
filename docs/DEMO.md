# Ustaad demo runbook (judges, 6 minutes maximum)

Updated 2026-10-10 against master 0a71f33 (gates 0 to 5 closed, gate 6 polish open; that build passes its unit tests and was installed on phones A, B and C at 10:27, docs/BUILD.md). docs/CLAIMS.md is binding: a sentence it marks UNPROVEN or FALSE AS STATED is not said here; it appears only in the "What we do not claim" box or in the "If measured by then" box. A number in parentheses is followed by the file it comes from. Setup is in docs/SETUP.md, the one-page pitch in docs/PITCH.md, the slides in docs/DECK.md.

The script uses only steps that have run on phones, each in one run on the debug build (gate-3-agent-choir.md, gate-4-private-data-union.md, gate-5-memorycloud.md). The demo build has not carried a split or a Choir run. Its time slots are a plan. No rehearsal has been recorded, so do not state a demo time as a result until one has (docs/CLAIMS.md R20, UX6). The Knowledge Bank replication measurement that was a blank has been taken (2026-10-10-knowledge-bank.md, "Third run"); what it did not measure sits in the "If measured by then" box. The screenshot check of eight screen fixes is finished: all eight were seen fixed on Phone C (docs/design/qa3); frames from the second visual pass are in docs/design/qa2/.

## Layout on the table

- Three phones, A, B and C. Hold Phone B for steps 1 to 3 (it coordinated Data Union, Choir, throughput and the Knowledge Bank in the second run, 2026-10-10-e2e2-*.md) and take Phone C for step 4 (it drives MemoryCloud and holds the 30B file, 2026-10-10-memorycloud-inapp.md). Rehearse the hand-over; C also asked Data Union questions in the second run. Say "A, B or C", not "any phone" (docs/CLAIMS.md SC6).
- All three lie face up, charging, fan on them, Ustaad in front, screens on. The demo build, paired by QR (docs/SETUP.md sections 2 and 7).
- Stop rule: if the strip says Severe or shows the Hot stamp, stop the live step and use its fallback. A 20-problem Choir run heated all three phones to Thermal Status 2 to 3 in eight minutes (gate-3-agent-choir.md); sustained use reached Thermal Status 4 within 10 minutes and decode fell from 12.3 to 6.8 tok/s on the GPU (2026-10-10-llama-cpu-baseline.md).
- Say "numbers on the Swarm and Models screens are measured by the app" and nothing broader (docs/CLAIMS.md R20).

## Timed script (planned slots)

| Slot | Step | Shown | State |
|---|---|---|---|
| 0:00 to 1:00 | 1. The swarm | Swarm tab, Measure link, one swarm batch | Measured on A, B and C |
| 1:00 to 2:20 | 2. Data Union | Ask, sources, audit | Measured on three phones, GPU |
| 2:20 to 3:40 | 3. Agent Choir | One run of `m3` | Measured on three phones, 20 problems twice |
| 3:40 to 5:20 | 4. MemoryCloud | The 30B model across three phones | Measured, one run |
| 5:20 to 5:55 | 5. Close and limits | none | |

If the script runs over, drop the live swarm batch in step 1 (read the stored numbers) and the optional pull in step 4.

## Step 1. The swarm (0:00 to 1:00)

Presenter (Phone B): Swarm tab. The topology shows the three phones and the links. Tap a peer, then "Measure again (100 MB)" and read the Mbps, round trip and Frequency rows. Then "Run on the swarm" in the Swarm load panel and let it finish (28 to 31 s in the second run, 2026-10-10-e2e2-swarm-throughput.md). The panel has not been run from the screen on the demo build; rehearse it.

Judges see: phone tokens (A teal, B tangerine, C magenta), the strip with thermal, battery, memory and link, a live Mbps figure, round-trip rows, and per-phone jobs and tok/s in the load panel.

Say: "Phones that find each other, and a Wi-Fi Direct link between them; this speed is measured by the app right now. Then one batch of 32 short prompts across all three phones."

Numbers:
- Wi-Fi Direct 640.7 to 724.9 Mbps raw and 790 to 920 Mbps with 4 to 8 streams, A and C, 200 MiB; through the access point 89.2 to 102.2 Mbps. TCP round trip averages 6.3 to 8.3 ms on Direct; a signed request through the app averages 20 to 28 ms. Off the station's channel the link fell to 10.3 Mbps against 426 Mbps on it, one run each (2026-10-10-swarmlink-throughput.md). Say "we measured this, we did not invent it".
- Three phones took 28.1 to 31.0 s for the 32 prompts, 28.90 to 31.94 tok/s (mean 30.91), against 12.03 tok/s for the fastest single phone (Phone C alone, 74.5 s): 2.57 times (2.40 to 2.65 over five runs). Two phones gave 18.83 tok/s, one run. GPU on all three, Thermal Status 0 or 1 at the start, through the access point with no Direct group, answers of 28.6 tokens on average, debug build (2026-10-10-e2e2-swarm-throughput.md). A batch of independent short requests, not one answer made faster.

Do not say: the link is Wi-Fi 7 (the standard of the Direct interface cannot be read), that it works with no internet (airplane mode was not run), or that the batch speed was measured on the Direct group.

Fallback:
- Measure reads low or fails: the peer is busy, hot or frozen. Bring it to the front, press "Quick check (10 MB)", then read the earlier-runs list.
- No Direct group: "Host a swarm", "Use current Wi-Fi instead", and quote the access-point figure above.
- The batch is slow or a phone shows no model: do not wait; read the stored numbers above.
- Pairing is done before the judges arrive. The camera scan has never been exercised; the paste route is the backup (docs/SETUP.md section 7).

## Step 2. Data Union (1:00 to 2:20)

Presenter (Phone B): Data tab, "Ask the swarm". Type or dictate "Where is the warranty bill for my laptop?" and tap Ask. Then open "Query audit". Optional, if time allows: the control question "Boiling point of liquid nitrogen".

Judges see: a timing strip (embed, search, one round-trip figure per peer, first word, total), source cards with phone tokens (Phone B's bill photo `IMG_2231.jpg` and Phone A's warranty certificate), the answer, then in the audit page "Sent from this phone" (the question text only) and "Returned by each phone" (docs/design/qa/32-data-audit.png, a frame from the QA run).

Say (novelty statement 3, exactly as worded in docs/research/07-novelty-and-prior-art.md): "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file." Caveat to say if asked: peer-to-peer snippet retrieval is published (DRAG, 2025); the claim is this attribution and consent behaviour shipped on phones, for which no earlier description was found, and the search was limited.

Numbers (Phone B asking, C and A answering, GPU chat on all three, debug build, 231 signed answers checked with 0 wrong; 2026-10-10-e2e2-data-union.md, gate-4-private-data-union.md):
- The acceptance pair ran: the warranty question cited Phone B's bill photo first and Phone A's certificate second. The first ask took 5.3 s to the first word and 11.5 s in total; the repeat 0.55 s and 5.3 s (cause of the repeat's speed not checked; say it is a repeat).
- First-time questions, n = 9: median 4.2 s to the first word (1.4 to 5.8 s) and 6.6 s in total (2.7 to 12.9 s). The 2 s target was met by 2 of 9 and the 6 s target by 4 of 9. On the CPU build the same electricity question took 12.4 s, on the GPU 5.8 s.
- Retrieval is not the cost: embedding 23 to 50 ms, local search 1 to 7 ms, slowest peer round trip 71 to 116 ms. The time is the model reading 3 to 6 sources and writing.
- Privacy held in the cases tried: with Phone B's folder set private, Phone C got "withheld 3" and no file of B; B's salary slip in its Private folder was withheld from a peer's ask.
- The Hindi milkman note was answered from Devanagari text in 1.4 s to the first word.

Do not say: "fully private"; that the 2 s and 6 s targets are met; that tapping a citation opens the file (it sends the owner a notification the owner taps, and no person has tried it, docs/CLAIMS.md R10); that the screen shows an absent phone as Incomplete (with C's app stopped the report flagged it, the answer text did not say a phone was missing and the screen was not looked at, R25); private by default on a fresh install (not re-tested). Do not demo the Hindi warranty question: its answer named the certificate on Phone A as the bill and omitted Phone B's photo.

Fallback:
- A peer does not answer: the answer is flagged and the phone is named; read the sources that arrived.
- Model slow: the source cards are already on screen; read the cited snippets.
- Index empty: do not re-index live. Show docs/design/qa/30-data-ask-answer.png and 32-data-audit.png and say they are frames from the QA run on phone C.

## Step 3. Agent Choir (2:20 to 3:40)

Presenter (Phone B): Choir tab. Pick `m3` from "Try one". It took a second round in both second-run passes and the three-phone Choir was right in both (26.0 s and 26.9 s per problem, 2026-10-10-e2e2-choir.md). Choose only from the 13 problems the Choir got right in both passes: c1, c2, m3, m4, m5, m6, m8, p1, p3, p4, q1, q2, q3. Of those, c1, c2, m3 and q2 needed a second round in both passes, so the phones visibly disagree and converge; p1 is the fastest (one round, 7.2 s and 12.9 s). Do not demo `m2`: it was wrong in both passes (360, then 648; the pass-1 `648` carried a "consistent" label). Leave "Check against the problem's test" off and say so. Tap Run.

Judges see: one lane per phone, answer chips, a Disagree stamp if the answers differ, round labels, the tool-check line, a baseline row with its wall clock, and totals for rounds, tokens and wall clock.

Say (novelty statement 2, exactly as worded in the novelty report): "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Caveat to say if asked: tool-checked selection, self-consistency and equal-compute comparison are published methods; the claim is the product presentation, and it holds only for problems that have a deterministic checker. With the test off the checker only recomputes the arithmetic the models wrote, and one such "consistent" answer was wrong (docs/CLAIMS.md R6). Then say what the numbers support: "Three phones answering in parallel and cross-checking took 20.1 seconds per problem against 32.3 for one phone sampling three times, the same number of model calls."

Numbers (three phones, B coordinating, GPU, 20 built-in problems, strict scorer; 2026-10-10-e2e2-choir.md, gate-3-agent-choir.md):
- Pass 1, test off: one answer 14 of 20 in 11.4 s per problem; one phone sampling three times 18 of 20 in 32.3 s; Choir 16 of 20 in 20.1 s; Choir with the tool check 16 of 20. Pass 2, test on: one answer 11 of 20 in 12.8 s; Choir 14 of 20 in 23.4 s; with the tool check 15 of 20. One problem is 5 points, so none of the score gaps is established, and pass 2 is not a controlled comparison with pass 1.
- All 40 Choir answers were valid; all three phones answered in 37 of 40 rows; mean rounds 1.55 and 1.70.
- Say parallel speed and cross-checking. Never say more accurate than one phone: it scored below three samples on one phone (16 against 18) and was slower than one answer (20.1 against 11.4 s).
- Roles differ by prompt and temperature (0.3 and 0.6) on one model, Qwen3.5-4B. If no answer has a majority a judge pass runs (docs/CLAIMS.md R7).

Do not say: two model families; critic or rotating judge; "smarter"; that Choir verifies independently; a run on the demo build (none exists).

Fallback:
- A lane shows No answer, Timed out or Away: it is left out of the vote and the run goes on (choir/ChoirScreen.kt); say so. In the second run all three phones answered in 37 of 40 rows.
- All phones agree in round 1: say they agree and read the stamp as shown.
- Phone hot: the eval refuses to start; talk over the stored numbers above instead, with docs/design/qa2/21-choir-result-lanes.png as a frame of a real `m3` run on three phones (second visual pass; do not read its digits aloud).

## Step 4. MemoryCloud (3:40 to 5:20)

Do this step last: starting a split stops the chat and embedding servers on all three phones and restores them on Stop (2026-10-10-memorycloud-inapp.md).

Presenter (Phone C, the driver; Phones A and B lend memory): Memory tab, pick `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf`, "Load across 3 phones". Talk while it loads. Ask "Explain how a heat pump works, in about 100 words." and read the tok/s and first-token time. Then "Stop and restore phones". The cold load took 319 s, so do one before the judges arrive and keep the lenders' caches; the 65 s figure is a re-plan with warm caches, and a fresh start after Stop with warm caches was not timed, so time it in rehearsal.

Judges see: SPLIT READY with a live tok/s, load time and first-token time, TCP round trips to both lenders, a one-row layer strip with a colour per phone (C layers 0 to 15, A 16 to 31, B 32 to 47), and the banner "Running a large model with Phone A and Phone B." (docs/design/shots/memcloud-ready-30b.png).

Say: "This model is 18.6 GB, a mixture-of-experts model with 128 experts of which 8 are used per token, and whole layer ranges sit on the three phones. One phone gave no token in 134 seconds and the system killed 23 other apps; two phones did not load. Across three phones it runs: first token in 1.5 to 2.8 seconds, 5.7 to 6.4 tokens a second. It is a capacity tool, not a speed tool: a model that fits one phone is slower when split."

Numbers (Phone C driving, A and B lending, Wi-Fi Direct group at 5765 MHz, one run, debug build of e2d41e4; 2026-10-10-memorycloud-inapp.md, gates/gate-5-memorycloud.md):
- The model is Qwen3-30B-A3B, a mixture-of-experts model: 48 layers, 128 experts, 8 used per token (GGUF header). Whole layer ranges were placed on phones; individual experts were not placed on different phones. The screen shows 3 phone boundaries per token and TCP round trips of 7.1 ms and 7.8 ms to the lenders.
- Load 319 s with empty lender caches, 65 s on a re-plan with warm caches; first token 1.5 to 2.8 s; decode 5.7 to 6.4 tok/s over 100 and 200 token answers; thermal 1, 1, 1 at the start and 0, 0, 1 at the end.
- App plus children while serving: A 5.9 GB, B 6.1 GB, C 5.1 GB, with 5.2 to 5.3 GB still available on each phone; the weights received were A 5743 MB and B 5991 MB. Link: C to A 680 Mbps, C to B 245 Mbps, round trip about 7 ms.
- Baselines: one phone no token after 134 s; two phones did not load (0.47 of 9.3 GB reached the lender in 8 minutes) (2026-10-10-rpc-split-spike.md, gate-5-memorycloud.md).
- The same mechanism on a model that fits one phone (Qwen3.5-4B, two phones): 6.86 and 6.98 tok/s split against 10.80 and 14.69 on one phone.

Optional, only with a laptop and adb on the table and rehearsed: force-stop Ustaad on Phone B mid-answer. In the one run the driver showed DEGRADED naming Phone B after 1.77 s and kept the 27 tokens written, and a re-plan was READY after 65.3 s; the button can read "Re-plan with 3 phones" for a few seconds after a phone is lost (docs/design/shots/memcloud-degraded-30b.png). Say "one run".

Do not say: that two phones run the 30B model (they did not load); that the result was repeated or sustained (one run, answers under 40 s, nothing at Thermal Status 2 or above); that the PRD's 6 to 10 tok/s estimate was met (5.7 to 6.4 measured); that the demo build carried it; the ExpertMesh "remote experts" figures as measurements (they are arithmetic estimates of network wait; no run put experts of one layer on different phones); that the 10 s lease works (it is unit-tested, and a lender had its models back 26 to 27 s after its driver was killed, but the 10 s was not timed alone).

If it fails live: the first start once failed after 12 s with "port 50052 is still in use" on Phone A, and the second start worked (cause not found): press load again. If the split will not come up, show docs/design/shots/memcloud-ready-30b.png and memcloud-degraded-30b.png as frames from the measured run and read the numbers above.

## Step 5. Close and limits (5:20 to 5:55)

No phone action. Say, in this order:
1. Novelty statement 1, exactly as worded in the novelty report, then its caveat in the same breath: "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root. Each part has prior art; this is the combination; the search was limited; the lease is unit-tested and its 10 s was not timed on a phone, and the 30B result is one run."
2. Limits: "Phones overheat within minutes of sustained work. Android freezes the app when the screen is off, so Ustaad works while it is in front. Splitting a model that fits one phone is slower."
3. "Every number we showed is from a run on these phones, one run each; what we have not run is marked pending."

Do not close with the emergency-mesh line from the PRD: it needs a run in airplane mode that does not exist (docs/CLAIMS.md PS3).

## If measured by then (not part of the script)

Use a row only when its file exists and holds the number. Until then say nothing about it and use the fallback.

| Item | File that will hold it | Fallback that is already proven |
|---|---|---|
| Knowledge Bank with a phone that is away when the card is saved, the 3-minute timer, and more than three phones (not measured; the replication itself is measured) | none yet | Not part of the script. Say what ran on three phones: with no sync call a shared save and a revoke were each listed on both other phones at the first poll, under 0.8 s; with the author's phone stopped the other phone answered from the card in 40 and 47 ms with the author, the time and two sources; a revoke stopped its use and a tampered copy was refused (2026-10-10-knowledge-bank.md "Third run", 2026-10-10-e2e2-knowledge-bank.md) |
| Knowledge Bank saved and shared with the app's own Save and Share buttons, on the demo build (the measured saves and revokes were made through debug routes, which the demo build does not have) | none yet | Not a live step. Show the page frames docs/design/qa2/12-kb-page-saved-and-revoked.png and 13-kb-card-detail-on-c.png as frames from the second visual pass |
| A repeat of the three-phone 30B run: cold load, 5 minutes of generation, Thermal Status 2 or above | none yet | Step 4 as written, labelled one run |
| The 20-problem Choir run repeated after the JSON-fragment and rounding fix (08a205f), and a Choir run on the demo build | none yet | Gate-3 numbers above |
| Airplane mode with the Direct group formed; bytes to the internet per app | none yet (docs/CLAIMS.md M6) | Do not say "no internet" as a result |
| Camera QR scan, document scan, dictation by a person; the demo build end to end (it has not carried a split or a Choir run) | none yet (docs/CLAIMS.md M8) | OCR 74 to 110 ms per image is the only creative-use number (2026-10-10-e2e2-data-union.md) |
| 20-minute screen-off soak with the battery exemption | none yet | "Works while in front with the screen on" |
| Office Kit mirroring of the presenter phone | none yet | Do not claim |

## 90-second version

| Time | Beat | Say | Number |
|---|---|---|---|
| 0:00 to 0:15 | Swarm tab, last Measure result and the last swarm batch already on screen | "Three phones, a Wi-Fi Direct link, measured by the app." | 790 to 920 Mbps with 4 to 8 streams (2026-10-10-swarmlink-throughput.md); three phones 30.91 against 12.03 tok/s (2026-10-10-e2e2-swarm-throughput.md) |
| 0:15 to 0:55 | Data Union: the warranty question, then the audit page | Novelty statement 3, exactly as worded | The pair ran; first-time median 4.2 s to the first word (2026-10-10-e2e2-data-union.md) |
| 0:55 to 1:20 | Choir: show a run finished before the judges arrived (that the tab keeps the last run after you leave it is not verified; test it) | Novelty statement 2, exactly as worded | 16 of 20 in 20.1 s against 18 of 20 in 32.3 s for three samples on one phone (2026-10-10-e2e2-choir.md) |
| 1:20 to 1:30 | The 30B result, then the limit | "Three phones ran an 18.6 GB model that one phone cannot, at 5.7 to 6.4 tokens a second. A split of a model that fits one phone is slower." | 2026-10-10-memorycloud-inapp.md |

## What we do not claim

- "First", "only" or "we invented": LinguaLinked (2023), prima.cpp (ICLR 2026), Voltron (July 2026), edge-llm-cluster and exo's 2024-25 demos are prior art for splitting a model over phones; channel pinning, QR-started Wi-Fi Direct and Bluetooth hand-over are in earlier patents and Nearby Connections; hash-chained logs date from 1998; DRAG (2025) retrieves snippets peer to peer (docs/research/07-novelty-and-prior-art.md). Say "we measured it", "we use", not "we invented". Say "executed once", not "exactly-once delivery". Do not call the log tamper-proof: it is local and unsigned.
- "Faster than one phone" without saying where. It holds for a batch of independent short prompts on three phones (30.91 against 12.03 tok/s) and for Choir against one phone sampling three times (20.1 against 32.3 s). It does not hold for a split of a model that fits one phone (docs/CLAIMS.md MC2), or for Choir against one phone answering once (20.1 against 11.4 s). Never "Choir is more accurate than one phone" (CH2).
- Two model families, a critic, a rotating judge (CH5, CH6).
- Wi-Fi 7, 6 GHz, or an NPU speedup; Wi-Fi and Wi-Fi Direct together adding bandwidth (it was 0.3 to 0.5 times the speed of Direct alone, 2026-10-10-swarmlink-throughput.md) (LK14, LK16, RT9).
- "0 bytes to the internet", "no internet" as a result, or airplane-mode operation (PS2, PS3).
- "Fully private" or "zero-trust" (PS9). Say: private by design, files and embeddings stay on the phone, the question and short snippets cross the link signed but not encrypted.
- Works in the background, or the battery setting fixes it (LK7, UX3).
- The 30B model on two phones (it did not load); the three-phone MemoryCloud result as repeated, sustained or run on the demo build (one run, debug build); individual experts placed on different phones, or the ExpertMesh "remote experts" figures as measurements (EM1).
- Camera, document scan or dictation as demonstrated; Office Kit; the in-app model download; the memory lease as proven; the Knowledge Bank reaching a phone that was away when the card was saved, or the 3-minute timer (not measured) (R15, R17, R26, R28, R20).

## Judging-criteria map

Weights are from docs/research/06-hackathon-context-and-landscape.md. Creative phone use and Office Kit usage are read from HackTracker device data; how it counts them is unknown (same file, open questions). The black "HackTracker by" pill in the QA screenshots is the hackathon's own telemetry app, not Ustaad (docs/design/qa/REPORT.md).

| Line | Weight | What earns it | Evidence | Honest gap |
|---|---:|---|---|---|
| End product quality | 30 | Steps 1 to 4 run on real phones with real models, three phones in each; the app says when a split is slower; degraded states are shown | gates 1 to 5; docs/design/qa/; docs/design/shots/memcloud-*.png | Gate 6 open: second visual pass, the demo build never exercised end to end; 17 visual defects at 71f2359, of which the second pass found 10 fixed, 2 partly and 5 not checked, and eight further fixes were seen working (docs/design/qa2, qa3) |
| Novelty and impact | 20 | Novelty statements 1 to 3 with their caveats; private cross-phone search with owner-notification open; an 18.6 GB model across three phones; applications in docs/PITCH.md | docs/research/07, 08 | Every part has prior art; the claim is the combination |
| Creative phone use | 15 | On-device OCR including Hindi (74 to 110 ms per image on the second run), on-device LLM on the Adreno GPU, Wi-Fi Direct. Camera scan, document scan and dictation are built and not yet run by a person | 2026-10-10-e2e2-data-union.md, 2026-10-10-inapp-llm.md | docs/CLAIMS.md R15 |
| Technical depth | 15 | The 30B model across three phones (load 319 s cold, 65 s warm, 5.7 to 6.4 tok/s), channel pinning finding, 700 of 700 requests executed once across two path failovers (paths silenced with a fault hook, Wi-Fi not switched off), three-phone batch throughput 2.57 times the fastest phone, GPU 2.8 times prefill and about 5.8 times less memory, signed requests and responses | 2026-10-10-memorycloud-inapp.md, 2026-10-10-swarmlink-throughput.md, 2026-10-10-e2e2-swarm-throughput.md, 2026-10-10-inapp-llm.md | One run each for several of these |
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
| Batch of 32 short prompts, GPU, access point | three phones 30.91 tok/s (28.90 to 31.94) against 12.03 for the fastest single phone; two phones 18.83, one run | 2026-10-10-e2e2-swarm-throughput.md |
| GPU against CPU, one run each, phone C | prefill 201.9 against 71.6 tok/s; first token 2.59 against 7.20 s; memory 1032 against 5952 MB | 2026-10-10-inapp-llm.md |
| Decode when sustained | 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10 on the GPU | 2026-10-10-llama-cpu-baseline.md |
| Choir, three phones, 20 problems, strict scorer, test off | Choir 16 of 20 in 20.1 s; one phone three samples 18 of 20 in 32.3 s; one answer 14 of 20 in 11.4 s | 2026-10-10-e2e2-choir.md |
| Data Union, GPU, first-time questions, n = 9 | median 4.2 s to the first word, 6.6 s total; under 2 s in 2 of 9, under 6 s in 4 of 9 | 2026-10-10-e2e2-data-union.md |
| Knowledge Bank, three phones, no sync call; author's phone stopped | a shared save and a revoke listed on both other phones at the first poll, under 0.8 s; answer from the card in 40 and 47 ms (second run: 35 ms against 5090 ms for the live search) | 2026-10-10-knowledge-bank.md, 2026-10-10-e2e2-knowledge-bank.md |
| 30B across three phones, one run | load 319 s cold, 65 s warm; first token 1.5 to 2.8 s; 5.7 to 6.4 tok/s | 2026-10-10-memorycloud-inapp.md |
| 30B on one phone; on two phones | no token in 134 s; did not load | 2026-10-10-rpc-split-spike.md, gates/gate-5-memorycloud.md |
| Split 4B over two phones against one | 6.86 and 6.98 against 10.80 and 14.69 tok/s | 2026-10-10-memorycloud-inapp.md |
