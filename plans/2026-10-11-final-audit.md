# Final audit of the implementation (2026-10-11, 00:35)

Asked by the founder at 00:26: a final audit of the implementation, an adversarial audit by Codex with gpt-6.1-sol, Phone A updated, cleaned up and used for tests again, and the random taps moved to Phone C.

State at the start: master f62da67 plus documents, 1453 unit tests, the same build on phones A, B and C, all three paired with one secret and listing each other. Phone C carries the founder's remote-control session with random taps until about 02:27, so Ustaad on Phone C is in the background and Phone C is not a reliable swarm member.

Constraint: correctness, with the demo the same day. A wrong fix made at night costs more than a known and written-down gap, so a finding is fixed only when the fix is small, has a unit test and can be checked on a phone; everything else is written down with its reason.

Scope: the code as it stands, with weight on what changed since the audit of 2026-10-10 (docs/review/2026-10-10-adversarial-audit.md): the batch path without prompt cache (7962ff1), the cut-answer error (102dbd4), the pairing message (b975f55), the keep-busy fix (1b9d60b), Hammer (f62da67), and the earlier fixes of that audit, which have not been read again since they were made.

Steps:
1. Codex, read-only, gpt-6.1-sol at high effort: adversarial audit of the whole app. Raw output to docs/review/2026-10-11-codex-audit.txt.
2. Three Sonnet reviewers, read-only, one lens each so they do not overlap: (a) keep busy, scheduler, heat, battery and memory handling; (b) failure and fallback paths, and every state that can stay stuck (the class of the fault found on 2026-10-10: a resting state counted as work); (c) the 2026-10-10 findings table, row by row: is the fix in the code, does a test hold it, is what was deferred still as written, and does docs/CLAIMS.md say only what the code and the notes support.
3. Orchestrator: every finding is checked against the code before it is counted (one read each), then sorted: fix now, write down, reject. Fixes go to builders, one finding or one file group each.
4. One device tester on phones A and B, and C where it answers: the regression set of the evening (fault injection, MemoryCloud, privacy, agent endpoint, Knowledge Bank, batch across phones) plus what waited for Phone A: three-phone throughput, Choir on three phones, the 30B split across three phones. Phone C in the background limits the three-phone items; they are run if Phone C answers and marked otherwise.
5. Phone A clean-up: list what earlier experiments left on it, remove only what is ours and unused, leave the app's data and models alone.
6. One document: docs/review/2026-10-11-final-audit.md with the table of findings, what was fixed, what was not and why; the gate 9 artifact and docs/CLAIMS.md updated from it.

Failure modes: Codex or a reviewer reports something plausible and wrong (caught in step 3: no finding is acted on unread); two reviewers report the same thing (merged in the table, counted once); a reviewer runs out of budget on one file (each has a file list and a line cap for the return); the tester leaves a phone on a non-default setting (the orchestrator reads all three phones' state after it; the tester's brief lists what must be restored); a fix breaks something else late at night (unit tests plus the phone regression after the last fix, and nothing is installed on a phone during a test run); the random taps on Phone C reach something else (the script holds them back unless the remote-control screen is in front).

Ledger: 1 delegated to Codex (the founder asked for an independent model); 2 delegated to three Sonnet reviewers (read-only, bounded by lens and file list, their reading stays out of the orchestrator's context); 3 direct (the judgement is the orchestrator's job); 4 delegated to one Sonnet device tester (long, mechanical, checkable from its raw files); 5 direct (a few calls, deletions stay under the orchestrator's eye); 6 direct.
