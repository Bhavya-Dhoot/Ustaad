# Gate 6 — ExpertMesh (stretch) and demo polish (build work complete 2026-10-10; the rehearsal items below are open and need the founder's hands)

Gate criterion: ExpertMesh as a stretch, and demo polish. The build side is done and measured on the phones. What a person must do on the phones has not been done, so this gate is not closed until the founder has run the rehearsal list. Evidence: docs/design/qa2/REPORT.md and docs/design/qa3/ (screenshots), docs/benchmarks/2026-10-10-final-build-smoke.md, docs/benchmarks/2026-10-10-knowledge-bank.md section "Third run", docs/benchmarks/2026-10-10-memorycloud-inapp.md section "Three phones", docs/CLAIMS.md.

## Decisions
1. ExpertMesh ships as what was measured, not as a separate mode: a mixture-of-experts model (Qwen3-30B-A3B, 128 experts, 8 used per token) split by whole layer ranges across three phones. Placing single experts on different phones was not built; the screen shows that case as arithmetic only, labelled as an estimate.
2. The phones carry the debug build (`com.swarmos.app`, label Ustaad). It is the build every measurement used. The demo build (`com.ustaad.app`, random pairing secret, debug routes off) builds and passes the same tests but has never been paired or run on a phone, so it is not installed.
3. The Knowledge Bank pushes a shared card or a revocation to online peers at once; the 3-minute timer stays as the catch-up path.
4. Pitch wording follows docs/CLAIMS.md. Three sentences were removed for good: "more accurate than one phone" for the Choir, "two phones run a model neither can" for MemoryCloud, and "answers in two seconds" for the Data Union.

## Measured on the final build (master 0a71f33, 699 unit tests, installed on A, B and C at 10:27)
| What | Value |
|---|---|
| Models after install | chat on the GPU and embedder READY on all three; each phone lists the other two |
| Three-phone batch, one run | 32 of 32 jobs, 39.6 tok/s aggregate, 11 / 11 / 10 jobs per phone, thermal status 0 |
| Cross-phone question on Phone B | bill photo on B and certificate on A cited; first token 4.0 s, total 8.7 s |
| Question with Phone A stopped | answer carries "Phone A did not answer" above the text |
| Shared card and revocation, no sync call | on both other phones at the first poll, under 0.8 s (two saves, three revokes) |
| Answer from a card with the author's phone stopped | 40 and 47 ms, with author, time and two sources |
| First-pass screen defects (17) | 10 fixed, 2 partly, 5 not checked |
| Eight screen fixes made after the second pass | all eight seen fixed on Phone C (docs/design/qa3) |

## Deltas from plan
- ExpertMesh is a layer-range split of an expert model plus an explainer, not expert-level routing.
- The demo build was planned as the build to present. It is not the build on the phones.
- No rehearsal was run and no demo time was measured.

## Open: rehearsal list for the founder (none of these can be done over adb without changing phone settings or touching the screen as a person)
1. Battery: allow background activity for Ustaad on each phone (first-run setup shows the prompt). Without it OriginOS freezes the app when it leaves the front.
2. Pair by camera once: Host on one phone, scan the QR on the other two. Camera scan has never been done by a person.
3. Scan a paper document, dictate a question, tap a citation chip. None has been done by a person.
4. Turn on airplane mode with Wi-Fi back on and run one step, to show nothing leaves the room.
5. MemoryCloud: load the 30B split once before the demo (5.3 minutes cold, about 1 minute warm) and keep the three phones awake, cool and in front. One run exists; a second would make it a result rather than an event.
6. Time the whole script once (docs/DEMO.md) and note where the phones reach thermal status 2.

## Open risks
- Phones heat to status 2 to 3 within 8 minutes of continuous Choir work; Phone B's chat server was killed once at status 3 and restarted itself.
- Every measurement is one run on the debug build with the development pairing secret.
- Peer traffic is authenticated but not encrypted on a shared Wi-Fi; on the app's own Wi-Fi Direct group the group key protects it.
- Left as seen: the embedder name is cut with an ellipsis; the model picker row on the Memory tab still wraps ".gguf"; a saved card and the live source list number the same two files in a different order; "saved, just now old" wording; Phone B's audit header counts chunks ("matches 4") above two listed files.
- Screens never looked at on a phone: Host and QR, Scan review, Models while stopped or loading, a solo Choir run, the too-hot and NO INTERNET stamps.

## Ledger
- Choir validation fix, Knowledge Bank and Ask fixes, eight screen fixes: the two Sonnet workers that own those modules. Three evidence runs and two screenshot passes: device testers (Sonnet). Claims ledger and documents: two Sonnet writers. Merges, final build and install, gate artifacts 3 to 6, clean-up: orchestrator.
