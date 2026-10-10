# Final audit of the implementation (2026-10-11)

Plan: plans/2026-10-11-final-audit.md. Code state audited: master f62da67 (the commits after it changed documents and scripts only), 1453 unit tests, 0 failures, the same build on phones A, B and C. State after the fixes: master 02b15c2, 1499 unit tests, 0 failures.

Who looked: Codex (gpt-6.1-sol, high effort, read-only, raw output in `2026-10-11-codex-audit.txt`), three Sonnet reviewers with one lens each (resources and keep busy; failure paths and stuck states; the fixes and claims of 2026-10-10), one Sonnet device tester on phones A and B (docs/benchmarks/2026-10-11-final-regression.md). Every finding below was read against the code by the orchestrator before it was counted.

## Reviewer 3: do the fixes of 2026-10-10 hold, and do the claims match

Fixes. Rows 1, 3 to 8 and 10 to 23 of the 2026-10-10 table were found in the code as described. What is left:

| # | Where | What | Decision |
|---|---|---|---|
| F1 | pdu/PduStore.kt `setFolderPrivate` (row 2) | The fix is in the code (one transaction, repair at start), but no unit test would fail if the transaction were removed: the store needs Android's SQLite. Only the folder-selection rule is unit-tested. | Wording: the claim says "rule unit-tested, the write itself checked on a phone" (scripts/privacy-check.py). No code change. |
| F2 | sched/RemoteLlmClient.kt `asSchedError`, link/NodeServer.kt dedup | A resent job whose first run is still going after 30 s gets HTTP 409 "the same request is still running", and the client reads every 409 as "phone runs another model". Read against sched/SwarmLlm.kt: the job then goes to another phone either way (the one resend is already spent), so the effect is a wrong error text and a job that runs twice under overload. | Written down, not fixed today: no change in behaviour to gain, and a change in the scheduler on the day of the demo is not worth a clearer message. Second round: fixed (below). |
| F3 | llm/LlamaRuntime.kt memory check (row 9), link/PeerLinks.kt heartbeat paths | Held by phone checks, not by unit tests that would fail without them. | Accepted as phone-checked. |
| F4 | core/Signer.kt (deferred item 1: the signature covers method, path, time and body only) | Still open. Its reason, "Phone A stays on the older build", ended at 00:29 when Phone A took the current build. | Still deferred, new reason: a change of the signature on the day of the demo would have to reach all three phones at once and buys nothing a judge can see. |
| F5 | busy/BusyMode.kt `setEnabled`, `setHammer` | They write the audit entry on the caller's thread, which is the UI thread when a Settings button is used; the audit log is a synchronised file append. | Low. Fix candidate if another code change is made today (move the append to the existing save scope). |
| F6 | llm/LlamaArgs.kt:109 | A comment says whether the server's memory growth stops "is for a phone run to show"; evening-checks section 2 showed it (1.1 to 1.6 GB over 45 minutes). | Comment update with the next code change. |

Claims (docs/CLAIMS.md). Corrections to make:

| Row | What is wrong | Correction |
|---|---|---|
| HD1 | "without getting in the way of real work" is stronger than the notes: under keep busy a warm phone decodes real work at 10.8 tok/s where 13.5 is available. | Say what was measured: 0.07 to 0.59 s before a real request starts; real decode on a warm phone is slower. |
| HD1, gate 9 | "26 minutes and more" for Phone C's idle gap. | 36 minutes (21:35:18 to 22:11:43). |
| HD1 | The Settings line "Also runs with the screen off while charging." describes what the code does when the battery exemption is granted. The exemption was never granted on a phone, so it is unmeasured. | Say so in the row. The exemption is the founder's to grant. |
| HD1 | Hammer's helper text ("answers get slower and the phone gets hot", "pauses while the phone is too hot") is not measured: Thermal Status 3 was never reached on a phone. | Say so; measure Hammer on Phone A. |
| HD4 | "half-written case is unit-tested only" overstates the test (see F1). | Reword. |
| HD6 | The caveat "a phone joining mid-batch is unit-tested only" is out of date: evening-checks section 8 saw a joining phone take 10 of 32 jobs. | Reword; say 99.6 % in two of three runs. |
| HD12 | "survives the coordinating phone dying" is stronger than the run: the batch ended as interrupted with 7 of 32 jobs done. | Say that. |
| HD7 | The "switch" for leaner sources is a debug-build intent extra; there is no Settings toggle. | Say "debug build only". |
| PS5, R26 | "lease unit-tested (11 tests), not run on a phone" is out of date: 32 tests, and HD3 saw a lender restore itself on a phone. | Reword. |
| AG3 | "calls work while in the background" needs the caveat of evening-checks section 9 (status reads timing out, one 401 after 39 s, with the app in the background on a lit screen). | Add. |
| LK6 | The 4.1 s figure for a silenced path belongs to the old 1000 ms heartbeat; it is 2500 ms now, and the final build showed 2.3 s after a force-stop. | Quote 2.3 s; mark 4.1 s as the old timeout. |

Status lines: HANDOFF.md, README.md and docs/BUILD.md agree with each other and with the tree (f62da67, 1453 tests). All three still say Phone A is reserved and on the older build; HANDOFF.md line 45 carries an old table of builds per phone; README.md line 7 lacks a separator in one sentence.

## Reviewer 1: keep busy, scheduler, heat, battery, memory

No blocker and no high finding. Read against the code by the orchestrator where a decision depended on it.

| # | Where | What | Decision |
|---|---|---|---|
| R1 | sched/JobExecutor.kt `submit` | The slot is counted before the job's coroutine has run a line. A caller cancelled in that moment cancels the coroutine before it starts, so nothing ever gives the count back: with one slot per phone the phone refuses every job and runs no filler until the app restarts. Read and agreed; not seen on a phone. | Fix now, with a test that reproduces it. |
| R2 | busy/BusyLoop.kt, llm/LlamaServerClient.kt | A filler job has no time limit of its own. A model server that accepts a request and never answers holds the loop in "running" for up to ten minutes (the socket timeout): an idle phone that reports itself busy. | Fix now: 60 s limit, counted as a failed job. |
| R3 | core/ThermalGate.kt, sched/SchedNode.kt | The gate has a kind for work from another phone, and nothing calls it: at Thermal Status 4 or more a phone still accepts jobs from others. Only the sender's two-second-old view of the phone keeps work away. | Fix now: refuse work from another phone at status 4 or more. |
| R4 | memcloud/SplitCoordinator.kt `runBaseline`, `stop` | An exception in the single-phone baseline leaves the phase BASELINE, which counts as work in progress: the same class as the fault of 2026-10-10. No thrower was found. | Fix now. |
| R5 | busy/BusyMode.kt | Hammer: the flag is cleared before the time of the switch-off is written (a job can start in between); a rest that was running continues after Keep busy is switched off and on; Hammer stays on after "Stop Ustaad" and a start in the same process, although the text says it is off after a restart; the audit entry is written on the UI thread. | Fix now, four small changes. |
| R6 | busy/BusyLoop.kt `runOne` | Three calls sit between taking the filler slot and the try that gives it back, and the restore call runs before the release. If one of them threw, the slot would stay taken. No thrower named. | Fix now (ordering only). |
| R7 | sched/GatedLlmClient.kt, pdu/Answering.kt | A slot taken inside a timeout block can be lost when the timeout fires as the slot is handed over. | Fix now. |
| R8 | llm/LlamaRuntime.kt, RestartPolicy.kt | An automatic restart after a server death goes through the same heat and memory refusal as a manual load and is tried once. Refused, the phone has no chat model until the owner taps Load. | Written down. A retry loop in the runtime is not a change for the day of the demo; the refusal needs Thermal Status 4 or a memory shortage right after a server died, which frees its memory. Second round: fixed for a start refused for heat or memory (below). |
| R9 | llm/LlamaRuntime.kt memory check | The check reads free memory and reserves nothing: the chat and the embedding model are checked in parallel against the same figure. | Written down. The embedder is 0.3 GB against a margin of 1.5 GB. |
| R10 | llm/Bench.kt, Selftest.kt | A benchmark starts while a filler job may still be running for up to a quarter of a second, so the first tokens of a measured run can overlap filler, more often with Hammer on. Size not measured. | Written down: switch Keep busy off for a benchmark that is quoted. |
| R11 | busy/BusyPolicy.kt | After a pause for heat the loop resumes at the first reading under Thermal Status 3, with no settling time; with Hammer it could move back and forth on that edge. Thermal Status 3 was never reached on a phone. | Open until Hammer is measured for longer (Phone A). |
| R12 | busy/BusyCounters.kt | The Settings line calls every stopped filler job "stopped by real work", also those stopped because the model restarted, and does not show failed jobs. | Written down. Second round: half fixed, the line now names failed jobs (below). |
| R13 | busy/, llm/MemoryPressure.kt | Keep busy has no input for free memory; the low-memory signal only stops an idle embedder. With Hammer there is no back-off for memory. | Written down as a gap, not a fault shown. Second round: fixed (below). |

Checked and found sound by this reviewer: the Hammer and Keep busy switches are synchronised and coupled as described; the rest ends on a monotonic clock; a filler slot is given back only after its model call has ended; real work waits at most the stated three seconds behind filler; every caller that takes a slot releases it in a finally; signatures are checked on arrival, before any queueing; a dead phone ends in an error and not a hang; filler goes to the phone's own model only and its answers are dropped.

## Reviewer 2: failure paths and states that stay stuck

| # | Where | What | Decision |
|---|---|---|---|
| S1 | memcloud/SplitCoordinator.kt `startDegradedRestore` | High. After two minutes without a re-plan a degraded split gives this phone its models back and stays in the phase DEGRADED. That phase counts as work in progress, so keep busy runs no filler until someone presses Stop, and the "a large model is paused" banner comes back. The same class as the fault of 2026-10-10. Read and agreed. | Fix now. |
| S2 | memcloud/RpcLender.kt `stop`, SplitCoordinator.kt `confirmPeerRestored` | High, trigger not shown. A lender starts its own models again in the same instant it tells the rpc server to stop. The memory check for the chat model can run while the rpc server still holds the split's weights and refuse the load; the role is then FAILED, the list of what to restore is already cleared, and the driver reloads only roles it sees as STOPPED. The 30B split has not been run since the memory check was added. | Fix now: wait for the rpc server to be gone, and treat FAILED like STOPPED when restoring. Needs the 30B run on three phones to confirm. |
| S3 | memcloud/SplitCoordinator.kt:422 | A split that loses a phone in the last seconds of loading can be marked READY over DEGRADED, with no server behind it. | Fix now (one conditional). |
| S4 | pdu/PduEngine.kt `runAsk` | Only a cancellation is caught. Any other exception leaves the question in "searching" and the Ask button disabled, and Stop has nothing to stop. No thrower shown. | Fix now. |
| S5 | choir/ChoirRuntime.kt:123 | The busy flag is set before the try that clears it. | Fix now (one line). |
| S6 | llm/ModelStore.kt:239, ModelsUi.kt, memcloud/RpcWorker.kt:150 | A model whose load failed is treated as in use: it cannot be deleted and the list offers neither Load nor Delete for it until the server card's Stop is used. The same class again: FAILED tested as "not STOPPED". | Fix now. |
| S7 | link/LinkRoute.kt, link/LinkRuntime.kt `adopt` | The pairing message added on 2026-10-10 says "Will keep trying", and the retries end after ten minutes. Separately, a pairing with a host that never answers has already replaced the old secret, and nothing puts it back. | Wording fixed now. Restoring the old secret is written down: what a failed pairing should leave behind is the founder's decision. |
| S8 | memcloud/SplitCoordinator.kt restore retry | The retry that gives a peer its models back never gives up: a model file deleted on the peer, or a peer that left for good, keeps it running every five seconds, also after a restart. | Written down. Second round: fixed (below). |
| S9 | llm/StatusBoard.kt, RestartPolicy.kt | Two deaths of the GPU model server inside two minutes switch the phone to the CPU build for good, whatever killed it, the vendor system included. A note is shown and the GPU switch brings it back. | Written down; check the backend of every phone before the demo (all three are on the GPU as of 00:31). |
| S10 | sched/SwarmLlm.kt:466 | The one resend to a phone that missed a ping uses an abort signal that has already fired, so the job moves to another phone for nothing. Heals at the next probe. | Written down. Second round: fixed (below). |
| S11 | memcloud/RpcWorker.kt:104, RpcLender.kt:118 | A narrow race can leave a loan's lease without its timer; the list of models to restore is kept in memory only, so a lender whose process dies gets its default models back and not a non-default one. | Written down. |
| S12 | link/LinkRuntime.kt:156 | The link is marked started before its ten start steps; an exception in the middle leaves it half up until it is stopped. No thrower shown. | Written down. |

Checked and found sound by this reviewer: the flags keep busy trusts for benchmark, self-test, index run, downloads and loans; the order in which a driver records what it will stop on a peer before it stops it; a lender dying and a driver dying in a split; the fallback from the GPU build to the CPU build is bounded; the watch for a frozen server; bounded download attempts; Knowledge Bank revocation is atomic; a batch whose peer dies or joins; a dead phone ends in an error; a silent peer in a Data Union ask is bounded; link failover retries only while the caller is waiting.

## Codex (gpt-6.1-sol requested, high effort; it named itself "GPT-6 (Codex)"; 195 files read)

Raw output: `2026-10-11-codex-audit.txt` (8 high, 18 medium, 1 low). Of the 23 fix rows of 2026-10-10 it re-checked 15 and found them holding (1 to 4, 6 to 8, 10 to 13, 19, 20, 22, 23). Each finding below was read against the code before it was sorted.

| # | Where | What | Decision |
|---|---|---|---|
| C1 | link/LinkRuntime.kt `confirmPairing` | The Pair button adopts whatever pairing code is pending at the moment of the tap. A second pairing link arriving between the dialog being drawn and the tap is adopted without having been shown. | Fix now: the button confirms the code it displays, and is refused if another one has arrived. |
| C2 | MainActivity.kt pairing link with `confirm` | In a debug build any app on the phone can send a pairing link with the confirm flag and replace the pairing secret with no tap. Debug builds only; the phones run the debug build. This is the path scripts use to pair phones, and the one used to pair Phone A at 00:31. | Written down. The demo build ignores the flag (docs/BUILD.md). If the pitch says "pairing needs the owner's tap", it has to be said of the demo build. |
| C3 | sched/JobExecutor.kt:143 | The lost slot of R1, found independently, with one more consequence: the job's entry also stays behind, so a resend of the same job would wait on it for ever. | Fix now (R1). |
| C4 | memcloud/RpcLender.kt:249, SplitCoordinator.kt `releasePeer` | A release that could not be confirmed is retried later without the loan's token. If the same driver has started a new loan on that phone meanwhile, the late stop is accepted and ends the new loan. The token rule of 2026-10-10 (row 5) stops a stale stop that carries an old token, not one that carries none. | Fix now: the retry keeps and sends the token it was given. |
| C5 | memcloud/SplitCoordinator.kt `stop` | Stop does not wait for the operation it cancels, and its own teardown is not registered as the running operation. A start sent while that teardown runs is admitted and can be torn down by it. From the screen the Start control is not offered until the phase is IDLE, so this needs the API. | Written down. |
| C6 | memcloud/SplitCoordinator.kt:948 `baseline` | The single-phone baseline can be started twice: until the first has released the lenders the phase is still READY or DEGRADED, and in those phases a second call is let through. A double tap is enough. | Fix now: the phase is taken before the work is launched. |
| C7 | llm/LlamaRuntime.kt:413 | The memory check is per role, not across roles (R9). | Written down. |
| C8 | sched/SwarmLlm.kt:588 | The owner's own request runs on this phone at Thermal Status 4 when no other phone can take it. | Not a fault: the owner asked and gets an answer. Work from other phones is refused at that status after R3. |
| C9, C10, C11 | memcloud/SplitCoordinator.kt:651, :476, :493 | Three more ways for a restore to be lost: the driver killed between stopping its own models and recording them (only with "load models at start" off); a restore refused for heat at Thermal Status 4 is not tried again; a lender's restore refused while the driver has already forgotten it. | The third is S2, being fixed. The first two are written down with R8: no phone has reached Thermal Status 4. Second round: the second (C10) is fixed (below); the first stays written down. |
| C12 | kb/KbEngine.kt:144 | The Knowledge Bank's cards are sealed with a key from the pairing secret. A new secret adopted and the app killed before the bank has re-sealed them leaves them unreadable. | Written down. Pairing was done three times tonight with no kill in between. |
| C13 | AppGraph.kt:100, choir/ChoirRuntime.kt:73 | Choir and the split use the gated model client without the guard that refuses the placeholder model. If the chat model is stopped while a Choir voice waits for its slot, that voice can answer "[FAKE] No chat model is loaded on this phone." The text is labelled; it would count as one voice's answer. | Written down: do not stop a model during a Choir run. |
| C14 | busy/BusyLoop.kt:143 | Switching Hammer off, the phone getting too hot, the battery falling or the app going to the background do not stop a filler job that is already running; they take effect at the next job. A job is a few seconds, and at most 60 s after R2. | Written down. |
| C15 | busy/BusyApp.kt:76 | Claim about the bundled model server: filler jobs sent with the prompt cache off may still push real conversations' cached prompts out of the server's cache, and a real request may be given the slot meant for filler. Read from native code Codex says it mostly did not examine. | Measured on Phone A and not borne out for one conversation: 0.33 to 0.35 s for the repeated prompt after 18 filler jobs (see the last section). |
| C16 | llm/LlamaRuntime.kt:711 | A model server death during filler uses up the one automatic restart; a second death within two minutes, during real work, leaves the phone without a chat model until the owner loads it. | Written down with R8. Still written down: the second round retries a refused start, not a second death. |
| C17 | memcloud/MemCloudLogic.kt:137 | DEGRADED after its own release still counts as in use (S1), found independently. | Fix now (S1). |
| C18 | sched/SwarmLlm.kt:441 | Says the overall deadline of a job is not enforced across phones, each attempt getting a fresh budget. Reviewer 1 read the same function as ending at twice the budget. | Not settled; written down as a disagreement between two readers. |
| C19, C20 | sched/BatchRunner.kt:224, :264 | A batch accepted and the coordinator killed before the first report is written answers "not found" instead of "interrupted"; two workers can write the report file at the same time. | Written down. Second round: both fixed (below). |
| C21 | pdu/IndexRunner.kt:35 | When the last file of a shared folder is deleted, its text stays in the index and is still served to paired phones. The rule is deliberate (an empty listing looks the same as a folder that cannot be read, so it never wipes the index), and its cost is this case. | Written down, and it belongs in what is said about privacy: after deleting the last file of a shared folder, stop sharing the folder. |
| C22, C23 | pdu/PduEngine.kt:264, choir/EvalSession.kt:99 | An index run whose folder listing never returns cannot be stopped; a Choir evaluation stopped with the storage full stays "running". | Written down. |
| C24 | link/BleProtocol.kt:122 | A signed request that waits more than 30 s behind a slow Bluetooth transfer arrives with a stale signature. | Written down; Bluetooth is the fallback path. |
| C25 | core/SwarmService.kt:208 | "Stop Ustaad" during a batch stops this phone's servers, and the batch already under way can still send jobs to the other phones. | Written down. Second round: fixed, and seen on a phone (below). |
| C26 | llm/ModelStore.kt:232 | A download cancelled while its checksum is being computed and started again at once can be overwritten by the old run. | Written down. |
| C27 | llm/ModelStore.kt:239 | A failed model counts as in use (S6), found independently. | Fix now (S6). |

Three findings were made independently by Codex and by a reviewer (the lost slot, DEGRADED after release, the failed model in use), and two more overlap in substance (the memory check, a lender's restore). None of the three readers found a way for an unpaired device to read data or run a model.

## What was fixed (merged on master as 5724511 and fa8b5cd, plus 574c1fd; 1499 unit tests, 0 failures)

Seventeen findings were fixed by two Sonnet builders in separate worktrees and one by the orchestrator. Where the table says "failed before", the builder ran the new test against the old code and saw it fail.

| Finding | Fix | Held by |
|---|---|---|
| R1, C3 lost slot | The job's coroutine is started so that it always runs its first lines, and leaves through the normal clean-up when it finds itself cancelled. | `JobExecutorCancelBeforeStartTest` (immediate slot, queued job, job queued behind filler); 3 of 4 failed before |
| R7 slot lost in a timeout | The slot taken inside the timeout block is remembered and given back if the block's value is lost. | No test: the race needs two threads at the right instant |
| R6 filler slot | Nothing that can throw sits between taking the filler slot and the try; the slot is given back even if restoring the shared figures throws. | Two tests in `BusyLoopTest`; both failed before |
| R2 filler time limit | `BusyPolicy.FILLER_TIMEOUT_MS` = 60 s. A job over it is cancelled, waited for, and counted as failed, with the existing ten-second back-off. | Tests at 59.999 s and 60 s; a preemption at 10 s still counts as cancelled |
| R5 Hammer | The switch-off time is written before the flag; Keep busy off ends a rest that was running; "Stop Ustaad" switches Hammer off; audit entries are written in order by one writer off the caller's thread. | Four tests in `BusyModeTest`; all failed before |
| R3 heat | Chat, stream and embedding work from another phone is refused at Thermal Status 4 or more, the way a full phone refuses it. The owner's own requests are not. The status is the phone's two-second health reading. | `SchedHeatTest` (refused at 4, 5, 6; accepted at 3; the owner's four paths accepted at 4) |
| S1, C17 released DEGRADED | The state records that the degraded split has given the phone back; that state no longer counts as in use and no longer shows the banner. A re-plan, a new start, a stop or a failure clears it. | Five tests |
| R4 baseline, stop | Every exit of the single-phone baseline ends in IDLE with a result, the flags cleared and the phone's own models back. Stop reaches IDLE even if its teardown throws. | Three tests |
| C6 baseline twice | The baseline takes its phase in the same step that admits it, so a second call is refused. | One test: second call refused, one server start |
| S3 READY over DEGRADED | READY is written only if the phase is still LOADING. | One test that degrades the split during the final refresh |
| S2 lender's restore | The lender waits up to 5 s for the rpc server's process to be gone before it starts its own models (outside its lock; the Stop lending button no longer waits on the main thread). The driver reloads a role it finds FAILED as it does one it finds STOPPED. | Ordering tests for stop, a driver's stop and lease expiry; two driver tests |
| C4 loan token | The token of a loan stays with the record of a release that could not be confirmed, is sent on every retry, and is stored with it. A retry that finishes after a new split has taken the phone no longer clears that split's record. | Tests in both test classes, including after an app restart |
| S4 failed ask | Any exception in an ask ends it as done with the note "Something went wrong while answering. Ask again." and is passed on as before. | `AskFailureTest`, on the pure step that closes the ask |
| S5 Choir busy | The flag is set immediately before the try that clears it. | Reordering, no test |
| S6, C27 failed model | "In use" now means READY or STARTING, for delete, for the models list and for the rpc cache. | Tests in `ModelsUiTest` and `RuntimeHelpersTest` |
| S7 pairing text | "Ustaad keeps trying for 10 minutes. After that, scan the code again." (the figure comes from the code's own constant) | `UnreachableHostTextTest` |
| C1 pairing confirm | The Pair button adopts the code its dialog showed; if another has arrived, nothing is adopted. | `PairingConfirmTest` |

Not verified by a unit test and waiting for a phone: the lender's wait for the rpc server (S2) on a real split, above all the 30B split on three phones; "Stop Ustaad" switching Hammer off; the released state on a real degraded split.

One test is unreliable and is not from these changes: `SchedStreamTest.aStreamThatBreaksAfterTokensIsNotRetriedOnAnotherPhone` failed in 2 of 6 partial runs on the unchanged tree and passed in every full run.

## Measured on phones for this audit

- Regression on the build before the fixes, phones A and B (docs/benchmarks/2026-10-11-final-regression.md): fault injection 6 of 6 on Phone A, MemoryCloud between B and A 9 of 9, agent endpoint 14 of 14, setup checks 12 of 12. The privacy check failed once and passed on the second run: after a forced stop Ustaad on Phone A was a live process that served nothing for over 90 s, until it was opened a second time. Cause not found.
- Not run: the batch checks across two phones. The permission layer of the session refused them for the tester (they switch Keep busy off and force-stop an app during a batch), and they were not attempted another way.
- C15, prompt cache under filler (raw/final-audit/prompt-cache/): on Phone A at half duty a repeated 214-token question read its prompt in 2.0 to 2.1 s the first time and in 0.33 to 0.35 s the second time, with 0, 6 and 18 filler jobs in between. Filler did not push it out of the cache. One conversation, one run; a cache already full of other conversations was not tried.
- Hammer for 25 minutes on Phone A: docs/benchmarks/2026-10-11-hammer.md.

## Second and third pass on Phone A, and one fault the fixes introduced

Second pass, fixed build fa8b5cd on Phone A alone (docs/benchmarks/2026-10-11-final-regression.md, second section): agent endpoint 14 of 14; eight forced restarts in a row with Ustaad answering after 1.1 to 2.1 s and the chat model ready after 15 to 17 s, so the restart fault of the first pass did not show again (it had shown once in two runs; whether a fix cured it or its trigger was something else is not known); keep busy counting again after the two-minute settle, 29 jobs in 62 s, 0 failed; a local batch 32 of 32 at 9.41 tok/s. On the same build "Stop Ustaad" pressed with Hammer on switched Hammer off, and it was off after "Start Ustaad" (R5).

Fault injection gave 5 of 6. The failure was real and came from a fix: with the chat server frozen, the watch that kills and restarts a frozen server took 207.7 s where the build before the fixes had taken 98.9 s. The 60 s limit on filler jobs (R2) started a new filler call every 70 s, and the watch does not count a missed ping while a call is in flight, so its three misses took three rounds. Corrected in 02b15c2: a filler job that ran past its limit is followed by a 45 s wait, which leaves the watch its three pings. Third pass on that build, Phone A (raw/final-audit/pass3/): the same check passed, the frozen server was killed after 85.5 s and came back READY on the GPU.

All unit tests were green on the build that had this fault. It was the phone that showed it.

## Third and fourth pass on two phones, and a second round of fixes

Third pass, build 02b15c2, phones A and B, 02:52 to 03:16 (docs/benchmarks/2026-10-11-final-regression.md, third section). Phone B came back from the founder at 02:48 and took the build at 02:51, so the MemoryCloud fixes ran on phones for the first time, across two phones with the 4B model:
- `memcloud-check` 9 of 9 in each direction: the split READY after 34 and 32 s, answers at 6.09 and 5.81 tok/s, the lender's chat model READY 3 s and 0 s after a stop and 27 and 26 s after the driver's app was killed (S2, C4).
- A degraded split left alone (S1): the lender was force-stopped, the phase read DEGRADED 5.3 s later, the driver released itself 122.5 s after that, its own chat model was READY on the GPU about 16 s later, and filler was counted again 138 s after the release (2 to 33 jobs in the next 60 s, 0 failed) while the phase still read DEGRADED.
- Privacy 3 of 3, end to end 12 of 12, a two-phone batch 32 of 32 in 36.3 s at 24.52 tok/s (Phone B 15 jobs, Phone A 17).

One new finding from that pass, P1. The check had left Ustaad on Phone A force-stopped for 5.5 minutes and the screen had gone to sleep (timeout 300 s). Started in that state, Ustaad answered for about half a minute, then no call was answered, its GPU load was not ready after 120 s and it fell back to the CPU build. It took a wake, a keyguard dismiss, a start, the GPU switch off and on and a reload by hand to bring it back. Not fixed: a person starts the app with the screen on. What it shows is S9 again (a phone on the CPU build stays there until its owner uses the GPU switch) and one more cost of the battery exemption that no phone has. Check the backend of every phone before a demo.

Second round, master 17aad2d, 1532 unit tests, 0 failures, installed on Phone A at 03:17:31 and Phone B at 03:17:45. Chosen from the findings that were written down because each is a fallback, small and testable (plans/2026-10-11-final-audit.md, step 8):

| Finding | Change | Held by |
|---|---|---|
| R13 no back-off for memory | Keep busy pauses while free memory is under 1024 MB, with Hammer on as well, and looks again every 10 s. An unknown reading does not pause it. | Four tests in `BusyPolicyTest`, one in `BusyLoopTest` |
| R8, C10 a refused automatic start | A model start the app made by itself (the load at app start, the restore after a split or a loan, the restart after a death) that the heat gate or the memory check refuses is tried again every 30 s, up to 20 times. The status says what it waits for. A load the owner asked for is never retried. The loop ends on Stop, on a new load, when the service is down or when the file is gone. | `StartRetryTest`, 11 tests |
| S8 restore retry that never gives up | The wait between rounds doubles from 5 s to 60 s while the calls fail and starts again at 5 s after progress. A file the peer answers 404 or 400 for is left alone and said once. | `MemCloudLogicTest`, three tests in `SplitCoordinatorTest` |
| S10 resend with a fired signal | The one resend to a phone that came back uses that phone's current abort signal. | One test in `SwarmLlmTest`; failed before |
| F2 every 409 read as a wrong model | Only a 409 that carries a model is a model mismatch. Any other is a job still running after 30 s: a timeout, the phone is there. | `RemoteConflictTest` |
| C19 unknown batch after a kill | The first report is written before the id is returned. | `BatchStoreFilesTest`; failed before |
| C20 two writers on one report | One writer at a time, each write to its own temporary file and moved over the report; a temporary file left by a killed write is removed. | `BatchStoreFilesTest`; failed before |
| C25 Stop Ustaad during a batch | "Stop Ustaad" ends the batches that run (it waits at most 3 s) and their report says `interrupted`. | Three tests; the call in the service itself is not unit-testable |
| R12, half | The Settings line names failed filler jobs when there are some. Jobs stopped by a model restart are still counted under "stopped by real work". | One test in `BusyModeTest` |

Fourth pass, on that build, phones A and B, 03:19 to 04:08 (same file, fourth section):
- Fault injection on Phone A 6 of 6, agent endpoint 14 of 14, four forced restarts with the chat model READY on the GPU after 16.2 to 17.6 s. The frozen server was killed after 35.0 s in the script's own step and after 91.6 s in a second measurement with Keep busy at half duty; why the two differ was not looked into.
- `memcloud-check` 9 of 9 in each direction again: the split READY after 34 and 36 s, 5.84 and 5.48 tok/s, the lender READY 2 s and 1 s after a stop and 27 s after a driver kill.
- C19 on a phone: the status of a batch read 44 ms after it was accepted answered `running`, 0 of 32 done.
- C25 on a phone: a 96-job batch on Phone A, "Stop Ustaad" pressed 5.5 s in with 2 items done. Phone B's count of running jobs was 0 at the first read 4.7 s after the tap and in all 14 reads over the next 62 s. After "Start Ustaad" the batch read `interrupted`, 2 of 96.
- Two batches 32 of 32 at 21.23 and 21.07 tok/s. Keep busy over two 5-minute windows on both phones: 0 failed jobs, 50 to 60 % of the time generating, no pause for memory with 4716 to 5245 MB free. Privacy 3 of 3, end to end 12 of 12.
- Not seen on a phone, because the condition was not produced: the pause for low memory (R13), a refused start being retried (R8, C10), the back-off of the restore retry (S8), the 409 reading (F2), the resend signal (S10) and the wording for failed jobs (R12). Unit tests hold them.
- The pass left one file on Phone A, `/sdcard/__ui.xml` (24 KB, a screen dump). It was not deleted.

## Tally and what is open

| | Count |
|---|---:|
| Findings fixed, each read against the code first | 27 (18 in the first round, 9 in the second), plus the correction of the frozen-server watch and half of R12 |
| Written down with a reason | 24 (23 of the first 32, and P1) |
| Judged not a fault | 1 (C8) |
| Measured on a phone and not borne out | 1 (C15) |
| Claims reworded in docs/CLAIMS.md | 11 |
| Unit tests | 1453 before, 1532 after, 0 failures |
| Regression runs on phones | One on phones A and B before the fixes, two on Phone A after the first round, two on phones A and B (builds 02b15c2 and 17aad2d); none on Phone C |

Open, in the order they matter for a demo:
1. Phone C. It is in the founder's remote-control session since about 00:25, still carries f62da67 without any fix of this audit, and nothing was run on it.
2. The 30B split on three phones has not been run since the memory check before a load was added on 2026-10-10. The MemoryCloud fixes ran on two phones with the 4B model only (third and fourth pass).
3. Keep busy is paused on any phone where Ustaad is not in front, until the battery exemption is granted, and a phone started with its screen asleep fell back to the CPU build (P1). Only the founder can grant the exemption.
4. The batch checks across two phones (a peer dying, a peer joining, the coordinator killed) were last run on the build of 2026-10-10 evening: the permission layer of the session refused them for the tester. On this build only "Stop Ustaad" on the coordinator was run.
5. The pauses and retries of the second round whose condition could not be produced on a phone (list above).
6. The 24 findings written down, of which these three should be known before a pitch: in the debug build, which the phones run, another app can replace the pairing secret with no tap (C2); the text of the last file deleted from a shared folder stays searchable until the folder is unshared (C21); a model stopped during a Choir run can put a labelled placeholder line among the voices (C13).
7. One unit test, `SchedStreamTest.aStreamThatBreaksAfterTokensIsNotRetriedOnAnotherPhone`, failed in some runs on a loaded PC and passes in full runs. No failure trace was kept, so its cause is not known.

"Production grade" stays unproven (docs/CLAIMS.md HD9). What can be said is what this file lists: who read the code, what they found, what was fixed and how it is held, what was run on phones, and what was left.
