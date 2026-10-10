# Final regression of the evening's checks on phones A and B, 2026-10-11

Date and time: 2026-10-11, 00:34 to 01:10 IST (scripts ran 00:37 to 01:04; clock reading in `docs/benchmarks/raw/final-audit/00-start/clock.txt`, per-script start and end times in each sub-folder's `started.txt` and `ended.txt`). Phones: Phone A (`192.168.242.89`) and Phone B (`192.168.242.23`) only. Phone C was not addressed by any command of mine (no adb, no API call); what that meant for the swarm-wide steps is under sections 5 and 6.
Run by a device tester: nothing built, nothing installed, no code edited. Raw output of every script: `docs/benchmarks/raw/final-audit/<sub-folder>/stdout.txt` plus the JSON the script wrote beside it. All numbers below come from those files unless a line says otherwise.

## Build

| Phone | versionName | lastUpdateTime | base.apk sha256 (first 16) | Source file |
|---|---|---|---|---|
| A | 0.1.0 | 2026-10-11 00:29:01 | 2573d917d9a098d7 | `00-start/build-A.txt`, `00-start/apk-sha-A.txt` |
| B | 0.1.0 | 2026-10-11 00:06:46 | 2573d917d9a098d7 | `00-start/build-B.txt`, `00-start/apk-sha-B.txt` |

Same apk on both (hash prefix equal). Models folders identical on both: `Qwen3.5-4B-Q4_K_M.gguf` and `embeddinggemma-300M-Q8_0.gguf` only (`00-start/models-dir-A.txt`, `-B.txt`).

State before the first script (read live at 00:35 with `phone-probe.py state`, `get /v1/sched/busy`, `get /v1/memcloud/state`; not saved to a raw file, but `gate-log.txt` from 00:43 shows the same READY, STOPPED and thermal values): both phones chat READY on GPU, embedder READY, rpc STOPPED, Keep busy enabled (`half_duty`, `hammer` false), MemoryCloud IDLE, agent access off. Thermal status was 2 on both at the start, so everything below ran warm (see Anomalies).

## 1. `scripts/hardening-check.py` on Phone A (`01-hardening/`)

Commands: `python scripts/hardening-check.py 192.168.242.89 <dir> th`, then `... gc` into `01-hardening/gc/`.

| Check | Result | Measured |
|---|---|---|
| t-truncated-stream | PASS | chat server pid 29404 killed 6 s into a long answer: HTTP 500 `{"error": "the model stopped mid-answer"}` |
| t-restart | PASS | chat READY on GPU, restarts 0 (as printed) |
| h-hung-server | PASS | pid 31818 frozen; first change after 98.9 s (state STARTING); then READY on GPU, new pid 729, restarts 1; the app's own line: "stopped answering /health for 30 s, killed by Ustaad" |
| m-memory-preflight | NOT RUN | `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` is not in Phone A's models folder (`00-start/models-dir-A.txt`, `00-start/models-A.json`) |
| g-gpu-start | PASS | three reloads READY on GPU, loadMs 17746, 16850, 17735; program cache 5 files; thermal 2 (`01-hardening/gc/stdout.txt`) |
| c-cpu-fallback | PASS | two kills: chat READY on CPU, restarts 1, notice "The GPU server failed ... Running on the CPU build." |
| c-gpu-restored | PASS | after switching the GPU off and on: READY on GPU, loadMs 18041 |

Counts: 6 PASS, 0 FAIL, 1 not run. g and c were run because time remained; the brief said skip them otherwise.

## 2. `scripts/memcloud-check.py`, B drives, A lends (`02-memcloud/`)

Command: `python scripts/memcloud-check.py 192.168.242.23 192.168.242.89 "Phone A" <dir> vnsk`.

| Check | Result | Measured |
|---|---|---|
| v-bad-layers | PASS | layers [0, 32]: HTTP 400 "every phone needs at least one layer"; driver phase IDLE |
| n-lender-memory | PASS | 999999 MB loan: HTTP 400 "this phone has about 5223 MB available"; lender models stayed READY |
| s-load | PASS | split READY after 48 s (loadMs 39899); lender lendingTo Phone B, lender chat and embed STOPPED, rpc READY |
| s-ask | PASS | 74 tokens, first token 8394 ms, 4.12 tok/s |
| s-stale-stop | PASS | wrong loan token: HTTP 409 "the loan belongs to Phone B"; lender still lending |
| s-cache-clear-refused | PASS | HTTP 409 while lending |
| s-restore | PASS | driver models back in 19 s, lender in 0 s, rpc STOPPED |
| k-driver-killed | PASS | driver app force-stopped 2 s into a start; lender READY again after 26 s, lendingTo None |
| k-driver-recovered | PASS | driver READY again 16 s after relaunch, phase IDLE |

Counts: 9 PASS, 0 FAIL. For comparison only, `docs/benchmarks/2026-10-10-hardening-checks.md` line 44 records the earlier split start as READY in 88 s, 3.9 tok/s.

## 3. `scripts/privacy-check.py`, owner Phone A, peer Phone B (`03-privacy/`)

Both A and B hold shared folders (`03-privacy/pdu-status-89-before.json`, `pdu-status-23-before.json`: `Documents/SwarmDemo` and `Pictures/SwarmDemo` on each). The header's default question ("electricity bill") returned no title containing "Electricity" on A (its nearest hit was the mobile-bill photo) and no hit at all on B, so there is no electricity bill in either corpus. Phone A has `IMG_1046_Mobile_Bill_Sept.jpg` in its shared Pictures folder (the folder the script switches), so A was the owner and the script was run with `QUESTION="What is my mobile phone bill for September?" EXPECT="Mobile_Bill"` (a deviation the header allows). The hand-typed probe queries that led to this choice are not saved in a raw file.

| Run | Check | Result | Measured |
|---|---|---|---|
| 1 (00:46:44, `03-privacy/stdout.txt`) | baseline | PASS | peer query returns `IMG_1046_Mobile_Bill_Sept.jpg`, withheld 0 |
| 1 | private-after-kill | FAIL | "owner back after None s; folder private: None; a peer's query now returns [], withheld None" |
| 1 | shared-again | FAIL | "folder shared again: False ... returns [], withheld None" |
| 2 (00:50:13, `03-privacy/run2/stdout.txt`) | baseline | PASS | same as run 1 |
| 2 | private-after-kill | PASS | owner back after 2 s; folder private True; peer query returns [], withheld 1 |
| 2 | shared-again | PASS | folder shared again; peer query returns the bill photo, withheld 0 |

Run 1 failed because Phone A's app did not serve after the script's `force-stop` followed at once by `am start`: the process (pid 5807, started 00:46:45) was alive but `dumpsys` showed MainActivity with visible=false, no service running, the launcher in front, and port 8787 refused for more than 90 s. A second plain `am start -n com.swarmos.app/.MainActivity` ("Activity not started, its current task has been brought to the front") and the API answered at once (`03-privacy/manual-steps.txt`). Run 1 also left `Pictures/SwarmDemo` private on Phone A; I put it back with the script's own call (`POST /v1/pdu/debug/share {"folder":"Pictures/SwarmDemo","shared":true}`), then ran the script again. Run 2 passed, and the relaunch problem did not repeat in that one further attempt, so it is intermittent, cause not found. The privacy property itself (private plus kill must withhold) was observed to hold in run 2 only; run 1 could not judge it.
Counts: run 1 1 PASS 2 FAIL; run 2 3 PASS 0 FAIL.

## 4. `scripts/agent-check.py` on Phone A (`04-agent/`)

Agent access turned on with `am start ... --ez agentAccess true --es agentKey <random 32 characters>` (key kept in a shell variable; a search of every raw file for it found nothing). Command: `python scripts/agent-check.py 192.168.242.89:5555 192.168.242.89 <key> <dir> abcdfghij` (step e left out, see Not run).

| Check | Result | Measured |
|---|---|---|
| a-auth | PASS | no key 401, wrong key 401, right key 200; models ustaad-local, ustaad-swarm |
| b-lan | PASS | PC to phone IP:8790 ConnectionRefusedError |
| c-other-uid | PASS | curl on the phone as uid 2000: HTTP200, reply "ready" |
| d-chat | PASS | HTTP 200, 6.89 s, 87 completion tokens |
| d-stream | PASS | first token 0.41 s, 13.8 tok/s after the first token |
| d-schema | PASS | HTTP 200, 2.79 s, parsed city Tokyo, 13.96 |
| d-embed | PASS | HTTP 200, 0.11 s, 2 vectors of 256 dimensions |
| f-two-at-once | PASS | alone 7.15 s; together 6.92 s and 13.53 s (1.89 times a single call) |
| g-refusals | PASS | tools 400, unknown model 404, n=2 400, image 400, big 413, many 400 |
| h-openai-sdk | PASS | chat 7.44 s; stream first token 0.76 s, 52 chunks; embedding 256 dims |
| i-background-5s / 60s / 180s | PASS x3 | 13.3, 13.2, 13.3 tok/s after first token with the app in the background (in front: 13.2) |
| j-switch | PASS | switch off: connection closed (the script counts this as off); on again HTTP 200 |

Counts: 14 PASS, 0 FAIL; step e not run. Afterwards agent access was turned off with `am start -n com.swarmos.app/.MainActivity --ez agentAccess false`; an unauthenticated GET through `adb forward` to port 8790 of A and of B gave HTTP 000 on both (`99-final/agent-off-check.txt`); the forward was removed.

## 5. `scripts/batch-failover-check.py` (t, p): NOT RUN

Reason, first: the batch is started in `mode swarm` on Phone B, and the scheduler uses every phone that is online (stated in `docs/benchmarks/2026-10-10-e2e2-swarm-throughput.md` and in the `step_t2` comment of `scripts/e2e2-check.sh`). Phone C was online when I began: B's `GET /v1/link/peers` at about 00:35 listed it online (thermal 0, battery 84 %), B's `/v1/sched/phones` listed it up with a READY model, and Phone A's log has `nsd resolved swarmos-82d2676d-0 -> 192.168.242.76:8787` at 00:46:05 (all seen live, none saved to a raw file). About a third of the 32 jobs would then have run on Phone C, which the founder left out. The repo's own way to get a two-phone swarm (`step_t2`) force-stops the third phone's app over adb, which the brief forbids. So I did not start the batch.
Later Phone C vanished from both phones' peer lists: `e2e2/peers-1.json` (B's view, 00:56) lists only Phone A, and `99-final/sched-phones-B.json` (01:07) lists only B and A; live reads of both `/v1/link/peers` at 01:08 showed the same. I then tried to start the check as a true two-phone run (`tp`); the permission layer of my session refused that action ("Interfere With Workloads") before anything ran or any file was created, and I did not retry or work around it. Batch-failover, and the swarm parts of e2e2 below, therefore remain unrun and need a decision from the orchestrator or the founder.
Substitute that touches no third phone: the same `bench32` batch in `local` mode on each phone alone with `scripts/bench-batch.py` (`05-local-batch/`, command `python scripts/bench-batch.py <ip> local <tag> <dir>`):

| Phone | Finished / failed | Wall | Tokens | Aggregate | Decode | Thermal before to after |
|---|---|---|---|---|---|---|
| B | 32 / 0 | 98.9 s | 889 | 8.99 tok/s | 10.9 tok/s | 2 to 2 |
| A | 32 / 0 | 95.5 s | 889 | 9.31 tok/s | 11.2 tok/s | 2 to 2 |

Earlier the same batch in local mode gave B 88.8 s / 10.09 tok/s and A 87.1 s / 10.29 tok/s from thermal 0 (`docs/benchmarks/2026-10-10-e2e2-swarm-throughput.md`, rows single-Phone-B and single-Phone-A). Today is about 10 percent slower; today's runs started at thermal 2 with Keep busy on, the earlier ones at thermal 0, so this is not a like-for-like comparison. Peer-killed, peer-joining and coordinator-killed behaviour was not exercised.

## 6. `scripts/e2e2-check.sh --steps s ...` (`06-e2e2/`, results in `e2e2/`)

Command run: `scripts/e2e2-check.sh --steps s --results docs/benchmarks/raw/final-audit/e2e2 192.168.242.23 192.168.242.89` (B asks, A peer). The brief asked for `s,k,t`.

| Check | Result | Measured |
|---|---|---|
| s1 hello B / A | PASS x2 | HTTP 200 in 140 ms / 114 ms |
| s2 signed health B / A | PASS x2 | 113 ms / 99 ms, answer MAC ok on both |
| s3 unsigned B / A | PASS x2 | 401 on both |
| s4 peers | PASS x2 | B lists A online after 648 ms; A lists B after 720 ms |
| s5 models, s6 backend B / A | PASS x4 | chat Qwen3.5-4B-Q4_K_M, GPU, embedder READY on both |

Counts: 12 PASS, 0 FAIL, 0 WARN (`e2e2/checks.log`: `SUMMARY pass=12 fail=0 warn=0`).
Step `k` (Knowledge Bank) not run. Reason at the time of the decision (00:57, Phone C still listed online): it saves a shared card on B that replicates by itself to every online paired phone (a card and later a revocation record would have been written into Phone C's app data), and its `POST /v1/pdu/ask` calls fan out to all online phones; neither can be restricted to A and B. Step `t` not run, same reason as section 5 (its `swarm2-1` and `swarm2-2` batches use every online phone); its single-phone local part is covered by the substitute in section 5, not by this script. After Phone C left the peer lists (01:08) I did not start k or t, because the swarm run in section 5 had just been refused and these are the same kind of action. Steps `d` and `p` were not run, as briefed.

## Not run, and why

| Item | Why |
|---|---|
| hardening step m | 30B file absent from Phone A (section 1) |
| agent-check step e (`ustaad-swarm` chat) | routes to any online phone, so it could have run on Phone C |
| batch-failover t and p | swarm batch would have used Phone C while it was online; the later attempt at a two-phone run was refused by the session's permission layer (section 5) |
| e2e2 steps k and t | card replication and swarm batch would have reached Phone C; not started afterwards (section 6) |
| e2e2 steps d, p | push and remove files on a phone; excluded by the brief |

Phone C has been absent from both peer lists since before 00:56 (section 5). If that holds and the orchestrator or founder clears it, batch-failover `tp` and e2e2 `k,t` (and agent-check `e`) can be run as briefed on A and B. batch-failover turns Keep busy off on both phones (`--ez keepBusy false`), so it must be switched back on afterwards.

## Deviations from the brief

1. privacy-check run with QUESTION and EXPECT set (allowed by its header), and run twice after run 1 failed (second `am start` and folder restore in between, both the script's own steps).
2. agent-check step e skipped; e2e2 reduced to step s; batch-failover not run; local-mode `bench-batch.py` runs added as a substitute (existing script, no code written). `06-e2e2/` holds the stdout of the step-s run, `e2e2/` the results it wrote.
3. hardening steps g and c added (time remained).
4. I did not wait for Thermal Status 0 or 1 (the e2e2 guard wants it); the brief's "2 or lower" was used and the phones sat at 2 most of the time.
5. A small helper in my scratch directory (not in the repo) waited for chat and embedder READY, rpc STOPPED and thermal 2 or lower between scripts and wrote `gate-log.txt`. The agent-check run began without that gate (A's app had been force-stopped by privacy run 2 about 30 s earlier; every check passed).

## Anomalies

- Thermal status: 2 on both phones at the start and at the end; 0 on A after the fault-injection steps; 1 on B once (`gate-log.txt`). Keep busy (half duty) was running on both throughout, which is why they did not cool to 0 or 1.
- Kills (script-made, all expected): chat server on A killed with signal 9 in step t and twice in step c; frozen with SIGSTOP in step h and killed by the app after its health timeout; B's app force-stopped in memcloud step k; A's app force-stopped in privacy runs 1 and 2.
- Privacy run 1: A's app did not come back for more than 90 s after force-stop plus `am start` (section 3).
- No adb disconnects or reconnects seen; `adb devices` listed A, B and C throughout.
- After step c, Keep busy on A showed `paused_model_restarted` from 01:04 until 01:09, then `running` (`99-final/busy-A.json`). The 300 s settle time after a server death is read from `app/app/src/main/java/com/swarmos/app/busy/BusyPolicy.kt` (`SETTLE_AFTER_DEATH_MS`), not measured.
- Phone C disappeared from both phones' peer lists between 00:46 and 00:56 without any command of mine (section 5).

## Files created on a phone

None outside the app's own private data. Scripts did not write to `/sdcard` or `/data/local/tmp` (a search for files newer than 00:36 in `/sdcard/Android/data/com.swarmos.app`, `Download`, `Documents`, `Pictures` and `/data/local/tmp` found none; `99-final/new-files-A.txt`, `new-files-B.txt`; read at 01:00, before the g and c steps). Inside the app's files directory the app itself wrote `audit.jsonl`, `logs/chat.log`, `logs/embed.log`, `logs/rpc.log` (A), `pdu.db-wal/-shm`, and one batch report each (`batches/batch-72ed678929934151.json` on A, `batches/batch-7f6c9c282ea447bf.json` on B) from the local runs. PC side: an `adb forward` for the agent check, removed afterwards (`adb forward --list` empty).

## Final state of both phones

Read at 01:04 (`99-final/state-A.txt`, `state-B.txt`, `memcloud-A.txt`, `memcloud-B.txt`, `agent-off-check.txt`): chat Qwen3.5-4B-Q4_K_M READY on GPU on both (useGpu true), embedder READY, rpc STOPPED, MemoryCloud IDLE with no notices and nothing pending, agent access off (port 8790 refused on both), thermal 2 on both. Shared folders as before: A `Documents/SwarmDemo` and `Pictures/SwarmDemo` shared; B unchanged, its `Documents/SwarmDemo/Private` still private. Read again at 01:09 (`99-final/busy-A.json`, `busy-B.json`): Keep busy enabled on both, `hammer` false, A `running`, B `half_duty`; A thermal 0, B thermal 2; chat and embedder READY, rpc STOPPED on both. Nothing is left changed.


## Second pass on the fixed build, Phone A only

Date and time: 2026-10-11, 01:53 to 02:26 IST. Phone A (`192.168.242.89`) only; Phones B and C received no adb command and no API call. The pairing secret was read from Phone A and never printed or written; a search of every raw file under `pass2/` for it, and for the agent key, found nothing. Raw files are under `docs/benchmarks/raw/final-audit/pass2/` (called `pass2/` below); the small helper scripts I used for item 4 and the h diagnostic (they are not repo scripts) are copied to `pass2/helpers/`.

### Build and start state (`pass2/01-start/`)

New build: versionName 0.1.0, lastUpdateTime 2026-10-11 01:50:35, base.apk sha256 prefix f4b00f44246c3de0 (first pass: 2573d917d9a098d7) (`build-A.txt`, `apk-sha-A.txt`). Models folder unchanged (`models-dir-A.txt`: Qwen3.5-4B-Q4_K_M.gguf and embeddinggemma-300M-Q8_0.gguf). At 01:53: chat READY on GPU, embedder READY, rpc STOPPED, thermal 1, Keep busy enabled and `paused_model_restarted` (app just installed, jobsDone 0, hammer false), MemoryCloud IDLE, agent access off (unauthenticated GET to port 8790: HTTP 000, `agent-off-check.txt`), shared folders `Documents/SwarmDemo` and `Pictures/SwarmDemo`, Ustaad in front, screen awake (`state-A.txt`, `busy-A.json`, `memcloud-A.txt`, `pdu-A.txt`, `focus-A.txt`).

### 2. `scripts/hardening-check.py` on Phone A (`pass2/02-hardening/`)

Commands: `... th` (`th/`), then `... gc` (`gc/`); h repeated once (`h-rerun/`) and once as a longer diagnostic (`h-extended/`, not a repo script).

| Check | Result | Measured |
|---|---|---|
| t-truncated-stream | PASS | pid 22429 killed 6 s into a long answer: HTTP 500 "the model stopped mid-answer" |
| t-restart | PASS | chat READY on GPU, restarts 0 (as printed) |
| h-hung-server | **FAIL** | `FAIL  h-hung-server  froze pid 22683: first change after None; then READY on GPU with pid 22683, restarts 1` (`th/stdout.txt`, `th/h-hung.json`) |
| h-hung-server, run again | **FAIL** | `FAIL  h-hung-server  froze pid 27567: first change after None; then READY on GPU with pid 27567, restarts 0` (`h-rerun/stdout.txt`) |
| g-gpu-start | PASS | three reloads READY on GPU, loadMs 18399, 17816, 18141; 5 cache files |
| c-cpu-fallback | PASS | two kills, chat READY on CPU, notice "The GPU server failed ... Running on the CPU build." |
| c-gpu-restored | PASS | READY on GPU, loadMs 17819 |

Counts: 5 PASS, 1 FAIL (h, reproduced in a second run). First pass on the old build: h PASS, first change after 98.9 s (section 1 above, `01-hardening/stdout.txt`).

What the h failure is: the script freezes the chat server (SIGSTOP) and gives the app 120 s to restart it. The app did not within 120 s in either run. The app's log shows why: the health watch kept missing its ping, but most misses are "not counted now: a call is in flight within its time" (a Keep busy filler call in flight against the frozen server), and in the longer diagnostic below only 3 of the 16 missed pings counted (`th/applog-0156-0159.txt`, `h-rerun/applog-health.txt`, `h-extended/applog-health.txt`). At the 120 s mark 1 of 3 (second run) or 2 of 3 (first run) had counted, then the script released the server with SIGCONT. Diagnostic with a longer window (`h-extended/hung-extended.json`, `stdout.txt`, `applog-health.txt`): chat server frozen at 02:18:56; counted misses at 02:19:59, 02:21:04 and 02:22:22; at 02:22:22 "health watch: 3 misses in a row, killing the server"; first change seen by the PC 207.7 s after the freeze (state STARTING, restarts 1, notice "stopped answering /health for 30 s, killed by Ustaad"). During the freeze Keep busy's `jobsFailed` on A went from 1 to 4. So the watchdog still works but takes about 208 s instead of 99 s while Keep busy is running; the script's 120 s window is now too short. Whether 208 s is acceptable is a judgement for the orchestrator; the run is a FAIL against the script as written. I did not test with Keep busy switched off.

### 3. `scripts/agent-check.py` on Phone A (`pass2/03-agent/`)

Command as before: `python scripts/agent-check.py 192.168.242.89:5555 192.168.242.89 <key> <dir> abcdfghij`; step e (ustaad-swarm, routes to any online phone) not run. 14 PASS, 0 FAIL: a-auth 401/401/200; b-lan connection refused; c-other-uid HTTP200; d-chat 7.32 s, 87 tokens; d-stream first token 0.46 s, 13.4 tok/s; d-schema 3.02 s; d-embed 0.20 s, 256 dims; f-two-at-once alone 7.30 s, together 6.94 s and 13.56 s (1.86 times); g-refusals 400/404/400/400/413/400; h-openai-sdk chat 7.31 s, 52 chunks; i-background 5 s, 60 s, 180 s all 13.4 tok/s (in front 13.3); j-switch off then on again HTTP 200. Agent access off again afterwards: `pass2/03-agent/agent-off-check.txt` and `pass2/99-final/agent-off-check.txt` both HTTP 000.

### 4. Restart fault, five rounds (`pass2/04-restart/`, helper `pass2/helpers/restart_rounds.py`)

Each round: wait for chat and embedder READY, `am force-stop com.swarmos.app` (0.15 to 0.21 s), then one `am start -n com.swarmos.app/.MainActivity` (0.06 to 0.08 s); times counted from that `am start`; Ustaad was in front before every round (`focusBefore` in `round<n>.json`).

| Round | Started | (a) signed GET /v1/sched/busy answers | (b) chat READY | Fault (no answer in 30 s) |
|---|---|---|---|---|
| 1 | 02:08:02 | 1.1 s | 15.3 s | no |
| 2 | 02:08:18 | 1.3 s | 16.6 s | no |
| 3 | 02:08:35 | 1.3 s | 17.1 s | no |
| 4 | 02:08:53 | 1.3 s | 17.1 s | no |
| 5 | 02:09:11 | 2.1 s | 17.2 s | no |

The fault from the first pass did not appear, so no dumps were taken. After the first-pass fault the launcher was in front (screenshot at 00:49), and I do not know what was in front just before it, so I added a variant that was not asked for, to test whether a backgrounded app matters: three more rounds with the HOME key pressed and the launcher confirmed in front (`focusAfterHome` in `home-variant/round<n>.json`) before the force-stop. Results (`home-variant/stdout.txt`): round 1 API 1.2 s, chat READY 17.0 s; round 2 1.1 s, 15.9 s; round 3 1.2 s, 16.4 s; no fault. Eight attempts, no fault. I cannot say whether the new build fixed it or whether the first-pass trigger was something else (it happened once in two runs, 00:46 against 00:50). The first-pass observation stands in section 3 above (`03-privacy/manual-steps.txt`).

### 5. Keep busy after a restart (`pass2/05-keepbusy/samples.txt`)

After round 5 (chat READY about 02:09:28) Keep busy stayed `paused_model_restarted` until 02:11:32, then `running` with jobsDone 2. Over the next 62 s (02:11:32 to 02:12:34) jobsDone grew from 2 to 31, tokens 42 to 606, jobsFailed 0, jobsCancelled 0. PASS.

### 6. Local batch (`pass2/06-local-batch/`)

`python scripts/bench-batch.py 192.168.242.89 local A-local-pass2 <dir>`: 32 / 32 finished, 0 failed, wall 94.4 s, 889 tokens, aggregate 9.41 tok/s, decode 11.4 tok/s; thermal status 2 at the start and 2 at the end. First pass on the old build: 95.5 s, 9.31 tok/s, thermal 2. PASS (no threshold set in the brief).

### Not run

Agent-check step e (may route to Phone C); hardening step m (30B file not on the phone); anything involving B or C. Keep busy was not switched off in the h diagnostic.

### Anomalies and deviations

Thermal status 0 to 2 during the pass (`pass2/gate-log.txt`); no status above 2 seen. Kills are all script-made: chat server SIGKILL in t and twice in c, SIGSTOP in the h runs (killed by the app after its watchdog in the diagnostic), app force-stops in the 8 restart rounds. No adb reconnect. Deviations: h repeated twice (rerun and a 420 s diagnostic), 3 extra restart rounds with the app in the background. Keep busy's `jobsFailed` counter on A was 4 at the end of the h diagnostic and reads 0 at 02:25:49 (`99-final/busy-A.json`) because the later restart rounds reset it.

### Files created on a phone

None outside the app's own data (`pass2/99-final/new-files-A.txt`: files newer than 01:52 are the app's own `audit.jsonl`, `logs/chat.log`, `logs/embed.log`, `pdu.db-shm`, datastore and one batch report `batches/batch-58b75fe32a2147c3.json`). Nothing on `/sdcard` or `/data/local/tmp`.

### Final state of Phone A (`pass2/99-final/`, read 02:25:49)

Chat Qwen3.5-4B-Q4_K_M READY on GPU (useGpu true), embedder READY, rpc STOPPED, Keep busy enabled and `running`, hammer false, jobsFailed 0, MemoryCloud IDLE with no notices, agent access off (port 8790: HTTP 000), thermal 0, Ustaad in front (`mCurrentFocus=com.swarmos.app/.MainActivity`), screen awake, shared folders unchanged.


## Third pass on the fixed build, phones A and B

Date and time: 2026-10-11, 02:52 to 03:16 IST. Phones A (`192.168.242.89`) and B (`192.168.242.23`) only. Phone C received no adb command and no API call; it was in neither phone's peer list at the start (`pass4/01-start/peers-A.txt`, `peers-B.txt`), in B's `/v1/sched/phones` before the swarm batch (`pass4/06-swarm-batch/sched-phones-B-before.json`), nor in B's peer list after it (`peers-B-after.json`). The pairing secret was read from Phone A and never printed or written; a search of every raw file under `pass4/` for it found nothing. Raw files: `docs/benchmarks/raw/final-audit/pass4/` (called `pass4/` below; the folder name differs from the "third pass" title). My helper scripts (not repo scripts) are in `pass4/helpers/`. B's read-only polling script from the coordinator was ignored.

### 1. Build and start state (`pass4/01-start/`)

| Phone | lastUpdateTime | base.apk sha256 (first 16) | Files |
|---|---|---|---|
| A | 2026-10-11 02:34:10 | d482365f80db92bc | `build-A.txt`, `apk-sha-A.txt` |
| B | 2026-10-11 02:51:20 | d482365f80db92bc | `build-B.txt`, `apk-sha-B.txt` |

Same apk on both. Models folders identical (Qwen3.5-4B-Q4_K_M.gguf, embeddinggemma-300M-Q8_0.gguf). At 02:52: both phones chat READY on GPU, embedder READY, rpc STOPPED, MemoryCloud IDLE, hammer false, Keep busy enabled (A `half_duty`, jobsDone 191, jobsFailed 1; B `paused_model_restarted`, jobsDone 0), Ustaad in front and screen awake on both, thermal A 2, B 0 (`state-*.txt`, `busy-*.json`, `memcloud-*.txt`, `focus-*.txt`). One thing already there: Phone A's chat showed restarts 1, notice "Restarted automatically after the server was killed" and a `lastError` ending "stopped answering /health for 30 s, killed by Ustaad ... after 228 s" (left from a freeze before this pass; `state-A.txt`). Agent access was not read at the start; it was read at the end (HTTP 000 on both).

### 2. `scripts/memcloud-check.py`, both directions (`pass4/02-memcloud-B-drives/`, `pass4/02-memcloud-A-drives/`)

Commands: `python scripts/memcloud-check.py 192.168.242.23 192.168.242.89 "Phone A" <dir> vnsk` and `python scripts/memcloud-check.py 192.168.242.89 192.168.242.23 "Phone B" <dir> vnsk`. Each direction: 9 PASS, 0 FAIL (`stdout.txt` in each folder).

| Number | B drives, A lends | A drives, B lends |
|---|---|---|
| Split READY after start | 34 s (loadMs 26668) | 32 s (loadMs 23605) |
| Answer | 68 tokens, first token 4791 ms, 6.09 tok/s | 74 tokens, first token 5141 ms, 5.81 tok/s |
| v-bad-layers / n-lender-memory | PASS (HTTP 400, "about 4795 MB available") | PASS (HTTP 400, "about 5629 MB available") |
| Stale stop / cache clear while lending | HTTP 409 / HTTP 409, PASS | HTTP 409 / HTTP 409, PASS |
| Driver models READY after stop | 17 s | 17 s |
| Lender chat READY again after stop | 3 s | 0 s |
| Lender chat READY again after the driver app was killed (k) | 27 s, lendingTo None | 26 s, lendingTo None |
| Driver READY after relaunch | 15 s | 17 s |

For comparison only, the first pass on the older build (B drives, A lends) gave split READY 48 s, 74 tokens at 4.12 tok/s, lender restore 0 s, k 26 s (section 2 above).
After each direction `get /v1/models/state` on both phones (`pass4/02-memcloud-*/models-state-A.json`, `models-state-B.json`): chat READY on GPU, embedder READY, rpc STOPPED, restarts 0, lastError null, notice null, MemoryCloud IDLE with no notices and nothing pending, on both phones both times.

### 3. A degraded split left alone (`pass4/03-degraded/`)

Done by a helper script (`pass4/helpers/degraded.py`) that logs every call with its time: `calls.log` (every call), `stdout.txt`, `timeline.json`, `memcloud-state-*.json` (full state at each phase change). Start body as in memcloud-check step s: `POST /v1/memcloud/start {"file": "Qwen3.5-4B-Q4_K_M.gguf", "peers": ["Phone A"]}` on B. Polls of `/v1/memcloud/state`, `/v1/models/state` and `/v1/sched/busy` on B every 5 s from the force-stop on (the brief asked for the last two only from `released`; they are read-only).

| Event | Clock | After force-stop | Detail |
|---|---|---|---|
| Split start posted on B | 02:58:14 | | HTTP 202 |
| Split READY | 02:58:47 | | 33.0 s after start, loadMs 24520, A lendingTo Phone B |
| `am force-stop com.swarmos.app` on A | 02:58:51 | 0 | not restarted |
| Phase DEGRADED | 02:58:56 | 5.3 s | lostPhone "Phone A", failReason "heartbeat lost"; B chat STOPPED, Keep busy `no_model` |
| `released` true | 03:00:58 | 127.8 s | 122.5 s after DEGRADED; phase still DEGRADED; notice "Phone B's own chat and embedding models were restored automatically after 2 minutes in DEGRADED with no re-plan"; B chat STARTING on GPU |
| B chat READY on GPU | 03:01:14 (first poll) | 143 s | about 16 s after released; Keep busy `paused_model_restarted`, jobsDone 0 |
| Keep busy `running` | 03:03:11 | 260 s | 117 s after chat READY; phase DEGRADED |
| First filler job counted | 03:03:16 | 266 s | 138 s after released; jobsDone 2; phase DEGRADED |
| 60 s after the first filler job | 03:04:18 | 327 s | jobsDone 2 to 33, jobsFailed 0, phase DEGRADED |
| `POST /v1/memcloud/stop {}` on B | 03:04:18 | | HTTP 202; B phase IDLE at 03:04:20 |
| `am start` on A | 03:04:18 | | API answered 2.0 s later |

During the split B's `/v1/models/state` reads chat READY on CPU (the small fallback model, `smallModel` 14.08 tok/s in the state): that is not the chat READY of the table, which is the first READY on GPU after released.

Verdict on the fix under test: it holds. After `released` B's own chat came back on its own (about 16 s), Keep busy left `no_model` and `paused_model_restarted` after its 120 s settle, and filler jobs were counted again (2 to 33 in 60 s, none failed) while MemoryCloud still read DEGRADED. DEGRADED was not cleared by the release: the phase stayed DEGRADED until the stop was posted.

A did not recover on its own after `am start`, and this is a separate finding. `am start` at 03:04:18 was answered (API up in 2.0 s, chat and embedder STARTING), but from about 03:04:55 every call to A timed out until the helper gave up at 03:08:03 (`stdout.txt`, lines from 03:04:55; `timeline.json` `amStartToBothReadySeconds` null), and A's API still did not answer when I looked at 03:08:35. When I looked at 03:10:42 Phone A was `mWakefulness=Asleep` with the keyguard showing, `screen_off_timeout` 300000 ms, and the Ustaad process and its servers alive (`A-asleep-dumps.txt`). Ustaad holds KEEP_SCREEN_ON while it is in front (checked on both phones at 03:12), but it had been force-stopped for 5.5 minutes and the screen timed out; the later `am start` found a sleeping screen. The app's GPU load on A did not finish in 120 s and A fell back to CPU: "The GPU server failed (not ready after 120 s ...). Running on the CPU build." I did not capture the moment the screen went off. I then did the following (`manual-steps.txt`): `input keyevent KEYCODE_WAKEUP` and `wm dismiss-keyguard` at 03:10:5x, the plain `am start`; A chat READY on CPU at 03:11:15; then the restore calls of hardening-check step c (`POST /v1/models/gpu` false, then true, `POST /v1/models/stop` chat, `POST /v1/models/load` chat): chat READY on GPU, loadMs 13629, at 03:11:41. So the pair was back in the expected state at 03:12:07 (`gate-log.txt`), about 8 minutes after the `am start`, only after a manual wake. In the earlier passes every force-stop was followed by a restart within seconds, so this is the first time a force-stopped Ustaad was left for longer than the screen timeout (my inference for why the screen was off; not measured).

Final MemoryCloud state of both (`final-memcloud-A.json`, `final-memcloud-B.json`, read 03:12): IDLE, no notices, pendingRestore empty, released false, on both; B's step log ends "Phone A has its models back". Chat READY on GPU, embedder READY, rpc STOPPED on both (`final-models-*.json`).

### 4. `scripts/privacy-check.py`, owner A, peer B (`pass4/04-privacy/`)

`QUESTION="What is my mobile phone bill for September?" EXPECT="Mobile_Bill" python scripts/privacy-check.py 192.168.242.89 192.168.242.23 <dir>`. 3 PASS, 0 FAIL: baseline (peer sees `IMG_1046_Mobile_Bill_Sept.jpg`); private-after-kill (owner back after 2 s, folder private, peer's query returns [], withheld 1); shared-again (bill photo returned, withheld 0). The restart fault of the first pass did not appear. Folder `Pictures/SwarmDemo` shared again afterwards (`pdu-status-A-before.json` for the starting state).

### 5. `scripts/e2e2-check.sh --steps s` (`pass4/05-e2e2/`)

`scripts/e2e2-check.sh --steps s --results docs/benchmarks/raw/final-audit/pass4/05-e2e2/results 192.168.242.23 192.168.242.89`. 12 PASS, 0 FAIL, 0 WARN (`results/checks.log`: `SUMMARY pass=12 fail=0 warn=0`): hello 100 ms (B) and 150 ms (A), signed health 140 and 124 ms with answer MAC ok, unsigned 401 on both, B lists A online after 715 ms and A lists B after 709 ms, chat Qwen3.5-4B-Q4_K_M on GPU on both, embedder READY.

### 6. Two-phone swarm throughput (`pass4/06-swarm-batch/`)

Before it: B's `/v1/sched/phones` listed only B (local, up) and A (up); no Phone C (`sched-phones-B-before.json`). Thermal at the start: A 0, B 2 (`thermal-before.txt`). `python scripts/bench-batch.py 192.168.242.23 swarm B-swarm <dir>`: 32 / 32 finished, 0 failed, wall 36.3 s, 889 tokens, aggregate 24.52 tok/s; per phone B 15 jobs (decode 13.4 tok/s), A 17 jobs (decode 16.7 tok/s); mode swarm, real model, no fake (`batch-B-swarm.json`, `stdout.txt`). Thermal at the end: A 0, B 2 (`thermal-after.txt`). Phone C was still absent from B's peer list afterwards. PASS. For reference only, `docs/benchmarks/2026-10-10-e2e2-swarm-throughput.md` row swarm2-1 has 47.6 s and 18.83 tok/s for two phones from thermal 0 on the older build; not a like-for-like comparison.

### Not run

`scripts/batch-failover-check.py`, as instructed. Agent access was not read at the start of the pass (only at the end).

### Anomalies and deviations

- Item 3: A's screen went to sleep while Ustaad was force-stopped; Ustaad on A became unreachable after `am start` and its GPU load fell back to CPU; manual wake, `am start` and the hardening-c restore calls were needed (above). This was the only manual intervention.
- Thermal status 0 to 2 in `gate-log.txt`; nothing above 2.
- Script- or test-made kills: driver app force-stops in memcloud step k (B, then A), force-stop of A in item 3, force-stop of A in the privacy check. No adb reconnect (`adb devices` listed A, B, C, and B's USB serial throughout).
- The helper for item 3 polled `/v1/models/state` and `/v1/sched/busy` on B from the force-stop on, not only from `released`.

### Files created on a phone

None outside the app's own data (`pass4/99-final/new-files-A.txt`, `new-files-B.txt`: `audit.jsonl`, logs, `pdu.db-*`, and one batch report `batches/batch-6c886169ac394550.json` on B). Nothing on `/sdcard` or `/data/local/tmp`.

### Final state of both phones (`pass4/99-final/`, read 03:15)

Phone A: chat READY on GPU (restarts 0, no lastError), embedder READY, rpc STOPPED, Keep busy enabled and `running` (jobsFailed 0), hammer false, MemoryCloud IDLE, agent access off (HTTP 000 on port 8790), shared folders `Documents/SwarmDemo` and `Pictures/SwarmDemo`, thermal 0, screen awake, Ustaad in front.
Phone B: chat READY on GPU (restarts 0, no lastError), embedder READY, rpc STOPPED, Keep busy enabled and `half_duty` (jobsFailed 0), hammer false, MemoryCloud IDLE, agent access off (HTTP 000), thermal 2, screen awake, Ustaad in front. B lists only Phone A as a peer.
