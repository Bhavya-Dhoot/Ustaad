# Evening checks on phones B and C: one-shot batch jobs, a 45-minute keep-busy soak, low memory, background (2026-10-10, 16:00 to 18:30)

Phones B (192.168.242.23) and C (192.168.242.76), iQOO 15, debug build `com.swarmos.app`, chat `Qwen3.5-4B-Q4_K_M` on the GPU. Phone A stayed with the founder and took no part. "Before" is the build of master b8b4777 (installed 15:01); "after" is the build with the three changes of section 1 (installed 17:31 on B, 17:32 on C). One run of each check unless a count is given. Raw results: `raw/gate-9b/`. Two side experiments have their own notes: `2026-10-10-prompt-fixed-cost.md` (where the fixed cost per request comes from) and `2026-10-10-ngram-speculation.md` (speculative decoding, rejected).

## 1. Batch jobs without the checkpoint they never reuse
Finding (Phone C, prompt-fixed-cost note): this model needs a context checkpoint for any prompt-cache reuse, so the model server reads every prompt in two passes with a checkpoint between them. That is about 0.29 s per request (fixed cost 485 ms, 194 ms with checkpoints off), and switching checkpoints off for everything would make a repeated 202-token prompt cost 1713 ms instead of 343.

Change: the server skips the checkpoint only for a request sent with the prompt cache off (three lines, `native/patches/server-no-checkpoint-without-cache.patch`), and the app sends batch jobs that way; the keep-busy filler already did. Every other caller (Choir, Data Union, chat screen, agent endpoint, single jobs) keeps the cache on and runs the same server code as before. Both shipped server binaries were first rebuilt from the untouched source and matched the old ones byte for byte, then rebuilt with the patch; the three other native binaries are unchanged. A phone on an older build ignores the new field.

The 32-job batch on each phone alone, started at Thermal Status 0:

| | Phone B before | Phone B after | Phone C before | Phone C after |
|---|---|---|---|---|
| Throughput, whole batch | 10.33 tok/s | 11.11 tok/s | 10.08 tok/s | 10.99 tok/s |
| Wall-clock | 86.7 s | 80.0 s | 88.9 s | 80.9 s |
| Prompt time per job, median | 562 ms | 398 ms | 667 ms | 400 ms |
| Prompt time, sum of 32 jobs | 19.5 s | 12.7 s | 20.3 s | 12.8 s |
| Decode speed | 13.9 tok/s | 13.6 tok/s | 13.6 tok/s | 13.4 tok/s |
| Thermal Status, start to end | 0 to 1 | 0 to 2 | 0 to 1 | 0 to 1 |

Gain: 7.5 % on Phone B and 9.1 % on Phone C, all of it from prompt time; decode was slightly slower in the after runs (heat). One run per cell.

Answers: 29 of the 32 answers are word for word the same before and after, on both phones. Three (items 1, 12 and 27) are a different wording of the same fact, for example "guides ships away from dangerous coastlines" against "shines on the horizon to warn ships of nearby dangers"; 896 tokens before, 889 after. Reading the prompt in one pass instead of two changes the rounding, and at temperature 0 a near tie then falls the other way. The new answers are the same on both phones (32 of 32), as the old ones were. So this is not "identical output"; it is the same model giving an equally valid answer for one prompt in ten.

Unit tests: 1424, 0 failures (1414 before this change, 1406 at gate 9).

## 2. Keep busy for 45 minutes, with a real request every five minutes
Switch on, app in front, charging, 16:44 to 17:29 (2687 s, sampled every 15 s), before build. A real 48-token request was sent to each phone about every five minutes through the signed chat route.

| | Phone B | Phone C |
|---|---|---|
| Time generating filler | 1317 s of 2687 s (49.0 %) | 1553 s of 2687 s (57.8 %) |
| Thermal Status | 0 for 1.2 min, 1 for 1.8 min, 2 for 41.5 min | 1 for 9.5 min, 2 for 35.0 min |
| Filler jobs, tokens | 465 jobs, 10,101 tokens | 457 jobs, 9,958 tokens |
| Failed, cancelled by real work | 0, 4 | 0, 4 |
| Chat server memory | 1148 to 1459 MB (first 1357, last 1407) | 1320 to 1616 MB (first 1407, last 1386) |
| Chat server restarts | 0 | 0 |
| Real requests answered | 9 of 9 | 9 of 9 |
| Real request decode speed | 13.4 to 13.6 tok/s for the first eight (13.9 cool); the ninth 2.3 | 13.3 for the first, then 10.8 to 10.9 (13.6 cool) |
| Real request, time outside the model | 0.06 to 0.77 s | 0.06 to 0.50 s |

What it shows:
- Over 45 minutes the share is about half, not the 63 % and 84 % of the 16-minute runs in `2026-10-10-hardening-checks.md`: both phones reach Thermal Status 2 within minutes and then stay on half duty (median share while on half duty 0.497 and 0.495). Neither reached status 3, so the pause at 3 is still not exercised on a phone.
- Real work on Phone B kept its speed while the phone was held at status 2 (13.4 to 13.6 against 13.9 cool, 3 % down). Phone C lost a fifth (10.8 against 13.6). Same build, same room, same duty; the two phones throttle differently.
- Memory of the chat server stayed between 1.1 and 1.6 GB for the whole run with the bounded prompt cache. It did not grow.
- The repeated real question took 683 and 688 ms of prompt time the first time and 328 to 424 ms afterwards, with filler jobs in between: the prompt cache of real work survives filler.
- For the last 2.8 minutes Phone B's filler stood aside (state `waiting_for_idle`) because other real work was running on that phone. It was not sent by these scripts and was not identified. The ninth real request shared the model with it and decoded at 2.3 tok/s. Two generations at once on one phone are that slow (see `2026-10-10-slots-experiment.md`); the scheduler's one-job limit did not cover whatever that other caller was.
- The same question at temperature 0 was answered with 21 to 48 tokens across the nine repeats. Not looked into; a cache-restored prompt and a freshly read one can round differently.

## 3. Low-memory signal (Phone B, before build)
`am send-trim-memory com.swarmos.app RUNNING_CRITICAL` with the embedder idle. Four seconds later the embedder read STOPPED with the notice "Stopped to free memory (Android reported the phone almost out of memory). It starts again when something needs it."; the chat server was untouched (READY). The next Data Union question started the embedder again (embedding step 1691 ms instead of the usual 23 to 50 ms) and answered correctly ("due on 24-Sep-2026 and amounts to INR 2,410.00"); the embedder read READY afterwards. This is the system signal sent by hand, not a phone that was really out of memory.

## 4. Keep busy with the app in the background (Phone C, before build)
Running (10 filler jobs in 25 s); Home key; 20 s later `paused_background` and no further job in the next 20 s; app brought back; `running` again within 20 s. The battery exemption is not granted on B or C (`dumpsys deviceidle whitelist` has no entry for the app), so on these phones keep busy only works with Ustaad on screen.

## 5. Rejected: speculative decoding without a draft model (Phone B)
Details in `2026-10-10-ngram-speculation.md`. The one setting that drafted often cut decode speed by 25 to 43 % on Data Union and Choir prompts (7.57 against 11.54 to 13.34 tok/s) and changed one answer in twelve; the default setting never drafted. Left off. While a second, hand-started GPU server ran beside the app's own, the app's signed API stopped answering for minutes at a time; that set-up does not occur in the app, and the cause was not looked into.

## 6. Regression on the after build (17:42 to 18:27, phones B and C)
Run by a tester with the existing scripts; raw output under `raw/gate-9b/regress/`.

| Check | Result |
|---|---|
| Set-up (`e2e2-check.sh` step s, 12 checks) | pass; 51 signed answers checked, 0 wrong |
| Fault injection on Phone C (`hardening-check.py` g, m, t, h, c) | 5 of 5 pass: oversized load refused in 0.3 s; server killed mid-answer returns HTTP 500 `{"error": "the model stopped mid-answer"}` (it read "EOFException" before); frozen server noticed, restart begun after 34.5 s; after two kills the chat ran on the CPU build and the GPU switch restored it in 16.4 s |
| MemoryCloud, C driving, B lending (`memcloud-check.py` v, n, s, k) | 4 of 4 pass: bad split 400, over-sized loan 400, split answered at 3.45 tok/s, stale stop 409, driver killed and the lender had its models back after 26 s |
| Folder privacy across a kill (`privacy-check.py`) | pass: a peer's query returns nothing, 1 withheld |
| A folder that is not shared is withheld (`e2e2` step p) | pass: Phone B withheld 3 items from Phone C |
| Agent endpoint on Phone C (`agent-check.py` a, b, c, d, f, g, j) | 7 of 7 pass |
| Tools and file search (`agent-check2.py` t, u, v, s, w, x) | pass: right tool 8 of 8, direct answer 4 of 4; w and x returned 403 on the first try because an earlier step had left file search switched off, and passed once it was switched on again |
| Knowledge Bank, B to C, by hand | pass: a shared card saved on B was listed on C after 0.31 s; a shared card with a private source was refused (409); a revocation on B reached C after 0.32 s |
| Data Union asks (`e2e2` step d) | not a clean run: every ask from Phone B is marked failed by the script only because the paired Phone A did not answer ("peer not ok: Phone A:offline; flagged incomplete"), which is the designed behaviour with a phone missing; the script's "private by default" check assumes a fresh install and Phone B's folders were already shared |
| Knowledge Bank step of the script (`e2e2` step k) | stopped at its first check: the real answer it tried to save had a private source and the app refused to share it (409), as it should; its later checks did not run, hence the check by hand above |

The step-d script, finding no certificate on a peer, pushed its sample certificate to Phone C for the two-file question and removed that one file afterwards. With Phone A absent the certificate it cited is that sample, not Phone A's. At 17:50 Phone A's app came online by itself and one ask from Phone B reached it through the app's normal fan-out; no command was sent to Phone A by the tester.

## 7. GPU model start, old build against new build (Phone B, Thermal Status 0 throughout)
The regression read 15.8 to 17.8 s for a GPU start where the afternoon had read 12.1 to 13.4 s, so the two builds were installed in turn on one phone, three starts each:

| Build | Start times |
|---|---|
| old (b8b4777) | 13.4, 13.4, 13.7 s |
| new | 12.7, 14.9, 14.9 s |
| old again | 14.3, 16.1, 16.4 s |
| new again | 14.0, 15.8, 16.5 s |

The build makes no difference; the time creeps up with each start in a row, on either build, from about 13 s to about 16.5 s. The compiled-program cache is intact (197 files, none rewritten since 14:31). So "GPU start 13 s" holds for a phone that has not just loaded a model several times, and 16 to 18 s is what repeated starts or a warm phone give. The cause of the creep was not looked into.

## Not measured
Anything with Phone A (the large-model split, Choir, three-phone throughput, the two switches that are off). Keep busy on battery, with the battery exemption, or beyond 45 minutes. A phone that is really out of memory. The one-shot change on a batch spread over two phones (only each phone alone was run before and after).
