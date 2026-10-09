# SwarmLink G1: in-app probe over same-LAN and an app-created Wi-Fi Direct group

Date: 2026-10-10 IST (+05:30): first probe batch 01:24 to 01:43, soak 01:51 to 02:12, re-measurement with the final binary 02:27 to 02:36. Phones: A = iQOO I2501 `c6f72247`, B = `477684c1`, C = `82d2676d` (build 16.0.x, Android 16). App: `com.swarmos.app.link` (node port 8797, probe port 8798), branch `worktree-agent-aa47143252c31db53`.

Tool: the in-app Kotlin port of `scripts/tput/tput.c` (`link/Tput.kt`), triggered over the signed Node API with `scripts/swarm-call.sh <phone>:8797 POST /v1/link/tput '{"peerNodeId":"...","megabytes":100,"link":"LAN|DIRECT"}'`. Raw TCP on the probe port, 100 MiB in 1 MiB writes, Mbps is receiver-timed (server clock, first byte to EOF, reported back over the same socket). RTT = 200 one-byte ping-pongs with TCP_NODELAY on a fresh connection, taken before the bulk run. Every result is also in `filesDir/tput.jsonl` on the sending phone and returned as JSON.

Link under test:
- LAN = both phones associated to the same AP ("Chulbul", WPA3, 5765 MHz, `Wi-Fi standard 11ac`, PHY 433 Mbps per `dumpsys wifi`). Path = phone, AP, phone, two air hops.
- DIRECT = Wi-Fi Direct autonomous group created by the app on phone A (`DIRECT-sw-c6f72247`, WifiP2pConfig.Builder with 5 GHz band request, known passphrase). B and C joined with `WifiP2pManager.connect` by name and passphrase, no dialog. Interface `p2p0`, GO address 192.168.49.1, clients 192.168.49.175 (B) and 192.168.49.109 (C). B to C traffic crosses the group owner.

## Reading the numbers

- Headline (quiet phones, app in front, screens awake, final binary, section "Re-measurement with the final binary"): DIRECT A and B 305 to 503 Mbps receiver-timed, avg RTT 12 to 20 ms; LAN A and B 96 to 104 Mbps, avg RTT 23 to 26 ms. DIRECT is 3 to 5 times faster than LAN between the same phones.
- The first batch (01:24 to 01:43) was taken while other workers were running heavy jobs on the phones and while the OS froze and thawed the app (see the soak section): LAN 56 to 83 Mbps, DIRECT 93 to 288 Mbps. Those rows are kept because they are what a loaded phone gives; the quiet-phone numbers above are the ones to quote.
- Client to client over DIRECT (B and C) was about half of client to owner in the first batch (107 against 158 to 178 Mbps), because every byte crosses the group owner's radio twice; B was running a heavy workload from another worker during those runs. Not re-measured quiet.
- RTT does not meet the PRD target of 8 ms average: minimum 2.7 to 6 ms on DIRECT and 6 to 12 ms on LAN, but the average is 12 to 26 ms quiet and 30 to 60 ms loaded, p95 24 to 54 ms quiet. The native `scripts/tput` binary run in the same minute on the same two phones (A to B over LAN, first batch, 200 pings, three runs) gave avg 62.3, 54.1 and 55.1 ms (min 6.9 to 12.4, max 137 to 344 ms), so the in-app figure is not an artefact of the Kotlin port; the 2026-10-09 baseline (11 ms avg) was taken on idle phones. Likely cause is Wi-Fi power save on small packets plus the shared channel, not measured here; phase 2 covers it (low-latency lock, keep-alive).
- A native control (A to B over LAN, 200 MB, shell-owned `tput`, 02:3x) ran 13.8 s at 121.7 Mbps without a reset, so the network itself carries long transfers; every failed in-app probe seen during this work (connection reset, read timed out) happened while the peer app was frozen by the OS.
- Heartbeat RTT shown in the peer list (HTTP GET /health once a second, 30 to 280 ms) is worse than the TCP ping-pong because a once-per-second packet lets the radio doze between exchanges. It is a liveness signal, not the link RTT.
- No Wi-Fi standard, PHY speed or channel width is exposed for the p2p interface (see "Wi-Fi Direct result").

## Probe runs (100 MiB each, 200 pings)

"Other workload" is what `top -n 1 -m 3` showed on the three phones right before the batch (CPU out of 800 %), plus thermal status of the sending phone at the end of the run (0 none, 2 moderate, 3 severe, 4 critical). Official numbers must be re-measured on quiet phones.

| Date-time | Phones (from to) | Link | rx Mbps | tx Mbps | s | RTT min / avg / p95 / max ms | Other workload |
|---|---|---|---|---|---|---|---|
| 01:25:20 | A to B | LAN | 72.7 | 72.4 | 11.55 | 11.16 / 49.95 / 97.44 / 139.02 | C ran llama-bench (432 % user); A, B idle |
| 01:25:43 | A to B | LAN | 78.6 | 78.1 | 10.68 | 9.06 / 39.75 / 76.80 / 109.63 | same |
| 01:26:04 | A to B | LAN | 73.2 | 72.8 | 11.46 | 8.35 / 48.99 / 93.87 / 133.07 | same |
| 01:26:26 | B to A | LAN | 71.3 | 71.0 | 11.76 | 5.76 / 34.73 / 73.80 / 112.46 | same |
| 01:26:46 | B to A | LAN | 74.7 | 74.5 | 11.22 | 9.03 / 37.36 / 64.55 / 146.05 | same |
| 01:27:06 | B to A | LAN | 57.6 | 57.4 | 14.57 | 8.77 / 55.65 / 119.78 / 155.87 | same |
| 01:28:52 | A to C | LAN | 65.3 | 65.0 | 12.85 | 7.03 / 60.85 / 122.93 / 205.11 | B 174 % user + 315 % sys (file push), C light |
| 01:29:19 | A to C | LAN | 72.4 | 72.2 | 11.59 | 7.65 / 38.57 / 69.46 / 120.93 | same |
| 01:29:40 | C to A | LAN | 76.9 | 76.8 | 10.91 | 10.48 / 47.48 / 77.50 / 168.54 | same; C thermal 4 |
| 01:30:02 | C to A | LAN | 59.9 | 59.6 | 14.01 | 12.23 / 36.53 / 63.18 / 90.32 | same; C thermal 4 |
| 01:30:24 | B to C | LAN | 59.7 | 59.4 | 14.06 | 9.83 / 50.22 / 100.79 / 137.95 | same; B thermal 2 |
| 01:30:49 | B to C | LAN | 83.3 | 82.7 | 10.07 | 8.44 / 36.28 / 69.50 / 111.98 | same |
| 01:31:08 | C to B | LAN | 71.0 | 70.7 | 11.82 | 9.83 / 43.76 / 80.44 / 239.85 | same; C thermal 4 |
| 01:31:30 | C to B | LAN | 56.5 | 56.4 | 14.86 | 9.43 / 47.52 / 90.48 / 121.07 | same; C thermal 4 |
| 01:34:56 | A to B | DIRECT | 220.7 | 218.6 | 3.80 | 5.49 / 45.80 / 126.59 / 236.22 | B 216 % user; A, C idle |
| 01:35:10 | A to B | DIRECT | 219.7 | 217.7 | 3.82 | 4.81 / 39.06 / 98.48 / 159.62 | same |
| 01:35:23 | A to B | DIRECT | 259.3 | 257.5 | 3.24 | 6.25 / 39.40 / 105.03 / 138.87 | same |
| 01:35:35 | B to A | DIRECT | 288.0 | 284.3 | 2.91 | 3.87 / 30.15 / 74.55 / 161.67 | same; B thermal 3 |
| 01:35:45 | B to A | DIRECT | 221.5 | 218.8 | 3.79 | 5.51 / 47.32 / 115.11 / 195.21 | same; B thermal 3 |
| 01:36:01 | B to A | DIRECT | 226.0 | 225.0 | 3.71 | 5.38 / 51.47 / 134.96 / 250.10 | same; B thermal 3 |
| 01:40:46 | A to C | DIRECT | 148.2 | 147.9 | 5.66 | 4.92 / 31.09 / 82.45 / 144.20 | B 716 % user (another worker); A, C idle |
| 01:41:02 | A to C | DIRECT | 166.6 | 166.1 | 5.03 | 5.48 / 32.55 / 86.40 / 122.20 | same |
| 01:41:14 | C to A | DIRECT | 185.1 | 183.7 | 4.53 | 5.11 / 23.72 / 52.09 / 155.68 | same; C thermal 2 |
| 01:41:25 | C to A | DIRECT | 170.1 | 169.3 | 4.93 | 4.85 / 40.09 / 119.13 / 245.45 | same; C thermal 2 |
| 01:41:40 | B to C | DIRECT | 93.2 | 92.7 | 9.00 | 12.47 / 71.16 / 168.76 / 483.50 | same; B thermal 4 |
| 01:42:06 | B to C | DIRECT | 121.0 | 120.7 | 6.93 | 13.96 / 58.70 / 139.78 / 348.98 | same |
| 01:42:25 | C to B | DIRECT | 93.8 | 92.4 | 8.95 | 11.75 / 60.71 / 148.85 / 403.44 | same; C thermal 2 |
| 01:42:49 | C to B | DIRECT | 120.3 | 119.3 | 6.98 | 10.86 / 77.48 / 179.98 / 365.08 | same |

Means per direction (rx Mbps): LAN A to B 74.8, B to A 67.9, A to C 68.9, C to A 68.4, B to C 71.5, C to B 63.8. DIRECT A to B 233.2, B to A 245.2, A to C 157.4, C to A 177.6, B to C 107.1, C to B 107.1.

An earlier 10 MiB sanity run A to B over LAN (01:24:31): 62.5 Mbps rx, RTT 9.72 / 52.72 / 99.06 / 159.18 ms.

The radio description recorded by the app was empty for LAN runs in the first build (see "Defects found"); the AP link was 11ac, 433 Mbps PHY, 5765 MHz on both ends per `dumpsys wifi` (B at 01:33, A at 01:38), and the final binary records it itself (next table). DIRECT runs recorded interface `p2p0` and frequency 5765 MHz from the WifiP2pGroup.

## Re-measurement with the final binary

Phones A, B and C 604 to 774 % idle in `top` before each batch (no heavy job seen), link app brought to the front every 2 to 3 s by the test script and screens kept awake (a key event every 8 s), apps otherwise untouched. In-app link description read on the phone for LAN: `wlan0`, Wi-Fi standard 11ac, PHY 433 Mbps (tx and rx 433, one rx reading 390), 5765 MHz, RSSI -28 dBm (A) and -23 dBm (B).

| Date-time | Phones (from to) | Link | rx Mbps | tx Mbps | s | RTT min / avg / p95 / max ms | Other workload |
|---|---|---|---|---|---|---|---|
| 02:27:10 | A to B | DIRECT | 502.9 | 500.0 | 1.67 | 4.24 / 16.04 / 36.13 / 94.65 | none seen |
| 02:29:02 | A to B | DIRECT | 481.2 | 478.5 | 1.74 | 4.98 / 20.03 / 39.83 / 602.36 | none seen |
| 02:29:16 | B to A | DIRECT | 451.7 | 449.1 | 1.86 | 3.66 / 12.18 / 24.40 / 37.96 | none seen |
| 02:35:06 | A to B | LAN | 103.7 | 103.5 | 8.09 | 7.15 / 25.77 / 49.19 / 95.72 | none seen |
| 02:35:19 | A to B | LAN | 98.2 | 97.6 | 8.54 | 7.04 / 23.35 / 42.05 / 76.92 | none seen |
| 02:35:33 | B to A | LAN | 96.5 | 96.2 | 8.69 | 7.68 / 26.27 / 52.00 / 138.95 | none seen |
| 02:35:48 | B to A | LAN | 102.6 | 102.3 | 8.18 | 6.70 / 26.13 / 54.22 / 105.01 | none seen |
| 02:36:04 | A to B | DIRECT | 415.9 | 414.8 | 2.02 | 2.74 / 12.53 / 28.86 / 138.58 | none seen |
| 02:36:09 | A to B | DIRECT | 305.0 | 304.2 | 2.75 | 3.64 / 20.33 / 49.87 / 159.46 | none seen |
| 02:36:16 | B to A | DIRECT | 452.5 | 449.4 | 1.85 | 3.79 / 12.45 / 27.38 / 69.31 | none seen |
| 02:36:21 | B to A | DIRECT | 359.2 | 356.7 | 2.34 | 3.52 / 13.35 / 34.21 / 86.47 | none seen |

LAN 100 MiB between A and B also ran at 85.3 and 91.8 Mbps (02:34, kept-awake window, no RTT stage change). Not reproduced quiet: DIRECT B to C and C to B, LAN C pairs (C and B were busy with other workers' jobs earlier; still to be re-measured by the orchestrator on quiet phones).

## Wi-Fi Direct result

| Item | Result | How measured |
|---|---|---|
| Group | Autonomous GO on A, SSID `DIRECT-sw-c6f72247`, 5 GHz requested through `setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` | `/v1/link/direct` on A, `dumpsys wifip2p` |
| Band and channel | 5 GHz, 5765 MHz (channel 153), the same channel the infrastructure STA uses (`sta freq=5765`), i.e. single-channel concurrency: the two Wi-Fi roles share one radio and one channel | `dumpsys wifip2p` on A and B (`channelFrequency=5765`, `sta freq=5765`) |
| Channel width | Not exposed (not in `dumpsys wifi`, `dumpsys wifip2p`, WifiP2pGroup; `/sys/class/net/p2p0/*` is permission denied to the shell user) | looked for, not found |
| Wi-Fi standard and PHY speed of p2p0 | Not exposed by any API or dump available without root. The STA side stays 11ac, 433 Mbps. The measured ceiling is 288 Mbps receiver-timed at 3 to 4 times the LAN figure | same |
| STA + P2P concurrency | Yes. `dumpsys wifi`: `Connection state: STA=true, P2P=true`; wlan0 kept 192.168.242.x with p2p0 192.168.49.x beside it on all three phones; wireless adb to all three never dropped while the group formed, while B and C joined and during every DIRECT probe | `adb devices` after each step, `ip -4 addr` on A |
| Formation time | Host: createGroup to group and IPv4 up 503 ms (framework `durationTakenToConnectMillis=264`). Join by credentials: B 1341 ms, C 1755 ms (framework value on B 1093 ms) | in-app timer from the call to p2p0 holding an address |
| Invitation dialog | None on either side; `WifiP2pManager.connect` with network name plus passphrase joined directly | observed, no tap issued |
| Group survives app restart | Yes. After reinstalling the app on all three phones the system group and the DIRECT paths stayed up; the app picks the group up again at start (`DirectLink.refresh`) | peers listing after reinstall |

## Discovery, heartbeat, pairing

| Check | Date-time | Result |
|---|---|---|
| Discovery on a shared LAN | 01:19 after install and naming; cold start 02:13 | A, B and C each listed the other two as online with no user action (`GET /v1/link/peers` on each). Cold start: force-stop on all three, start all three at once, poll until each lists two online peers: C 3.7 s, A 4.6 s, B 5.3 s (required: within 15 s). NSD service type `_swarmos._tcp`, hello then signed health. |
| Force-stop B | 01:2x | A marked B away 2219 ms after the force-stop, C 2147 ms (3 missed 1 Hz heartbeats; times are from each observer's own logcat, corrected by measured clock offsets). Required: within 5 s. |
| Restart B | 01:2x | A and C had B online again 996 ms and 979 ms after `am start`. Required: within 10 s. |
| Deep-link pairing | 01:2x | A `POST /v1/link/host` rotated its secret; the payload from the debug route `GET /v1/link/pairing-payload` went to B as `swarmos://pair?d=...` over adb. B adopted it (secret fingerprint 4146 on both) and A listed B online; C, still on the old secret, went away on A and B, then returned after the same injection. A request signed with the old secret got HTTP 401 `bad signature or stale timestamp`. All three were put back on the dev secret with a payload that carries it. |
| QR | 01:3x | ZXing encodes the payload (316 characters) and ML Kit decodes the bitmap back to the identical text (`GET /v1/link/qr-selftest`: `ok:true, decodedMatches:true`). The camera scan itself was not exercised (nobody can aim a phone). |

## Soak, screen off on B and C

Run 01:51:15 to 02:12:05 IST (20.8 minutes), link app with the soak counters (`beats`, `missesTotal`, `awayCount`, `maxGapMs` per path in `GET /v1/link/peers`), foreground service running on all three, no battery or whitelist setting touched. A was the observer (screen kept awake); B and C were put to sleep with a key event at the start and my wake loop was stopped for them. Per minute the script logged A's counters and, on B and C, the `dumpsys power` flag of the app's partial wake lock (full log kept as `soak.log` on the PC, key lines below).

Screen-off for 20 minutes could not be held: other workers' tooling woke B and C repeatedly on these shared phones (B woke at 01:52:39, C at 01:54:49 and 02:01:47 according to `mLastWakeTime`). What the run does show:

| Check | Result |
|---|---|
| Process killed by the system | No. pids unchanged over the run (B 31494, C 8751, before and after); logcat had no kill, died or ANR line for the package (only Play Protect scan lines from the install). |
| Process frozen by the system | Yes. `dumpsys power` shows the app's `swarmos:service` wake lock as `DISABLED ... mIsFrozen` in these windows: B with the screen asleep 01:50:41 to 01:52:39 (frozen at 01:52:26, thawed at 01:52:40 when the screen woke); B with the screen on, about 02:07:35 to 02:12:05 (`mLastSleepTime` shows no sleep after 01:50:41, so this was not screen-off; the foreground app was not logged during the run, most likely another worker's app was in front); C frozen 01:51 to about 01:54:45, about 01:58 to 02:01:47 and about 02:07:30 to 02:12:05, thawed each time it was woken or brought to the front. The foreground service, the partial wake lock and the Wi-Fi low-latency lock did not prevent it. |
| Effect on the link | While a phone was frozen A counted consecutive misses (22 to 265) and marked the paths away within 3 s; the paths were back 0.6 s after the app was brought to the front (A saw B online with age 614 ms after the `am start` at 02:12:05). A probe that started while the peer was frozen ended in `Connection reset` or `Read timed out`. |
| Counters at A, start of run to end (01:51:15 to 02:12:14) | B LAN: beats 121 to 1029, missesTotal 26 to 370, away transitions 2 to 4, longest gap 24.2 s to 270.7 s. C LAN: beats 120 to 682, missesTotal 27 to 714, away 2 to 5, longest gap 24.5 s to 294.3 s (DIRECT paths within 3 counts of LAN). All misses fall inside the frozen windows. |
| Uninterrupted stretch | B stayed online with zero new misses from 01:53:30 to 02:07:30 (missesTotal constant at 103, beats 179 to 982, 1 Hz): about 14 minutes with the app in front and the screen on. C had two stretches of 3 to 5 minutes with zero new misses. |

Settings that would be needed so the link survives screen-off and another app in front (inference from the freezer flag, not tried, nothing changed): OriginOS battery settings for the SwarmOS package set to allow high background power consumption, auto-start allowed, battery optimisation exempt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt, or `dumpsys deviceidle whitelist +<package>` over adb), and the app card locked in Recents. Re-run this soak after setting them; the pass criterion is zero away transitions with the screen off for 20 minutes.

During the whole session the freeze also hit every other worker's variant on these phones, so any measurement that needs two apps to talk must keep both in front and awake (what the test scripts here do).

## Defects found while measuring

- WifiInfo redaction: `WifiInfo.getNetworkId()` is -1 for an app that is not in the foreground with location access, so the first build recorded no Wi-Fi standard, PHY speed or frequency for LAN runs. The probe now treats a positive frequency or link speed as "associated" and the final binary records them (see the re-measurement table). `core/HealthMonitor.kt` (not mine) has the same test `wi.networkId != -1 && wi.linkSpeed > 0` in `connected`; the StatsBar link cell goes blank while the app is in the background. Drop the networkId part there.
- The OS freezes the app: see the soak section. It is the dominant source of flaky results on these phones.
- Wi-Fi Aware: the framework lists `WIFI_FEATURE_AWARE` among the supported features in `dumpsys wifi` on phone A even though the 2026-10-09 probe found no `android.hardware.wifi.aware` feature flag. Not used, not tested; noted only because it contradicts the PRD premise.
