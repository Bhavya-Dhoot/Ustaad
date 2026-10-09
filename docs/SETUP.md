# Ustaad setup: from a clean phone to a ready swarm

Written 2026-10-10 against master at b661cdf (03:50 IST). Anything that exists only on an unmerged branch is marked [branch]; run `git branch --no-merged master` and `git log master..<branch> --oneline` before you rely on it. A number in parentheses is followed by the file it comes from. "Not verified" means no file in the repository records it.

The product is called Ustaad. Code identifiers still say SwarmOS (package `com.swarmos.app`, deep link `swarmos://pair`) until the final rename step (docs/CONTRACTS.md section 10).

## 1. What you need

PC (Windows 11, Git Bash):
- JDK 17 at `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` and the Android SDK; `app/local.properties` must contain `sdk.dir=C\:\\Users\\DELL\\AppData\\Local\\Android\\Sdk` (gitignored, plans/briefs/_common.md).
- adb at `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe`. In Git Bash run `export MSYS_NO_PATHCONV=1` before any `adb shell` with an absolute path (CLAUDE.md).
- NDK 28.2 only if you rebuild llama.cpp. You do not need it to build the app: the executables are committed in `app/app/src/main/jniLibs/arm64-v8a/` (`libllama-server.so`, `librpc-server.so`) and `native/prebuilt/arm64-v8a/`.
- Python 3.12 with `pip install pillow reportlab`, only for `scripts/make-demo-corpus.py`.

Phones: three iQOO I2501 (iQOO 15), Android 16, OriginOS 6, wireless debugging on (CLAUDE.md).

| Phone | Serial | Wireless ADB |
|---|---|---|
| A | 10BFC41T2N001UZ | 192.168.242.89:5555 |
| B | 10BFAU14Q6000XR | 192.168.242.23:5555 |
| C | 10BFAT216Z000XQ | 192.168.242.76:5555 |

`bash scripts/adb-connect.sh` reconnects all three. The Redmi M2101K6P is not a target. During the build phase phone B was reserved for the founder (plans/briefs/_common.md); for the demo all three are yours.

## 2. Build

Debug build, from `H:\UStaaf\app`:

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK: `app/app/build/outputs/apk/debug/app-debug.apk`. If anything else is building on the PC, use `bash scripts/gradle-locked.sh "H:/UStaaf/app" :app:assembleDebug`, which allows two builds at once; the PC hard-crashed under full load once (plans/2026-10-10-integration.md).

Demo build. On master b661cdf `app/app/build.gradle.kts` has only `debug` and `release` (release is unsigned, so it does not install). Commit 65b591b [branch worktree-agent-a603d19fc13a9fb9b] adds a `demo` build type: not debuggable, signed with the debug key, application id `com.ustaad.app`. Once it is merged:

```
./gradlew :app:assembleDemo
```

APK path by Gradle convention: `app/app/build/outputs/apk/demo/app-demo.apk` (not verified).

Why the demo build matters (docs/review/2026-10-10-merged-app-review.md, "Fix first" item 1): a debug build keeps every debug route open behind the fixed secret `swarmos-dev-secret` (`/pdu/debug/scan`, `/choir/debug/script`, and others). The demo build turns those routes off and gives each install a random pairing secret (AppGraph.kt). Consequences you must plan for:
- Phones do not trust each other until paired by QR or deep link. On the same Wi-Fi they show "rejected our signature (different pairing secret)".
- `scripts/swarm-call.sh` (dev secret) cannot call a demo build, so every preflight check below has to be read from the screen.
- The package name changes, so adb commands use `com.ustaad.app`, and the models folder moves (section 5).

Pick one build for all three phones and rehearse on the build you will present. Do not mix them.

## 3. Install and name

```
adb -s 192.168.242.89:5555 install -r app/app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.242.89:5555 shell am start -n com.swarmos.app/com.swarmos.app.MainActivity --es name "Phone A"
```

Repeat for B (`192.168.242.23:5555`, "Phone B") and C (`192.168.242.76:5555`, "Phone C"). For the demo build use `com.ustaad.app/com.swarmos.app.MainActivity` (the class keeps the old namespace). Naming also works on the phone: tap the name on the status strip, Settings opens, pick the A, B or C token. Colours are fixed by name: A teal, B tangerine, C magenta (docs/design/DESIGN.md).

Do not uninstall Ustaad from a phone that holds models you cannot easily re-push. Android deletes an app's `Android/data/<package>` folder when the app is uninstalled (general Android behaviour, not tested here), and the models live there. `install -r` keeps them.

## 4. First-run setup taps (every phone, by hand)

The app opens on "Three things before we start". Only a person can tap these (plans/briefs/shell.md). Each row turns into a check mark when done.

1. Allow nearby devices. Grants NEARBY_WIFI_DEVICES, location, and the three Bluetooth permissions. Needed to form and join the Wi-Fi Direct group.
2. Allow notifications. Needed for the foreground service notification that keeps Ustaad running with the screen off.
3. Turn off battery limits for Ustaad. Opens the Android dialog "let this app always run in the background" for this package.
4. Allow background start (shown only on vivo and iQOO). Opens the Ustaad page in system settings. Set the app to allow high background power use and allow auto-start there. The menu names come from community guidance (docs/research/06-hackathon-context-and-landscape.md) and have not been checked on OriginOS 6 on these phones. Also lock the Ustaad card in Recents (same source, not verified).

"Continue" enables when rows 1 to 3 are done; "Skip for now" is always available. Camera, photos and microphone permissions are asked later by the screen that needs them (Join, Index photos, mic button).

Why this is not optional. OriginOS freezes the app when the screen is off or the app is not in front. The foreground service, the partial wake lock and the Wi-Fi low-latency lock did not stop it:
- Peers counted 22 to 265 missed heartbeats while a phone was frozen, and the link came back 0.6 s after the app returned to the front (2026-10-10-swarmlink-g1.md).
- `pem_freezer` froze and killed the whole app, servers included, 6 min 18 s and 11 min 2 s after screen-off while it was generating (2026-10-10-inapp-llm.md). An idle app survived the same window.
- The battery exemption and the vivo background settings have not been tested. The pass criterion is zero away transitions with the screen off for 20 minutes (2026-10-10-swarmlink-g1.md). PENDING MEASUREMENT: that soak, to be written into docs/benchmarks/.

For the demo the rule is simpler: keep every screen on with Ustaad in front. The app sets the screen-on flag while it is in front (MainActivity.kt). A phone that is frozen is marked away; one that stays in front for 14 minutes had zero misses (2026-10-10-swarmlink-g1.md).

Options that skip a tap, for a phone you drive over adb: `adb shell pm grant <package> <permission>` for runtime permissions, and `--ez setupDone true` on the launch intent to mark the setup done. `adb shell dumpsys deviceidle whitelist +<package>` is suggested in 2026-10-10-swarmlink-g1.md as a battery exemption and has not been tried.

## 5. Models

Only two files are needed for Agent Choir and Data Union. Sizes and hashes are from docs/models.md; the app checks the size at load and the download path verifies SHA-256.

| Role | File | Bytes | SHA-256 starts with |
|---|---|---:|---|
| Chat (Choir, Data Union answers) | `Qwen3.5-4B-Q4_K_M.gguf` | 2,740,937,888 | `00fe7986` |
| Embedder (Data Union) | `embeddinggemma-300M-Q8_0.gguf` | 333,590,944 | `b5ce9d77` |
| MemoryCloud only | `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` | 18,556,686,752 | `6c997b8a` |

Do not load `gemma-4-E4B_q4_0-it.gguf` for the demo. It returned empty content on 3 of 10 JSON-schema calls (2026-10-10-inapp-llm.md), it needs 6.4 GB resident, and phone C's service died 4 s after a selftest with it loaded (2026-10-10-inapp-llm.md). `embeddinggemma-2` does not load on llama.cpp v0.6.0 (docs/models.md).

Push from the PC (files are in `H:\UStaaf\models\`, gitignored). Debug package shown; for the demo build replace `com.swarmos.app` by `com.ustaad.app` (the folder follows the application id, inferred from `getExternalFilesDir("models")` in llm/ModelStore.kt, not tested on a demo install):

```
D=/sdcard/Android/data/com.swarmos.app/files/models
adb -s 192.168.242.89:5555 shell mkdir -p $D
adb -s 192.168.242.89:5555 push models/Qwen3.5-4B-Q4_K_M.gguf $D/
adb -s 192.168.242.89:5555 push models/embeddinggemma-300M-Q8_0.gguf $D/
adb -s 192.168.242.89:5555 shell sha256sum $D/Qwen3.5-4B-Q4_K_M.gguf
```

Push speed over wireless ADB is about 3 MB/s, so 14 minutes for the 4B file (2026-10-10-inapp-llm.md) and more than an hour for the 30B file (docs/models.md). Use the USB cable for the 30B file. PENDING MEASUREMENT: which phone holds the 30B file for MemoryCloud, and whether it is pushed into the app folder (the spike ran it from `/data/local/tmp/llama/models/`, 2026-10-10-rpc-split-spike.md).

Loading. "Load models at start" is on by default (Models tab). Open the Models tab and wait for both servers to show READY. Load time from start is 4 to 6 s warm and 19 to 31 s cold (gate-2-inference-baseline.md). Chat plus embedder take about 6.2 GB resident on one phone (2026-10-10-inapp-llm.md). If the tab says "No chat model is loaded: replies on this phone are canned", every answer is FAKE and labelled so. The in-app download button exists and says it uses the internet; it has not been exercised on a phone (gate-2-inference-baseline.md).

## 6. Demo corpus

`scripts/make-demo-corpus.py` generates synthetic files (12 for phone A, 12 for B, 11 for C, counted in docs/demo-corpus/) and pushes them to `/sdcard/Pictures/SwarmDemo` and `/sdcard/Documents/SwarmDemo` on each phone, then asks MediaStore to rescan.

```
python scripts/make-demo-corpus.py --push-only
python scripts/make-demo-corpus.py --push-only --phones B
```

The script imports Pillow at the top, so install `pillow reportlab` even for `--push-only`.

The story: the laptop bill is `IMG_2231.jpg` on B, the extended-warranty certificate is `Laptop_Extended_Warranty_Certificate.pdf` on A, C holds unrelated files, and B's `Documents/SwarmDemo/Private/Salary_Slip_Sep2026.pdf` must only appear as a withheld count (scripts/make-demo-corpus.py header).

Index on each phone, by hand, after both servers show READY:
1. Data tab, Photos row, "Index". Accept the photos permission.
2. Documents row, "Pick folder", choose `Documents/SwarmDemo`, then "Index" on the new row. The system folder picker cannot be driven over adb.
3. On B check the "Private folders" section shows the Private folder as never shared.
4. The hero number on the Data tab is the chunk count. It must be above zero on all three phones.

Read the embedder tag next to it. If it shows a "Fake embedder" stamp, do not index. On master, switching embedders wipes the index (pdu/PduEngine.kt `prepare`, review item 2). The guard that refuses to index without a real embedder is on the PDU branch [branch worktree-agent-aa03d1aa2341f4949], not on master. Indexing time is not measured (PENDING MEASUREMENT, docs/benchmarks/, no Data Union note exists yet).

## 7. Pairing and the Wi-Fi Direct group

There are three ways in, in the order to try them.

Same Wi-Fi, no setup (debug builds only). All phones on one access point, same dev secret. Each phone lists the other two 3.7 to 5.3 s after a cold start (2026-10-10-swarmlink-g1.md). Demo builds do not do this, because their secrets differ.

QR and Wi-Fi Direct (the demo path).
1. On A: Swarm tab, "Host a swarm", "Host over Wi-Fi Direct (5 GHz)". A QR code appears with the network name, band, address and group formation time. The group forms in 0.5 s on the host (2026-10-10-swarmlink-g1.md). Hosting makes a new pairing secret.
2. On B and C: Swarm tab, "Join", "Allow camera", point at A's code. The client joins by network name and passphrase with no dialog, in 1.3 to 1.8 s (2026-10-10-swarmlink-g1.md).
3. Swarm tab on A shows B and C with a DIRECT chip. Do not press Host again: a phone still holding the old secret drops out of the list (2026-10-10-swarmlink-g1.md, deep-link row).
4. If the group does not form, "Use current Wi-Fi instead" hosts on the shared network (96 to 104 Mbps, 2026-10-10-swarmlink-g1.md).

Paste or deep link, when the camera does not read the code. The camera scan has not been tested (2026-10-10-swarmlink-g1.md; only the encode and decode round trip and the deep link were). Paste the link into "Paste swarmos://pair link or code" on the Join page, or inject it:

```
adb -s 192.168.242.23:5555 shell am start -a android.intent.action.VIEW -d "swarmos://pair?d=<base64url>" com.swarmos.app
```

Check the pairing: the Swarm tab lists two online peers, each with a heartbeat time. Open one peer and press "Quick check (10 MB)" or "Measure again (100 MB)". On a quiet pair, Wi-Fi Direct gave 305 to 503 Mbps and 12 to 20 ms average RTT; the same phones through the access point gave 96 to 104 Mbps and 23 to 26 ms (2026-10-10-swarmlink-g1.md). The group shares the access point's channel, 5765 MHz, one radio (2026-10-10-swarmlink-g1.md). The Wi-Fi standard of the Direct interface cannot be read, so it is not claimed.

## 8. Pre-demo checklist

Run it the day before and again one hour before.

| Item | How to check | Source |
|---|---|---|
| Same build on all three phones | Settings sheet shows "Ustaad <version>"; `git log -1` on the PC | shell/SettingsSheet.kt |
| Names A, B, C | Status strip on each phone | docs/design/DESIGN.md |
| Setup rows done | Open Ustaad, no setup screen; battery row has a check | section 4 |
| Phones cool | Status strip says Cool; `adb shell dumpsys thermalservice` shows Thermal Status 0 or 1. A cool start reached status 2 to 3 within 1.5 to 4 minutes of load and cools about 0.3 C per minute with the screen on (2026-10-10-llama-cpu-baseline.md). The Models benchmark refuses to run above status 1 (2026-10-10-inapp-llm.md) and the Choir eval refuses on a hot phone (commit dfd7fd1) | 2026-10-10-llama-cpu-baseline.md |
| Chargers and fan | Phones on chargers, a fan on the table. Reviewers report about 52 % of peak after about 10 minutes of stress | docs/PRD.md section 7 |
| Screens on, Ustaad in front | No other app opened after launch; Ustaad card locked in Recents | section 4 |
| Date and time automatic | Settings on each phone. Requests more than 30 s off get HTTP 401 | docs/CONTRACTS.md section 6 |
| Airplane mode with Wi-Fi on | Switch airplane mode on, then switch Wi-Fi back on. Not verified on these phones: every Direct and LAN measurement so far was taken with the phones on an access point (2026-10-10-swarmlink-g1.md). Rehearse the whole demo this way before you promise it | none |
| Models READY | Models tab on each phone shows chat and embedder READY | section 5 |
| Corpus indexed | Data tab chunk count above zero, real embedder tag | section 6 |
| Swarm formed | Swarm tab lists two online peers with DIRECT | section 7 |
| One question answered | Ask "Where is the warranty bill for my laptop?" from A, then from B. Both must cite B's bill and A's certificate | docs/PRD.md R4.4 |
| One Choir run | Run the rehearsed problem; lanes finish before the 60 s deadline | docs/PRD.md R3.2 |
| Choir scoreboard saved | "Run all 20" once, on cool phones; the app stores `choir-eval-<ts>.json` in the app files folder | choir/ChoirRuntime.kt |
| Official numbers retaken | Link, decode and sustained numbers on cool idle phones. Owed since gate 1 and gate 2 | gate-1-swarmlink.md, gate-2-inference-baseline.md |
| Spares | Second charger per phone, USB cables, a travel router for the same-Wi-Fi fallback | docs/PRD.md section 7 |
| Office Kit | Laptop client installed, mirroring tested with the presenter phone. Not verified | docs/DEMO.md |
| Do Not Disturb on | So pop-ups do not cover the screen. Suggestion, not tested | none |

## 9. Troubleshooting

| Symptom | Cause | What to do | Evidence |
|---|---|---|---|
| A phone shows "away" when its screen is off or another app is in front | OriginOS freezes the app (`pem_freezer`); heartbeats stop | Keep the screen on with Ustaad in front. Do the setup rows. Bring the app to the front: the link returns in 0.6 s | 2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md |
| Whole app and both model servers die minutes after screen-off while generating | Same freezer; the process dies 16 to 25 ms after the freeze, and the servers are its children | Do not background Ustaad during a run. After a restart wait for READY again (4 to 6 s warm, 19 to 31 s cold) | 2026-10-10-inapp-llm.md, gate-2-inference-baseline.md |
| Decode falls from about 14.8 tok/s to 5 to 6 tok/s; strip says Severe or shows the HOT stamp; benchmark says "TOO HOT" | Thermal throttling. Decode was 14.86 tok/s cool, 10.7 at Thermal Status 3, 4.88 and 6.13 at CRITICAL | Stop. Fan, charger, wait for Thermal Status 0 or 1. Keep each demo segment under 3 minutes | 2026-10-10-llama-cpu-baseline.md, 2026-10-10-inapp-llm.md |
| Models tab: "port 8080 is still in use after two retries" | A leftover llama-server, an adb shell process or another app holds the loopback port | `adb shell ps -A` and look for `llama` or `rpc`; kill the stray process; then "Load defaults" | llm/LlamaRuntime.kt |
| Phone invisible to its peers, no error | Node port 8787 (probe port 8788) already used by another copy; the server only writes an audit line | Run one Ustaad per phone. Build variants with a port offset must be uninstalled | docs/CONTRACTS.md section 5, review section 5 |
| "rejected our signature (different pairing secret)" or HTTP 401 | Different secret, or a clock more than 30 s off. The message does not tell them apart | Check automatic date and time on all three, then host and scan again | 2026-10-10-swarmlink-g1.md, review section 2 |
| Peers vanish after pressing Host | Hosting makes a new secret; phones that did not scan the new code are on the old one | Scan the new code on B and C. Press Host once | 2026-10-10-swarmlink-g1.md |
| Data tab shows 0 chunks after indexing, or an index that was there is gone | Embedder changed or was FAKE; master wipes the index when the embedder changes | Wait for the real embedder tag, then Index again. Fix is on a branch | pdu/PduEngine.kt, review item 2 |
| Answers carry a "Fake model" stamp | Chat model not loaded | Models tab, "Load defaults"; check the file name and size in the models folder | llm/ModelsScreen.kt |
| Banner "The server was killed ... Restarting in 2 s" | Child server killed; one automatic restart | Wait. Process handling was tested by unit tests only, not on a phone | 2026-10-10-inapp-llm.md |
| A phone is slow or restarts its service after loading a second large model | Memory: 4B plus embedder is about 6.2 GB; Gemma 4 E4B is 6.4 GB; the 30B on one phone thrashed, produced no token in 134 s and made lmkd kill 23 apps | Load only the two default models. Never try the 30B on one phone live; show the measured result instead | 2026-10-10-inapp-llm.md, 2026-10-10-rpc-split-spike.md |
| Direct group does not form | No failure of this kind is recorded in the repository | Use "Use current Wi-Fi instead" (96 to 104 Mbps) | 2026-10-10-swarmlink-g1.md |
| Link slower than quoted | Loaded phones gave 93 to 288 Mbps on Direct and 56 to 83 on LAN; Direct and the access point share one channel | Stop other work on the phones; measure on a cool, idle pair | 2026-10-10-swarmlink-g1.md |
| Camera does not read the QR code | Camera scan untested | Paste the link or inject it with the deep link command in section 7 | 2026-10-10-swarmlink-g1.md |
| Heartbeat time in the peer list reads 30 to 280 ms | That is liveness over a once-per-second request, not the link RTT | Quote the Measure result, not the heartbeat | 2026-10-10-swarmlink-g1.md |
| adb says offline or the phone is missing | Wireless transport dropped | `bash scripts/adb-connect.sh`. Wireless adb did not drop while Direct formed | docs/CONTRACTS.md section 7 |
| Status strip says 0 B to internet | The counter only adds the model downloader's bytes. It is not a measurement of all traffic | Do not use it as proof. See PITCH.md | ui/StatsBar.kt, review item 4 |

## 10. Not on master yet

| Branch (worktree) | What it adds | Why you care |
|---|---|---|
| worktree-agent-a603d19fc13a9fb9b | `demo` build type, audit log rotation, Stop Ustaad, service locks follow the foreground | The demo build, section 2 |
| worktree-agent-aa03d1aa2341f4949 | PDU review fixes: embedder guard, folders and scans private by default, peer open by notification, on-device dictation only | Index safety, privacy claims |
| worktree-agent-aa47143252c31db53 | Swarm link phase 2 (work in progress): authenticated responses, pairing confirmation, local-address rule, failover, Bluetooth | Trust model, failover |
| worktree-agent-ac34ad0bc1b4ddd46 | MemoryCloud and ExpertMesh screens; Memory tab on master is a placeholder | Demo step 4 |
| worktree-agent-a8395c82c6a8a106b | Scheduler streaming, embeddable throughput panel; the panel is not reachable from the UI on master | Aggregate tokens per second |
