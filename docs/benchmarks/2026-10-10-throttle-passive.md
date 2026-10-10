# Keep busy watched from outside, phones B and C (2026-10-10, 21:21 to 22:34)

Read-only watch of both phones for 40 minutes while the founder was using them: `scripts/throttle-watch.py`, one sample every 10 s (237 per phone) of `dumpsys thermalservice` and the signed `GET /v1/sched/busy`. No job was sent, nothing was started or changed on a phone. Build on both phones: 1ac22c5, both charging, app in front, keep busy on. Raw: `raw/gate-9b/throttle-passive/B.jsonl` and `C.jsonl`. One window, one run.

The question was which readable signal marks the step between the fast level (about 13.5 tok/s) and the slow level (about 10.8) of real work inside Thermal Status 2 (evening-checks section 9). The first window did not answer it and found a fault instead (sections 1 to 3). A second window half an hour later did answer it (section 5): the level follows the `gpu` cooling step in the thermal report.

## 1. Phone C ran no filler for 36 minutes (fault, fixed, fix checked on the phone)

| | Phone B | Phone C |
|---|---|---|
| Filler jobs in the window | 406, 0 failed | 119 until 21:35:18, then none; 0 failed |
| Time generating | 1090 s of 2392 s (45.6 %) | 324 s of the first 814 s (39.8 %, the founder's own requests ran in between), then 0 s of 1580 s |
| Keep-busy state after 21:35:18 | half duty | "waiting for the phone to be idle" in every sample to the end of the window, and still at 22:06 |
| Thermal Status at the end | 2 (skin 42.0 C) | 0 (skin 34.7 C) |

Cause, read from the phone at 22:03 (`GET /v1/memcloud/state`): at 21:35:20 a MemoryCloud start for the 30B model was refused on Phone C ("The plan does not fit: Phone C has 5.6 GB free and the planning estimate is 10.3 GB; Phone B has 7.2 GB free and the planning estimate is 9.9 GB. Nothing was started or stopped."). The refusal is correct. But it leaves MemoryCloud in the phase FAILED until the owner starts again, and the keep-busy loop counted every phase except IDLE as real work in progress. So the phone waited for an idle moment that never came, cool and with its model loaded.

Fix: commit 1b9d60b, FAILED no longer counts as work in progress (`MemCloudLogic.holdsPhone`, one unit test; 1431 tests, 0 failures). Phone C was released at 22:11:43, after 36 minutes without filler, with the app's own MemoryCloud stop call. The build with the fix was installed on Phone B at 22:51 and Phone C at 22:52 when the founder said so.

Checked on Phone C at 22:55 on that build: with keep busy running, the same start was sent (`POST /v1/memcloud/start` for the 30B file) and refused the same way (phase FAILED, "The plan does not fit ... Nothing was started or stopped"). The filler waited its five idle seconds and ran on: 22 jobs in the next 60 s with the phase still FAILED, chat and embedding servers READY with 0 restarts. MemoryCloud was stopped again afterwards (phase IDLE).

## 2. What the thermal report shows besides Thermal Status

The thermal HAL reports cooling devices. Three of them were never zero: `gpu`, `cpu-cluster0`, `cpu-cluster1`. They moved together, in three steps.

| Thermal Status | gpu / cpu-cluster0 / cpu-cluster1 | Phone | Samples | Skin temperature |
|---|---|---|---|---|
| 0 | 4 / 7 / 14 | C | 51 | 34.7 to 36.3 C |
| 0 | 5 / 10 / 15 | C | 58 | 36.4 to 39.8 C |
| 1 | 5 / 10 / 15 | C | 39 | 39.0 to 41.7 C |
| 1 | 7 / 13 / 17 | C | 6 | 40.1 to 40.9 C |
| 1 | 7 / 13 / 17 | B | 7 | 41.0 to 41.9 C |
| 2 | 7 / 13 / 17 | B | 230 | 41.0 to 43.5 C |
| 2 | 7 / 13 / 17 | C | 81 | 41.1 to 42.9 C |

The table leaves out two samples on Phone C at 21:28, described below. Where the steps were crossed (each seen once or twice, Phone C unless said):
- Thermal Status 2 to 1 on the way down at skin 41.0 C (B: 41.00, C: between 41.12 and 40.91); 1 to 2 on the way up at about 42.0 C (B: between 41.86 and 42.04).
- Thermal Status 1 to 0 on the way down at 39.0 C (twice); 0 to 1 on the way up between 39.8 and 40.3 C.
- Cooling step 7/13/17 to 5/10/15 on the way down at skin 40.0 C; 5/10/15 to 4/7/14 at 36.3 C.
- The step up from 5/10/15 to 7/13/17 was not seen. During three minutes of the founder's own requests on Phone C (21:42 to 21:45) the skin rose from 38.0 to 41.65 C at Thermal Status 1 and the step stayed at 5/10/15.
- At 21:28:22 Ustaad was in the background on Phone C for one sample. The cooling values read 5/10/15, ten seconds later 4/10/14, and 7/13/17 again ten seconds after that, with the skin at 42.3 to 42.5 C and Thermal Status 2 throughout. So these values do not follow skin temperature alone. One event.

After the filler stopped on Phone C the skin fell from 41.4 to 40.0 C in 91 s, to 36.3 C in 18 minutes and to 34.7 C in 26 minutes.

## 3. Speed against the cooling step

Filler speed here is tokens gained over seconds of generating gained between samples a minute apart. It includes prompt time, so it is lower than the decode speed of one request.

| Phone | Cooling step | One-minute windows | Filler speed, tok/s | Last real request, decode tok/s (`/v1/sched/phones`, read 22:03) |
|---|---|---|---|---|
| B | 7 / 13 / 17 | 37 | 6.69 to 9.74, median 7.72 | 10.94 |
| C | 7 / 13 / 17 | 13 | 7.11 to 10.34, median 7.91 | |
| C | 5 / 10 / 15 | 0 (no filler ran) | | 13.43 |

- At the step 7/13/17 both phones ran filler at about 7.8 tok/s for as long as they were watched, and the one real request known at that step decoded at 10.94 tok/s: the slow level. (Phone B's counters stood at zero at 21:21 with the model-restart pause showing, so its app had been restarted shortly before and that request is from after the restart.)
- The one real request known at the step 5/10/15 decoded at 13.43 tok/s: the fast level. The phone does not report when that request ran; the last GPU activity on Phone C was the founder's own use at 21:42 to 21:45, at this step. One reading on each side, on different phones, at different Thermal Status. It fits "the slow level is the step 7/13/17", and it is not proof: Thermal Status alone separates these two readings just as well.
- Not shown: the step 5/10/15 at Thermal Status 2, which is where the two levels have to be told apart. That needs a phone warming up from cool with the filler running. Phone C is cool and will do that once its keep busy runs again.

The second window (section 5) has two more fields per sample: the decode speed of the last real request (`realTps`) and the MemoryCloud phase (`memPhase`).

## 4. What follows for the duty rule

Nothing is changed on the phones. The present rule (half duty at Thermal Status 2) cannot see the step. On Phone C it happened to recover from step 7 by itself in five and a half minutes; on Phone B it has not recovered in 73 minutes, so every real request on Phone B runs at 10.9 tok/s where 13.4 is available, and its filler makes about a fifth fewer tokens for the same share of time.

A rule that reads the step is written and not merged (branch `worktree-agent-a718f09290a81f34a`, commits f3cd864 and e2452fe, 1462 unit tests, 0 failures; the second commit, written after section 6, makes the filler also run lightly from 39.6 C shell temperature, which is what should keep a phone out of the slow step in the first place): at `gpu` step 7 or more the filler runs lightly, resting four times the length of each job, until the step is left; `GET /v1/sched/busy` reports the step as `gpuStep` and the shell temperature as `shellC`; the three numbers of the rule can be set on a debug build without a rebuild. It goes to master only after a before and after on a phone: real decode, filler tokens and share of time generating over 30 minutes each. If the light phase does not end, or costs more share than it wins in speed, it is dropped.

## 5. Second window (22:06 to 22:34): the cooling step is the signal

Same watch, 167 samples per phone, with the decode speed of the last real request (`realTps` from `/v1/sched/caps`) in every sample. Raw: `B-2.jsonl`, `C-2.jsonl`. Two things made it useful. At 22:11:43 the stuck state on Phone C was cleared with the app's own MemoryCloud stop call (no restart; the chat server kept its process, restarts 0), and filler ran again within 10 s, so a cool phone warmed up under watch. And the founder ran real requests on both phones at 22:12 to 22:15 and at 22:26, which gave real decode readings at each step. Each change of `realTps` is one finished real request; it is counted at the step of the sample that first shows it, so a request that ran across a step change is counted at the later step.

| Phone | Thermal Status | gpu / cpu-cluster0 / cpu-cluster1 | Real requests | Decode, tok/s | Filler at half duty, tok/s with prompt time |
|---|---|---|---|---|---|
| C | 0 | 0 / 6 / 12 | 2 | 18.17 and 18.30 | |
| C | 0 | 4 / 7 / 14 | 7 | 14.12 to 14.30 | |
| C | 1 | 5 / 10 / 15 | 8 | 13.32 to 13.62 | |
| C | 2 | 5 / 10 / 15 | 2 | 13.32 and 13.44 | 9.07 to 10.40, median 9.67 (6 windows) |
| C | 2 | 7 / 13 / 17 | 4 | 10.74 to 11.34 | 7.22 and 7.65 (2 windows) |
| B | 2 | 7 / 13 / 17 | 13 | 10.79 to 10.99 | 7.22 to 8.86, median 7.98 (13 windows) |

- The same phone at the same Thermal Status 2 decoded at 13.3 to 13.4 tok/s at step 5/10/15 and at 10.7 to 11.3 at step 7/13/17. The cooling step separates the two levels; Thermal Status does not. This is what section 3 could not show.
- A cold phone is faster again: 18.2 to 18.3 tok/s at step 0/6/12. This is where the 18.1 to 18.3 tok/s per phone in the three-phone batch of the final-build smoke run came from, which no file had explained. It does not last under work: four minutes of real requests and filler took Phone C from 33.6 to 42.6 C skin temperature and from step 0 to step 5.
- Filler tokens follow the step too: about 9.7 tok/s at step 5 against about 7.5 to 8.0 at step 7, at the same half duty.
- Where the steps changed on Phone C this time: 0 to 4 between 35.9 and 37.2 C skin, 4 to 5 at 39.5 C, 5 to 7 between 42.1 and 42.5 C during a burst of real requests (battery 39.7 to 39.9 C), and 7 back to 5 five and a half minutes later at 41.4 C (battery 38.9 C) while filler ran at half duty.
- Phone B never left step 7 in the 73 minutes watched (21:21 to 22:34), although its skin fell to 41.0 C three times and its battery to 38.3 C, both lower than where Phone C left the step. So what moves the step is not the skin temperature the thermal report shows, and not the battery temperature. The two phones run different system builds (16.0.21.12 and 16.0.20.1). Not explained.
- The app's own user can read the step: `/sys/class/thermal/cooling_device<N>/type` names the device and `cur_state` holds the level (checked on Phone B with `run-as com.swarmos.app`: `gpu` 7, `cpu-cluster0` 13, `cpu-cluster1` 17, and the CPU frequency ceilings 1.997 of 3.629 GHz and 2.112 of 4.608 GHz). Whether the app's own process may read it is checked only after an install.

Phone C in this window: 268 filler jobs, 0 failed, generating 616 s of 1692 s (36 %, with the first five minutes stuck and the founder's requests in between). Phone B: 313 jobs, 0 failed, 845 s of 1692 s (50 %).

## 6. At the install (22:51 to 22:56): the step is not temperature alone

From the third watch (`B-3.jsonl`, `C-3.jsonl`, which also records the kernel's board and virtual thermal zones). One event each; the watch is still running and its full reading is not written up here.

- On both phones the step fell from 7/13/17 to 5/10/15 in the sample in which the app was replaced by the install (Phone B 22:51:56 at 42.1 C skin, Phone C 22:52:26 at 41.2 C), with no cooling before it. Phone B had not left step 7 in the 90 minutes before. So the phone's throttle keeps a state that a restart of the app resets; it is not a plain function of any temperature.
- While Phone C's model was restarting and Ustaad was briefly in the background, its step went 5, 4, 0, 4, 5 within 90 s at 39.8 to 41.7 C skin. Step 0 had otherwise been seen only under 34 C.
- The one step up to 7 seen with the zones recorded (Phone C, 22:42:57, during real requests on top of half-duty filler): `tz_shell` went from 39.82 to 40.04 C and the battery from 39.8 to 39.9 C. The step up at 22:26 also came at a battery reading of 39.9 C. On Phone B, `tz_shell` stayed between 38.9 and 39.9 C for the whole time it sat at step 7.
- After the install both phones ran at step 5 at half duty, Thermal Status 2, with no real requests. It did not hold: Phone B went to step 7 at 22:59:26, seven and a half minutes after the restart, and Phone C at 23:03:09. In both samples `tz_shell` had just reached 40.0 C (40.011 and 40.022); while the phones were at step 5 it had read at most 39.99 C. With the 22:42:57 event that is three steps up, on two phones, all at `tz_shell` 40.0 C. Skin temperature at the three events was 42.1 to 42.4 C and Thermal Status 2, as it had been for minutes before.
- So the slow step starts when the kernel zone `tz_shell` reaches 40.0 C, and half duty at Thermal Status 2 gets a phone there within ten minutes by itself. Once there, the phones stayed at step 7 at half duty with `tz_shell` between 39.5 and 40.0 C. Where the step is left on the way down is not known: under 38.9 C (Phone B never got lower and never left it).
- The app's own user can read that zone too (`thermal_zone<N>/type` is `tz_shell`, `temp` in thousandths of a degree; checked with `run-as` on both phones, where N and the number of the `gpu` cooling device differ).
- In the nine and a half minutes after 22:57 the filler generated 50 % of the time on both phones (Phone B 112 jobs, Phone C 119 jobs, 0 failed).

## Not measured
- Where the step is left on the way down, and why a restart of the app resets it. The third watch runs until about 01:04.
- The rule of section 4 on a phone: how long the light phase lasts, whether step 5 then holds, the share of time it costs.
- Whether the app's own process may read the step (the check used `run-as`).
- Phone A.
