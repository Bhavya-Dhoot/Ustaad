# Gate 3 — Agent Choir (closed 2026-10-10 by the orchestrator: the demo runs end to end on three phones; the accuracy claim is narrower than planned)

Gate criterion: Agent Choir demo end to end on two phones. Met on three phones (A, B, C) with the real model on the GPU. Evidence: docs/benchmarks/2026-10-10-e2e2-choir.md (master e2d41e4, debug build, script scripts/e2e2-check.sh, 20 problems, strict scorer, every signed answer checked). Earlier evidence: docs/benchmarks/2026-10-10-choir-answer-quality.md (one phone) and docs/benchmarks/2026-10-10-e2e-choir.md (the run that exposed the answer-format defects).

## Decisions
1. Every phone answers the same problem independently in round 1; phones that disagree see the others' answers and revise in round 2; a third round runs only if they still disagree. The coordinator picks by majority, then by confidence.
2. Answers are typed per problem kind (a number, an option letter, a short output), validated on the phone, and retried once when invalid. This removed the punctuation, prose and brace answers of the first run.
3. Two labels, never mixed: VERIFIED means the answer passed the problem's own test; CONSISTENT means only that the model's written arithmetic re-computes. A consistent answer can be wrong.
4. The fair baseline is one phone sampling three times and taking the majority (equal model calls), not one phone answering once.
5. The scorer is strict: exact number, exact letter, exact output.

## Measured (three phones, Phone B coordinating, Qwen3.5-4B Q4_K_M on GPU, 20 problems)
| Arm | Pass 1 (test off) | Pass 2 (test on) |
|---|---|---|
| One phone, one answer | 14 of 20, 11.4 s per problem | 11 of 20, 12.8 s |
| One phone, three samples, majority | 18 of 20, 32.3 s | not run |
| Choir, three phones | 16 of 20, 20.1 s | 14 of 20, 23.4 s |
| Choir with tool check | 16 of 20 | 15 of 20 |

Choir answers were valid in 40 of 40 rows. All three phones answered in 37 of 40 rows. Mean rounds 1.55 and 1.70. In pass 2 the tool check verified 15 of 20 against the problem's test and changed one wrong pick to the right one.

## Deltas from plan
- The plan expected the Choir to beat a single phone at equal compute. It did not: three samples on one phone scored 18 of 20, the Choir 16 of 20. What the Choir gives is the same number of model calls in 1.6 times less wall-clock time (20.1 s against 32.3 s) and a higher score than a single answer (16 against 14, and 14 against 11). With 20 problems, one problem is 5 points, so none of these differences is established.
- The pitch must say "three phones answer in parallel and cross-check, faster than one phone sampling three times", not "more accurate than one phone".
- Pass 2 scored lower than pass 1 in every arm with the same model; the runs are stochastic and the phones were hotter. The two passes are not a controlled comparison.

## Open risks
- One CONSISTENT answer was wrong (m2 = 648). The label must be read as "arithmetic re-checked".
- A JSON fragment (`,confidence": 1.0`) was accepted as a valid CODE answer on one phone, and two one-phone numbers carried floating-point noise. A fix is in progress on the Choir branch (unit tests only; not re-measured on phones).
- Phone B's chat server was killed once during pass 1 (thermal status 3) and restarted by the app; the cause was not found. Three one-phone rows were lost to it.
- A 20-problem run heats all three phones to status 2 to 3 in eight minutes. The demo should run two or three problems, not the scoreboard.
- No person has run the Choir screens by hand on the fixed build: the lanes, the round indicator and the scoreboard were last looked at in the visual pass on commit 71f2359.

## Ledger
- Module, answer-quality fix, compose-screen fixes: one Sonnet worker (Choir engineer). Evidence runs: device tester (Sonnet). Merges and this artifact: orchestrator.
