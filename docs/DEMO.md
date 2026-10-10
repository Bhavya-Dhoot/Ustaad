# Ustaad demo runbook (judges, 6 minutes maximum)

Written 2026-10-10 against master at b661cdf (03:50 IST). Setup is in docs/SETUP.md; claims policy is in docs/PITCH.md. A number in parentheses is followed by the file it comes from. PENDING MEASUREMENT means no file in the repository holds the number yet; the step names the file that will. "Not verified" means no code or benchmark file confirms the behaviour. Items marked [branch] exist only on an unmerged branch and are listed in docs/SETUP.md section 10.

The demo follows docs/PRD.md section 9 (pair, Data Union, Agent Choir, MemoryCloud, close), changed in three ways to match what is built and measured:
- Pairing uses a Wi-Fi Direct group, not a phone hotspot (gate-1-swarmlink.md, decision 1).
- MemoryCloud is not in the master app (the Memory tab is a placeholder), so its step has a live version [branch] and a fallback that is proven today.
- The "0 B to internet" stamp is not used as evidence. It counts only the model downloader's bytes (ui/StatsBar.kt, review item 4).

## Layout on the table

- Presenter holds Phone A, the coordinator. A hosts the swarm and runs every query.
- Phone B and Phone C lie face up on the table, powered, screens on, Ustaad in front, fan on them. Judges may be handed B to see a file open (step 2).
- Laptop: mirror of Phone A through vivo Office Kit (section "Office Kit", not verified).
- Rule for every step: if the phone strip shows Severe or the HOT stamp, stop the live segment and use its fallback (thermal throttling cut decode from 14.86 to 4.88 tok/s, 2026-10-10-llama-cpu-baseline.md, 2026-10-10-inapp-llm.md).

## Timed script

| Time | Step | Phone in hand | Live or fallback |
|---|---|---|---|
| 0:00 to 0:50 | 1. The swarm | A | Live. Numbers measured. |
| 0:50 to 2:20 | 2. Data Union | A, then B | Live. Timing PENDING MEASUREMENT. |
| 2:20 to 3:50 | 3. Agent Choir | A | Live. Scoreboard PENDING MEASUREMENT. |
| 3:50 to 5:10 | 4. MemoryCloud | A | Live version PENDING MEASUREMENT [branch]; fallback proven. |
| 5:10 to 5:50 | 5. Close and limits | A | No phone action. |
| 5:50 to 6:00 | Buffer | | |

If a step runs 30 s over, cut the next step's optional lines (marked optional) and keep the hard stop times.

## Step 1. The swarm (0:00 to 0:50)

Presenter does (Phone A): open the Swarm tab. The topology shows A, B and C with DIRECT chips. Tap B in the peer list, then "Measure again (100 MB)". Read the Mbps, the round-trip average and the PHY row.

Judges see: three phone tokens (teal A, tangerine B, magenta C), the status strip with thermal, battery, free memory and link, a live Mbps figure and RTT rows.

Say: "Three phones, one Wi-Fi Direct link between them, and this speed is measured by the app right now."

Number to point at: Wi-Fi Direct between A and B on quiet phones, 305 to 503 Mbps and 12 to 20 ms average RTT; the same phones through the access point 96 to 104 Mbps and 23 to 26 ms (2026-10-10-swarmlink-g1.md). All three phones list each other 3.7 to 5.3 s after a cold start (2026-10-10-swarmlink-g1.md). Say the RTT target of 8 ms was missed if asked (gate-1-swarmlink.md).

Optional (only after you have rehearsed it): show the airplane-mode icon on the phones' status bars and say they are in airplane mode with Wi-Fi on. Not verified: no run in the repository was taken in airplane mode (2026-10-10-swarmlink-g1.md used an access point). Do not point at the "0 B to internet" stamp as proof.

Fallback if it fails live:
- Peer missing: type its IP in "Peer IP or ip:port" and Add (Swarm tab).
- Measure fails or reads low: the peer was frozen or busy (loaded phones gave 93 to 288 Mbps on Direct, 2026-10-10-swarmlink-g1.md). Bring B to the front, retry once with "Quick check (10 MB)", then read the earlier-runs list.
- No Direct group: "Host a swarm", "Use current Wi-Fi instead", quote 96 to 104 Mbps.
- QR will not scan (camera scan untested): paste the link on the Join page. Do this before the judges arrive, not live.

## Step 2. Data Union (0:50 to 2:20)

Presenter does (Phone A): Data tab, "Ask the swarm". Type or dictate "Where is the warranty bill for my laptop?" and tap Ask. After the answer, tap a source card. Then ask a second question that matches the private file, for example "What was my salary in September?", and open "Query audit". Optional: hand Phone B to a judge.

Judges see, in order: skeleton cards while it searches, a timing strip (embed, local search, one round-trip figure per peer, first word, total), source cards with phone tokens (B: `IMG_2231.jpg`, A: `Laptop_Extended_Warranty_Certificate.pdf`), then a streamed answer with citations. In the audit page: "Sent from this phone" (question text only), "Returned by each phone", and a Withheld stamp on B, because B's `Private/Salary_Slip_Sep2026.pdf` is held back and only counted (pdu/PduAudit.kt, scripts/make-demo-corpus.py). The count is made from the files that matched, so the stamp only appears for a question the salary slip matches (pdu/Ranking.kt). Which wording of the second question produces it is not recorded; find it in rehearsal.

Say: "Each phone searches its own files. Only the question goes out and only short snippets come back, and the salary slip on Phone B stays on Phone B."

Number to point at: PENDING MEASUREMENT. The target is first visible text within 2 s and the full answer within 6 s on the Direct link (docs/PRD.md R4.4). No Data Union benchmark file exists yet; it will be docs/benchmarks/ (name not fixed). Read the timing strip aloud as the live value. What is measured today is the embedder: cosine 0.776 for "warranty bill for laptop" against "laptop purchase receipt" and 0.404 against "how to bake sourdough bread" (2026-10-10-inapp-llm.md). Design limits (not measured results): snippets of at most 400 characters, deadline 1.5 s per peer (docs/PRD.md R4.2, R4.3).

Behaviour that changes with the merge of the PDU branch [branch]: tapping a cited file on a peer opens it on that phone's screen today; the branch changes this to a notification the owner taps (review section 1). Rehearse the build you will present and do not promise the open-on-B moment unless it works.

Optional (skip if over time): Hindi. C holds `IMG_3302_Hindi_Note.jpg`; Devanagari OCR was merged (commit 6aa0a33). Retrieval quality for Hindi questions is not measured. Not verified.

Fallback if it fails live:
- A peer does not answer: the screen shows an Incomplete stamp and names the phone (pdu/PduAsk.kt). Say "this is the degraded state, the answer says it may be incomplete" and read the sources that did arrive.
- Model slow: sources are on screen before the answer finishes; read the cited snippets.
- Index empty or wrong: do not re-index live. Show the stored screenshots `docs/design/shots/pdu-ask-citations.png` and `pdu-audit.png` and say they are screenshots from an earlier build. Whether those were taken with the fake model is not recorded; check before showing.

## Step 3. Agent Choir (2:20 to 3:50)

Presenter does (Phone A): Choir tab. Pick the problem you rehearsed from "Try one" (the 20 built-in problems are m1 to m8, p1 to p4, q1 to q4, c1 to c4 in assets/choir/problems.json; which one makes the phones disagree is not recorded anywhere, so find it in rehearsal). Leave "Check against the problem's test" on. Tap Run.

Judges see: one lane per phone streaming its steps, answer chips, a Disagree stamp if the answers differ, round 2 and round 3 labels, the tool check line, a baseline row ("Baseline: Phone A alone") with its wall clock, and the totals (rounds, tokens, wall clock).

Say: "Each phone answers alone. When they disagree they check each other's steps, and a small Kotlin checker, not a model, decides when it can."

Number to point at: PENDING MEASUREMENT, the 20-problem table of one phone alone, three samples on one phone, Choir, and Choir plus tool check, with n, accuracy and mean seconds. The app writes it as `choir-eval-<ts>.json` in the app files folder and shows it in the scoreboard; no benchmark note exists yet (docs/PRD.md R3.5). Say throughput and verification, not "smarter", until the table says otherwise (gate-0-research-prd.md, decision 6). Live values: rounds, tokens, wall clock and the baseline seconds on screen. Derived, not measured in Choir: round 1 is capped at 300 tokens (choir/ChoirTypes.kt), about 20 s at the in-app decode rate of 14.8 tok/s (2026-10-10-inapp-llm.md). The hard deadline is 60 s (choir/ChoirCoordinator.kt).

Be accurate about two things:
- Roles. The phones run the same model, Qwen3.5-4B, with different prompts and temperature for the solver and the alt-solver (choir/ChoirPrompts.kt). They are not two model families. Gemma 4 E4B is chat only (gate-2-inference-baseline.md).
- The tool check. With the switch on, a built-in problem is checked against its own stored test, so the scoreboard's "Choir + tool check" is scored with an answer key; for a problem you type, the checker only re-computes the steps the models wrote (choir/ChoirScreen.kt, scoreboard basis text). Do not describe it as an independent verifier.

Fallback if it fails live:
- A lane shows Timed out, Away or Error: the run goes on without it (choir/ChoirScreen.kt); say so.
- All phones agree in round 1: say "they agree, so there is nothing to resolve; the tool check still ran" and read the Verified or Unverified stamp as it is.
- No peers online: the screen says the coordinator runs alone. Fix pairing, not the story.
- Phone is hot: the eval and benchmark refuse to start; show the stored scoreboard from rehearsal instead of running anything.

## Step 4. MemoryCloud (3:50 to 5:10)

PENDING MEASUREMENT. On master the Memory tab is a placeholder (memcloud/MemCloudModule.kt). The module is on branch worktree-agent-ac34ad0bc1b4ddd46 [branch] with no benchmark note. Use the live version only if that branch is merged, the 30B file is on the driving phone and the numbers below exist. Otherwise use the fallback, which is complete on its own.

Live version (needs measurement). Presenter does (Phone A): Memory tab, plan the split of Qwen3-30B-A3B across A, B and C, load, ask one question, watch tokens stream, then pull B's Wi-Fi (or close Ustaad on B) and show the degraded message, then restore. Judges see: a one-row layer strip with a colour per phone, hatched cells while loading, per-phone tok/s, per-hop RTT and thermal, and a named degraded state. Say: "This model does not fit in one phone. It is split by layers across three, and only activations cross the link."

Numbers PENDING, from docs/benchmarks/2026-10-10-memorycloud-inapp.md (named in plans/briefs/memcloud.md, file not present): load time cold and warm, time to first token, decode tok/s over a 200-token answer, RSS per phone, thermal after 5 minutes, time to DEGRADED after a pull (target within 10 s). Also PENDING: a valid three-phone split of the 4B model; the earlier attempt used `-ts 1,1` and was two separate tests (2026-10-10-rpc-split-spike.md). Already measured and safe to say: the rpc-server tensor cache cut the second load from 153 s to 41 s and the data sent from 1.57 GB to 187 MB (2026-10-10-rpc-split-spike.md).

Fallback, proven today (use it when the live version is not ready or fails):
1. Models tab on Phone A, open the benchmark view and tap "Run benchmark". It shows prefill, decode, time to first token, memory and thermal at start and end. About 16 to 21 s (derived from the three rows in 2026-10-10-inapp-llm.md). Decode on one phone: 14.8 tok/s in the app (2026-10-10-inapp-llm.md), 14.86 tok/s from a cool start in the standalone run (2026-10-10-llama-cpu-baseline.md).
2. Say the honest result: "We split the same 4B model across two phones. It runs at 3.18 tokens a second against 14.86 on one phone (2026-10-10-rpc-split-spike.md). Every token returns about 1 MB of logits over a link that moves 8 to 10 MB/s. A model that fits one phone should not be split."
3. Say why it is still worth building: "The 30B model, 18.6 GB, cannot run on one phone. We tried: no token in 134 s, memory thrashing, and the system killed 23 background apps (2026-10-10-rpc-split-spike.md). A split is for that case." Show it on a printed card of the numbers table below. Do not run it live: it makes the phone kill other apps.
4. Do not claim the 30B split works until step 4 numbers exist.

## Step 5. Close and limits (5:10 to 5:50)

Presenter does: nothing on the phones; hold Phone A so the Swarm tab is visible.

Say, in this order:
1. "Ustaad is the layer that places work on phones, keeps a hash-chained log of what was asked, and shows the numbers it measured." (Audit log: core/AuditLog.kt; the Settings "Verify" action shows Chain OK.)
2. "ExpertMesh is the same layer split applied to a mixture-of-experts model. We group experts by layer range because a remote expert inside a layer costs a round trip per layer per token." This is the design argument in docs/PRD.md section 2 and docs/research/03; the ExpertMesh screen is on the MemoryCloud branch [branch] and has no measured number.
3. "Limits we measured: the phones throttle within minutes, the OS freezes the app when the screen is off, and splitting a model that fits one phone is slower." (2026-10-10-llama-cpu-baseline.md, 2026-10-10-swarmlink-g1.md, 2026-10-10-rpc-split-spike.md)
4. Footnote, one sentence, no code: "Because the link needs no internet, the same mesh could carry messages in an emergency." (docs/PRD.md section 2.)

## 90-second version

| Time | Beat | Say | Number |
|---|---|---|---|
| 0:00 to 0:15 | Swarm tab, three DIRECT peers, last Measure result already on screen (run it before the judges arrive) | "Three phones, one Wi-Fi Direct link, measured by the app." | 305 to 503 Mbps (2026-10-10-swarmlink-g1.md) |
| 0:15 to 0:55 | Data Union: the warranty question and its sources; add the salary question and the Withheld stamp only if time allows | "Only the question goes out. Short snippets come back. Private files stay on their phone." | Timing PENDING MEASUREMENT |
| 0:55 to 1:20 | Choir: show a run completed before the judges arrived (whether the tab keeps the last run after you leave it is not verified; test it) | "They answer alone, check each other, and a checker has the last word." | Scoreboard PENDING MEASUREMENT |
| 1:20 to 1:30 | Say the limit | "Splitting a model that fits one phone is slower: 3.18 against 14.86 tokens a second. The split is for models that do not fit." | 3.18 and 14.86 (2026-10-10-rpc-split-spike.md) |

## Judging-criteria map

Weights are from docs/research/06-hackathon-context-and-landscape.md. Creative phone use and Office Kit usage are read from HackTracker device data; how HackTracker counts them is unknown (same file, open questions).

| Line | Weight | What in the demo earns it | Evidence in the repository | Honest gap |
|---|---:|---|---|---|
| End product quality | 30 | Steps 1 to 3 run end to end on real phones with real models; every figure on screen is live; degraded states are shown, not hidden | gate-1-swarmlink.md, gate-2-inference-baseline.md, docs/design/DESIGN.md | Choir and Data Union have no closed gate artifact; the review lists blockers (docs/review/2026-10-10-merged-app-review.md) |
| Novelty and impact | 20 | One task spread over phones with a private data path: the question travels, the files do not; plus an honest split result | docs/PRD.md sections 0 and 5.4 | Prior art exists: LinguaLinked (2023) and exo (2024 demo) (docs/research/06); do not say "first" |
| Creative phone use | 15 | Camera (QR join, "Scan a document" with OCR), voice dictation on the Choir question box, on-device OCR (ML Kit, Devanagari), Wi-Fi Direct, Bluetooth permissions, on-device LLM. Show the scan page and the mic button once | pdu/PduScreen.kt, choir/ChoirVoice.kt, link/QrScanner.kt | Camera QR scan untested; Data Union dictation can fall back to the network recogniser on master (review section 3); no Bluetooth code on master |
| Technical depth | 15 | Measured Direct against LAN link, i8mm build 3.7 times faster prefill than the default build (38.2 against 10.4 tok/s), tool-verified Choir, hash-chained audit log, the RPC split spike including the negative result, the freezer finding | 2026-10-10-swarmlink-g1.md, 2026-10-10-llama-cpu-baseline.md, 2026-10-10-rpc-split-spike.md, 2026-10-10-inapp-llm.md | 30B split not measured; RTT target missed |
| Office Kit usage | 10 | See the next section | docs/research/06 | Not verified |
| Demo and presentation | 10 | Timed script, hard stop times, a fallback for every step, the 90-second version | this file | Rehearsal not yet recorded (build plan step 5, plans/2026-10-09-build-plan.md) |
| Local model bonus | extra | Qwen3.5-4B (Apache-2.0) and EmbeddingGemma-300M run on the phone through llama.cpp v0.6.0; no cloud model is called; file hashes pinned | docs/models.md | The model download screen uses the internet and says so; ML Kit's bundled upload service was unmeasured and is removed on the PDU branch [branch] (review section 3) |

## Office Kit

The 10 percent line is scored from device data. Nothing in the repository integrates Office Kit and no vivo SDK was found (docs/research/06). A concrete, honest use:
1. Mirror Phone A to the demo laptop with vivo Office Kit screen mirroring, so the judges watch a large copy of the presenter phone while Phones B and C sit on the table. This also serves as the projector.
2. Use Office Kit clipboard sync to paste the pairing link into the Join page's "Paste swarmos://pair link or code" field when the camera scan fails.
3. Use Office Kit file transfer to move a demo file onto a phone instead of adb during the rehearsal.

Not verified: the laptop client is not installed, mirroring has not been tried with Ustaad (which keeps the screen on and uses the camera preview), and nobody has confirmed that HackTracker records mirroring as Office Kit usage. Ask the organisers (docs/PRD.md section 10, question 2). The build rules also split into "Red Light" (phone only) and "Green Light" (phone and laptop) categories (docs/research/06); confirm which one a mirror-only laptop falls under.

## Numbers you may say, and where they come from

| Claim | Value | File |
|---|---|---|
| Direct link, A and B, quiet | 305 to 503 Mbps, 12 to 20 ms average RTT | 2026-10-10-swarmlink-g1.md |
| Same Wi-Fi, A and B | 96 to 104 Mbps, 23 to 26 ms | 2026-10-10-swarmlink-g1.md |
| Peers found after cold start | 3.7 to 5.3 s | 2026-10-10-swarmlink-g1.md |
| Peer marked away after force-stop; back after restart | 2.1 to 2.2 s; 1.0 s | 2026-10-10-swarmlink-g1.md |
| Decode, 4B model, one phone | 14.8 tok/s in the app, 14.86 tok/s cool standalone | 2026-10-10-inapp-llm.md, 2026-10-10-llama-cpu-baseline.md |
| Decode when hot | 10.7 tok/s at Thermal Status 3; 4.88 and 6.13 tok/s at CRITICAL | 2026-10-10-llama-cpu-baseline.md, 2026-10-10-inapp-llm.md |
| Split 4B over two phones | 3.18 tok/s against 14.86 on one phone | 2026-10-10-rpc-split-spike.md |
| 30B on one phone | No token in 134 s, 23 apps killed | 2026-10-10-rpc-split-spike.md |
| Chat plus embedder memory | about 6.2 GB resident | 2026-10-10-inapp-llm.md |

Not allowed until measured: any Choir accuracy figure, any Data Union latency, any 30B tokens per second, any aggregate tokens per second across phones (the scheduler's throughput panel is not reachable from the UI on master; no benchmark exists).
