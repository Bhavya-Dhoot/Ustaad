# Ustaad setup: from a clean phone to a ready swarm

Updated 2026-10-10 against master 0a71f33 (gates 0 to 5 closed, gate 6 polish open); every branch listed in the first version of this file has been merged. Read docs/CLAIMS.md next to this file: it is the binding list of what may be said. A number in parentheses is followed by the file it comes from. "Not verified" means no code or benchmark file records it. Build commands live in docs/BUILD.md; this file adds the order of work, the by-hand steps and the checks. The check of eight screen fixes is running (docs/design/qa3).

The product is Ustaad. Code identifiers keep the old name (package `com.swarmos.app`, deep link `swarmos://pair`) until the final rename (docs/CONTRACTS.md section 10).

## 1. What you need

PC (Windows 11, Git Bash): JDK 17 at `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`; the Android SDK with `app/local.properties` holding `sdk.dir=C\:\\Users\\DELL\\AppData\\Local\\Android\\Sdk` (gitignored); adb at `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe`; `export MSYS_NO_PATHCONV=1` before any `adb shell` with an absolute path. The llama.cpp executables are committed (`app/app/src/main/jniLibs/arm64-v8a/`, `native/prebuilt/`), so the NDK is only needed to rebuild them. Python 3.12 with `pip install pillow reportlab` for `scripts/make-demo-corpus.py`.

| Phone | Serial | Wireless ADB |
|---|---|---|
| A | 10BFC41T2N001UZ | 192.168.242.89:5555 |
| B | 10BFAU14Q6000XR | 192.168.242.23:5555 |
| C | 10BFAT216Z000XQ | 192.168.242.76:5555 |

All three phones are iQOO I2501 on Android 16 and OriginOS 6 (CLAUDE.md). `bash scripts/adb-connect.sh` reconnects them. Phone B was reserved from 03:40 and released at f8ed057; the second end-to-end run and the three-phone MemoryCloud run used all three phones (docs/CLAIMS.md section 0, limit 2).

## 2. Build

Two build types (docs/BUILD.md):

| Build | Task | Application id | Debuggable | Pairing secret |
|---|---|---|---|---|
| debug | `:app:assembleDebug` | `com.swarmos.app` | yes | fixed `swarmos-dev-secret` |
| demo | `:app:assembleDemo` | `com.ustaad.app` | no | random per install |

```
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDemo
bash scripts/gradle-locked.sh "$PWD/app" :app:testDebugUnitTest
```

The demo APK is `app/app/build/outputs/apk/demo/app-demo.apk`. The lock wrapper allows two builds at once; the PC hard-crashed once under full load (plans/2026-10-10-integration.md).

The demo build is what judges see. It has no debug routes, ignores every adb intent extra (`name`, `setupDone`, `autoload`, `gpu`, `activity`, `gallery`), does not accept the dev secret, and `run-as` does not work on it (docs/BUILD.md). Consequences:
- Naming, the first-run setup and every pairing step are done by hand on each phone.
- `scripts/swarm-call.sh` and `scripts/e2e-check.sh` cannot drive it. Every preflight check below is read from the screen.
- Both builds use the same ports (8787, 8788, 8080, 8081, 50052). Only one can run at a time: stop the debug app first (`adb shell am force-stop com.swarmos.app`, or "Stop Ustaad" in its settings), or build the demo with `-PportOffset=60` (docs/BUILD.md).
- No benchmark in the repository used the demo build, and its install, model push and pairing are only partly verified: build, install and three curl checks ran on phone C, with no recorded result; pushing models into the demo package and pairing demo phones with a QR scan or a pasted link are NOT verified (docs/BUILD.md, docs/CLAIMS.md PS8). Rehearse on the build you will present.

## 3. Install and name

```
for P in 192.168.242.89:5555 192.168.242.23:5555 192.168.242.76:5555; do
  adb -s $P install -r app/app/build/outputs/apk/demo/app-demo.apk </dev/null
done
```

Name each phone on the phone: tap the name on the status strip, Settings opens, pick the A, B or C token, "Save changes". Colours follow the name: A teal, B tangerine, C magenta (docs/design/DESIGN.md). On a debug build you can use `adb shell am start -n com.swarmos.app/com.swarmos.app.MainActivity --es name "Phone A" --ez setupDone true`; the demo build ignores those extras.

Warning on uninstalling. Android removes an app's `Android/data/<package>` folder on uninstall (general Android behaviour, not tested here) and the models live there. Do not uninstall a phone's `com.swarmos.app` or `com.ustaad.app` unless the models can be pushed again. `install -r` keeps them.

## 4. First-run setup taps (every phone, by hand)

The first screen is "Three things before we start" (docs/design/qa/01-first-run-setup.png). Only a person can tap these. Each row turns into a check mark when done.
1. Allow nearby devices (nearby Wi-Fi, location and the Bluetooth permissions). Needed to form and join the Wi-Fi Direct group.
2. Allow notifications. Shows what Ustaad is doing and lets you stop it.
3. Turn off battery limits for Ustaad. Opens the Android dialog for this package.
4. Allow background start (only on vivo and iQOO). Opens the Ustaad page in system settings. The menu names (high background power use, auto-start, locking the card in Recents) come from community guidance (docs/research/06-hackathon-context-and-landscape.md) and have not been checked on these phones.

"Continue" enables after rows 1 to 3. Camera, photos and microphone permissions are asked later by the screen that needs them.

Why it matters, and what is not proven. OriginOS froze the app in every screen-off test: 22 to 265 consecutive missed heartbeats, and the link came back 0.6 s after the app returned to the front (2026-10-10-swarmlink-g1.md). `pem_freezer` killed the app and both model servers at 6 min 18 s and 11 min 2 s with chat generating (2026-10-10-inapp-llm.md). The battery exemption is untested (gate-2-inference-baseline.md, docs/CLAIMS.md UX3). The pass criterion is zero away transitions in 20 minutes with the screen off (2026-10-10-swarmlink-g1.md). Do not say the battery setting fixes background kills until that soak is run (docs/CLAIMS.md R20).

The supported rule for the demo: Ustaad in front, screen on. The app keeps the screen on while it is in front (MainActivity.kt). Say "Ustaad works while it is in front with the screen on" and nothing about background operation.

## 5. Models

Each app id has its own models folder. The demo package starts with an empty one (docs/BUILD.md). Push from the PC (`H:\UStaaf\models\`, gitignored):

```
D=/sdcard/Android/data/com.ustaad.app/files/models        # com.swarmos.app for the debug build
for P in 192.168.242.89:5555 192.168.242.23:5555 192.168.242.76:5555; do
  adb -s $P shell mkdir -p $D </dev/null
  adb -s $P push models/Qwen3.5-4B-Q4_K_M.gguf $D/
  adb -s $P push models/embeddinggemma-300M-Q8_0.gguf $D/
  adb -s $P shell ls -l $D </dev/null
done
```

| File | Role | Bytes | SHA-256 starts |
|---|---|---:|---|
| `Qwen3.5-4B-Q4_K_M.gguf` | chat (Choir, Data Union answers) | 2,740,937,888 | `00fe7986` |
| `embeddinggemma-300M-Q8_0.gguf` | embedder (Data Union, saved answers) | 333,590,944 | `b5ce9d77` |
| `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` | MemoryCloud, demo step 4: only on the driving phone (C); the lenders get their weights over the link | 18,556,686,752 | `6c997b8a` |

All from docs/models.md. A push takes 14 minutes per 2.7 GB file over wireless ADB (2026-10-10-inapp-llm.md); use the USB cable for the 30B file (over an hour wireless, docs/models.md). Do not load Gemma 4 E4B for the demo: 7 of 10 valid JSON outputs (2026-10-10-inapp-llm.md). EmbeddingGemma 2 does not load on llama.cpp v0.6.0 (docs/models.md). Models are pushed with adb; the in-app download with its SHA-256 check is unit-tested and has not run on a phone (docs/CLAIMS.md R17).

Backend. The chat model runs on the Adreno GPU by default and falls back to the CPU build; the Models tab shows a GPU or CPU chip. One run each on phone C from cool: prefill 201.9 tok/s on the GPU against 71.6 on the CPU, first token 2.59 against 7.20 s, server memory 1032 against 5952 MB, decode 17.31 against 16.47 tok/s (2026-10-10-inapp-llm.md). Quote it as one run each. The end-to-end runs used the CPU build because the GPU server failed to start then (2026-10-10-e2e-choir.md, finding F1); the fix is merged (2026-10-10-inapp-llm.md). The second run used the GPU on all three phones (2026-10-10-e2e2-swarm-throughput.md, 2026-10-10-e2e2-data-union.md).

Open the Models tab and wait for both servers to read READY. If nothing is loaded the one primary button is "Load models". Load time is 4 to 6 s warm and 19 to 31 s cold (gate-2-inference-baseline.md); the GPU chat server loaded in 13.3 s warm and 24.2 s with a cold file cache on phone C (2026-10-10-inapp-llm.md). A killed server restarts itself once: READY again after 15.9 s and 17.4 s in the one test (2026-10-10-inapp-llm.md).

## 6. Demo corpus and indexing

```
python scripts/make-demo-corpus.py --push-only
```

The script imports Pillow at the top, so install `pillow reportlab` even for `--push-only`. It pushes to `/sdcard/Pictures/SwarmDemo` and `/sdcard/Documents/SwarmDemo` on each phone and asks MediaStore to rescan (12 files for A, 12 for B, 11 for C, counted in docs/demo-corpus/). The story: `IMG_2231.jpg` (the laptop bill) belongs on B, `Laptop_Extended_Warranty_Certificate.pdf` on A, C holds unrelated files including a Hindi note, and `Private/Salary_Slip_Sep2026.pdf` on B must stay withheld.

Check B before the demo. The acceptance pair (photo on B, certificate on A) ran in the second end-to-end run with Phone B asking: both were cited, the photo first (2026-10-10-e2e2-data-union.md, gate-4-private-data-union.md). It was not run from another phone. The equivalent pair across A and C was run.

On each phone, by hand, after both servers read READY: Data tab, Photos row "Index"; Documents row "Pick folder", choose `Documents/SwarmDemo`, then "Index". The folder picker cannot be driven over adb. Folders and scans are private until you tap "Share with the swarm"; a folder named Private can never be shared (gate-4-private-data-union.md). To show the withheld count, leave one folder unshared. Read the chunk count on the Data tab: it must be above zero, with the real embedder name beside it (`embeddinggemma-300M-Q8_0`). Reference run: 10 to 11 chunks per phone, index runs 0.3 to 2.4 s, OCR 161 and 165 ms per image (2026-10-10-e2e-data-union.md).

Index first, load models for a split later. Indexing refuses to start without a real embedder, but if the embed server stops during a run (a split, a memory kill) placeholder vectors can be indexed under the real key; this second-review finding is open (docs/review/2026-10-10-second-review.md [pdu] HIGH).

Knowledge Bank. Data tab, "Knowledge bank", and "Save to knowledge bank" under a finished answer that a real chat model wrote and that cites a source. Measured on three phones with no sync call (2026-10-10-knowledge-bank.md, "Third run"): a shared save and a revoke were each listed on both other phones at the first poll, under 0.8 s; with the author's phone stopped the other phone answered from the card in 40 and 47 ms. The saves were made through debug routes, which the demo build does not have, and the page was only seen in the second visual pass (docs/design/qa2/); not measured: a phone that is away when the card is saved, the 3-minute timer, more than three phones. Do not put it in the live script.

## 7. Pairing and the Wi-Fi Direct group

Host phone (the one you hold, usually A or C). Swarm tab, "Host a swarm", then "Host over Wi-Fi Direct (5 GHz)". The page shows a QR code, a short pairing code, "Copy link" and "Share link", the network name and the measured band (docs/design/qa/fix-link-host-page.png). Hosting makes a new pairing secret. The group asks for the station's own 5 GHz channel by default; on a different channel the link fell to 10.3 Mbps against 426 Mbps on the station's channel, one run each (2026-10-10-swarmlink-throughput.md, section 1).

Joining phone. Swarm tab, "Join", allow the camera, point it at the host's QR code. The joiner shows the host's pairing code and waits for confirmation before it adopts the secret (docs/design/qa/fix-link-join-page.png; nobody has tapped that dialog, docs/CLAIMS.md LK11). Without a camera, "Copy link" or "Share link" on the host and the paste field on the Join page ("Pair with pasted code"). Not verified: the camera scan between two demo phones, the paste route, and how a link gets from one phone to the other with no internet (docs/BUILD.md). The `swarmos://pair` deep link is read by every build; the demo build asks for confirmation, and whether `adb shell am start -a android.intent.action.VIEW -d "swarmos://pair?d=..."` works on a demo install is not verified (docs/BUILD.md says it works only on debug builds).

Order: host once, then scan on every other phone. Pressing Host again changes the secret and the phones that did not scan the new code drop out (2026-10-10-swarmlink-g1.md).

Zero-setup path, debug builds only: all phones on one access point with the dev secret list each other 3.7 to 5.3 s after a cold start (2026-10-10-swarmlink-g1.md). Demo builds do not.

Measured link (A and C, 200 MiB): Wi-Fi Direct 640.7 to 724.9 Mbps raw and 790 to 920 Mbps with 4 to 8 streams; through the access point 89.2 to 102.2 Mbps raw (2026-10-10-swarmlink-throughput.md). Older pair A and B, 100 MiB: Direct 305 to 503 and LAN 96 to 104 Mbps (2026-10-10-swarmlink-g1.md). Round trip: TCP echo averages 6.3 to 8.3 ms on Direct; a signed request through the app averages 20 to 28 ms (2026-10-10-swarmlink-throughput.md). B to C over Direct was 93.2 to 121.0 Mbps while other jobs ran and was not retaken quiet (2026-10-10-swarmlink-g1.md). The Wi-Fi standard of the Direct interface cannot be read, so it is not quoted.

Check on screen: the Swarm tab lists two online peers; open one and "Measure again (100 MB)"; the Frequency row must equal the station's frequency. For demo step 4 the Direct group is hosted by Phone C, the driver, with A and B as clients: the weights travel over it (C to A 680 Mbps, C to B 245 Mbps, round trip about 7 ms, group at 5765 MHz, formed in 1.1 s each, 2026-10-10-memorycloud-inapp.md). In that run the group was formed with debug routes and the dev secret; with the demo build host it from C through "Host over Wi-Fi Direct (5 GHz)" (not run). The status strip says "No internet" only when no network has validated internet, otherwise a plain "online" word (and the model downloader's byte count); it is not a measurement of all traffic (docs/CLAIMS.md PS2).

## 8. Pre-demo checklist

Run it the day before and one hour before.

| Item | How to check | Source |
|---|---|---|
| One build on all three phones | Settings sheet shows the version; same APK file | docs/BUILD.md |
| Names A, B, C | Status strip | section 3 |
| Setup rows done | No setup screen on launch; battery row has a check | section 4 |
| Date and time automatic | Phone settings. A request more than 30 s off gets 401 | docs/CONTRACTS.md section 6 |
| Other saved Wi-Fi networks forgotten | Phone A once left the venue network for a saved one by itself, cause not found, and a run stopped (2026-10-10-e2e-choir.md, F5). Suggestion, not tested | none |
| Phones cool | Strip says Cool; the Models benchmark and the Choir eval refuse to start when hot (docs/CLAIMS.md UX4). Any sustained run reaches Thermal Status 3 to 4 within 2 to 10 minutes: 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10 on the GPU (2026-10-10-llama-cpu-baseline.md) | docs/PRD.md section 12 |
| Chargers, fan, screens on, Ustaad in front | Phones on chargers with a fan; no other app opened after launch | section 4 |
| Airplane mode, Wi-Fi back on | Not verified: every run had the access point up and whether it had internet was not recorded (docs/CLAIMS.md PS3). Rehearse the whole script this way before promising it | none |
| Models READY, backend chip | Models tab: chat chip GPU, embedder READY | section 5 |
| Corpus indexed, real embedder | Data tab chunk count above zero on all three | section 6 |
| Swarm formed | Two online peers with DIRECT | section 7 |
| Demo build closed | Run the three curl checks in docs/BUILD.md (200, 401, 401) and write the result down | docs/BUILD.md |
| One Data Union ask, PRD pair | "Where is the warranty bill for my laptop?" from A; B's bill and A's certificate both cited | docs/PRD.md R4.4 |
| One Choir run on the demo build | Run `m3` once (it needed a second round in both second-run passes and the Choir was right in both; `m2` was wrong in both, 2026-10-10-e2e2-choir.md). A phone row that says FAKE or "No model loaded, not asked" means the chat model is not READY there | docs/CLAIMS.md CH11 |
| One MemoryCloud split | Memory tab on Phone C: the 30B file across A and B. Do one cold load before the judges arrive (319 s) and keep the lenders' caches; time a fresh start after Stop with warm caches (only a re-plan, 65 s, was timed). Then Stop; chat and embedder come back READY on all three | 2026-10-10-memorycloud-inapp.md, gate-5-memorycloud.md |
| One Verify | Settings, Audit log, "Verify": Chain OK. No phone run is recorded (docs/CLAIMS.md PS6) | shell/SettingsSheet.kt |
| Choir scoreboard | Do not run the 20-problem scoreboard shortly before the demo: it heated all three phones to Thermal Status 2 to 3 in eight minutes, and the demo runs two or three problems (gate-3-agent-choir.md) | gate-3-agent-choir.md |
| Spares | Second charger, USB cables, a travel router | docs/PRD.md section 7 |
| Office Kit mirroring | Try it. Not verified; no claim until it has been tried (docs/CLAIMS.md R20) | docs/DEMO.md |

## 9. Troubleshooting

| Symptom | Cause | What to do | Evidence |
|---|---|---|---|
| A phone shows "away", or the app and both servers die, with the screen off or another app in front | OriginOS freezes the app (`pem_freezer`); the servers are its children | Keep Ustaad in front, screen on. After a restart wait for READY | 2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md |
| Decode falls (12.3 to 6.8 tok/s over ten minutes on the GPU), strip says Severe or shows the Hot stamp, benchmark says too hot | Thermal throttling | Stop, fan, charger, wait for Cool. Keep each segment short | 2026-10-10-llama-cpu-baseline.md |
| Models tab: "port 8080 is still in use after two retries" | A stray llama or adb shell process holds the port | `adb shell ps -A`, look for `llama` or `rpc`, kill it, "Load models" | llm/LlamaRuntime.kt |
| Phone invisible to peers, no error | Another copy of Ustaad holds 8787: the debug and demo builds share ports | Force-stop the other build; one Ustaad per phone | docs/BUILD.md |
| "rejected our signature" or the peer row says clock off by N s | Different pairing secret, or clocks more than 30 s apart; the peer row now names a clock gap | Automatic date and time; host once and scan again | 2026-10-10-swarmlink-throughput.md section 7 |
| Peers vanish after pressing Host | New secret; phones that did not scan the new code hold the old one | Scan on B and C; press Host once | 2026-10-10-swarmlink-g1.md |
| Direct link far below 400 Mbps, heartbeat 300 to 670 ms | Group on a channel other than the station's | Host again; check Frequency equals the station's | 2026-10-10-swarmlink-throughput.md section 1 |
| Direct group gone | The app re-forms it from stored credentials 2.2 s after removal, and asks the host for new credentials if the host re-created the group (4.2 s) | Wait; if still down use "Use current Wi-Fi instead" | 2026-10-10-swarmlink-throughput.md section 6 |
| Bluetooth path listed at some moments and missing at others | Cause not found; it never affected the Wi-Fi paths | Do not rely on it; say "control messages only" | 2026-10-10-swarmlink-throughput.md section 5 |
| Camera does not read the QR code | Camera scan untested | Paste the link on the Join page | docs/CLAIMS.md LK9 |
| Chat chip says CPU, or the Models tab shows a notice that the GPU server failed | The GPU child could not start; the app fell back | Read the notice; CPU works; prefill is about 2.8 times slower | 2026-10-10-inapp-llm.md |
| Data tab shows 0 chunks, or a Fake embedder stamp | Embedder not READY, or index wiped by a changed embedder | Wait for the real embedder, then Index again; never index during a split | docs/review/2026-10-10-second-review.md |
| An ask lists sources while the answer says nothing relevant was found | Relevance margin is thin (0.014 on three questions) | Say so; ask a clearer question | 2026-10-10-e2e-data-union.md |
| A Choir lane says "No answer" | The model gave no valid answer after one retry; the lane is left out of the vote | Continue; it is the designed behaviour | 2026-10-10-choir-answer-quality.md |
| Choir row for a phone says "No model loaded, not asked" | That phone's chat model is not READY | Models tab on that phone, "Load models" | choir/ChoirScreen.kt |
| Memory tab: splitting is slower than one phone | By design for a model that fits one phone: 6.86 and 6.98 tok/s split against 10.80 and 14.69 alone | Say so; do not use it as a speed result | 2026-10-10-memorycloud-inapp.md |
| Memory tab: a phone "may not fit" | The planner's estimate adds 1.5 GB per phone; it is an estimate | Pick the other phones or skip one | 2026-10-10-memorycloud-inapp.md |
| 30B on one or two phones | One phone: no token in 134 s, 23 apps killed. Two phones: did not load, driver thrashed | Never run it live on fewer than three phones | gate-5-memorycloud.md |
| First 30B start fails in about 12 s with "port 50052 is still in use" on a lender | A stale listener held the port; the teardown left nothing behind and the second start worked; cause not found | Press load again; if it repeats, stop everything and retry | 2026-10-10-memorycloud-inapp.md, gate-5-memorycloud.md |
| The DEGRADED screen says "Re-plan with 3 phones" for a few seconds after a phone is lost | The count uses the phones the registry still lists online | The button works either way; wait a few seconds | gate-5-memorycloud.md |
| Cached apps on the driver are killed during the split load (camera, search, messaging) | Memory pressure; Ustaad itself was not killed while in front | Expect it; keep Ustaad in front | gate-5-memorycloud.md |
| Worker phone seems to have lost its models after a split | The worker stops its own chat and embedder to lend memory and restores them on stop or 10 s after the driver stops renewing; the lease is unit-tested only | Memory tab "Stop lending"; "Load models" if needed | 2026-10-10-memorycloud-inapp.md |
| adb offline | Wireless transport dropped | `bash scripts/adb-connect.sh` | docs/CONTRACTS.md section 7 |

## 10. What is merged

Everything the first version of this file called "not on master" is merged, as are the Choir JSON-fragment rejection and number rounding (08a205f) and the Knowledge Bank push after save and revoke (924e720, measured in the third run): the demo build, the Data Union privacy fixes, the Swarm link phase 2 (response authentication, pairing confirmation, local-address rule, failover, Bluetooth), the scheduler panel (embedded in the Swarm screen), MemoryCloud and ExpertMesh (an explanation screen), the strict Choir answer scorer (01f79a6), the GPU start fix (71f2359) and the Knowledge Bank prototype (41316c7). Open review items are in docs/review/2026-10-10-second-review.md and the visual defects in docs/design/qa/REPORT.md (17 defects at commit 71f2359; several fixed since, not re-shot).
