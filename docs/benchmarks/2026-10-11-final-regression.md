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
