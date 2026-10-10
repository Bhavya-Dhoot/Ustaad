# Keep busy watched from outside, phones B and C (2026-10-10, 21:21 to 22:01)

Read-only watch of both phones for 40 minutes while the founder was using them: `scripts/throttle-watch.py`, one sample every 10 s (237 per phone) of `dumpsys thermalservice` and the signed `GET /v1/sched/busy`. No job was sent, nothing was started or changed on a phone. Build on both phones: 1ac22c5, both charging, app in front, keep busy on. Raw: `raw/gate-9b/throttle-passive/B.jsonl` and `C.jsonl`. One window, one run.

The question was which readable signal marks the step between the fast level (about 13.5 tok/s) and the slow level (about 10.8) of real work inside Thermal Status 2 (evening-checks section 9). The watch did not answer it, and it found a fault instead.

## 1. Phone C ran no filler for 26 minutes and more (fault, fixed in code, not yet on the phones)

| | Phone B | Phone C |
|---|---|---|
| Filler jobs in the window | 406, 0 failed | 119 until 21:35:18, then none; 0 failed |
| Time generating | 1090 s of 2392 s (45.6 %) | 324 s of the first 814 s (39.8 %, the founder's own requests ran in between), then 0 s of 1580 s |
| Keep-busy state after 21:35:18 | half duty | "waiting for the phone to be idle" in every sample to the end of the window, and still at 22:06 |
| Thermal Status at the end | 2 (skin 42.0 C) | 0 (skin 34.7 C) |

Cause, read from the phone at 22:03 (`GET /v1/memcloud/state`): at 21:35:20 a MemoryCloud start for the 30B model was refused on Phone C ("The plan does not fit: Phone C has 5.6 GB free and the planning estimate is 10.3 GB; Phone B has 7.2 GB free and the planning estimate is 9.9 GB. Nothing was started or stopped."). The refusal is correct. But it leaves MemoryCloud in the phase FAILED until the owner starts again, and the keep-busy loop counted every phase except IDLE as real work in progress. So the phone waited for an idle moment that never came, cool and with its model loaded.

Fix: commit 1b9d60b, FAILED no longer counts as work in progress (`MemCloudLogic.holdsPhone`, one unit test; 1431 tests, 0 failures). The debug APK is built. It is not installed: the founder was using the phones. Until it is, a refused or failed MemoryCloud start stops keep busy on that phone until the app is restarted or a split is started and stopped.

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

A second read-only watch is running on both phones since 22:06 for 180 minutes (`B-2.jsonl`, `C-2.jsonl`), with two more fields per sample: the decode speed of the last real request (`realTps`) and the MemoryCloud phase (`memPhase`).

## 4. What follows for the duty rule

Nothing is changed yet. If the second watch shows that the slow level starts at a skin temperature above the 42 C where Thermal Status 2 starts, and ends only at 40 C, then half duty at Thermal Status 2 is what holds a phone in the slow level: it keeps the skin at 41 to 43.5 C, above the release point, as Phone B showed for the whole window. A pause of about 90 s was enough on Phone C to get under 40 C. That would be the lever, and the cost in filler share has to be measured before any rule is changed.

## Not measured
- The step up to 7/13/17, and filler or real speed at 5/10/15 inside Thermal Status 2.
- Whether the app can read any of this itself. `dumpsys thermalservice` needs adb. The app can read Thermal Status, the battery temperature and the system's thermal headroom figure; none was compared with the cooling step.
- Phone A.
