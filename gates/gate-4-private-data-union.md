# Gate 4 — Private Data Union (closed 2026-10-10 by the orchestrator, with the latency target missed)

Gate criterion: Private Data Union demo end to end on two phones. Met with real models on phones A and C. Evidence: docs/benchmarks/2026-10-10-e2e-data-union.md (commit 8f18a3e, debug build, script scripts/e2e-check.sh). The security and correctness fixes from the two reviews were merged after that run and are covered by unit tests, not yet by a second phone run.

## Decisions
1. Each phone keeps one SQLite store: FTS5 for keywords, 256-dim EmbeddingGemma-300M vectors compared by brute-force cosine, fused by reciprocal rank. Dense floor 0.40 (calibrated on the real embedder: relevant hits 0.55 to 0.61, best irrelevant 0.386).
2. Only the question text goes out; each phone returns at most 5 snippets of at most 400 characters with title, date and score. Embeddings, files, paths and EXIF never leave a phone.
3. Folders and scans are private until the owner shares them; anything under a folder named Private can never be shared. A peer can only raise a notification asking the owner to open a file.
4. Indexing, scanning and re-indexing refuse to run without a real, ready embedder, checked before every batch.
5. Hindi text is read with a second OCR pass; dictation uses the on-device recogniser only.

## Measured (asks from Phone A, Phone C as peer, chat model on CPU)
| Question | Sources shown | Time to first token | Total |
|---|---|---|---|
| Where is the warranty bill for my laptop? (three runs) | Phone A: warranty certificate | 4.5 s, 0.49 s, 0.30 s | 8.4 s, 5.3 s, 5.7 s |
| When is my electricity bill due and how much is it? | Phone C: electricity bill photo, plus three from A | 12.4 s | 16.1 s |
| Hindi question about the milkman note | Phone C: Hindi note photo, answered from Devanagari text | 3.2 s | 4.7 s |
| Unrelated question | none | no model call | 0.09 s |

Query embedding 25 to 57 ms, local search 1 to 12 ms, peer round trip 54 to 232 ms. Before sharing, Phone C withheld its match and nothing from C reached A; a Private folder inside a shared folder stayed withheld.

## Deltas from plan
- PRD target "first visible text within 2 s, full answer within 6 s" is met only when a question repeats. New questions took 3.2 to 12.4 s to the first token because the prompt is processed on the CPU. The GPU chat backend (4 times the prefill in standalone tests) failed to start from inside the app during this run; the fix is in progress and this table must be retaken with it.
- The PRD acceptance pair (bill photo on Phone B, certificate on Phone A) was not run: Phone B is reserved. The equivalent pair across A and C was run.
- Source cards appear immediately (34 to 56 ms), before the model writes.

## Open risks
- Relevance margin is thin (0.014 between the best irrelevant hit and the floor, on three questions). Sources are still listed when the model says nothing relevant was found.
- Camera scan, the folder picker, permission dialogs and tapping a citation were not exercised by a person.
- Bundled ML Kit components that could report usage were removed from the manifest, but the app's network use with internet available has not been measured.
- Peer traffic is authenticated but not encrypted; on someone else's Wi-Fi a sniffer could read questions and snippets. On the app's own Wi-Fi Direct group the group key protects it.

## Ledger
- Module, restyle, two review-fix passes: one Sonnet worker (about 2.4M tokens over four runs). End-to-end evidence: device-tester. Merges and this artifact: orchestrator.
