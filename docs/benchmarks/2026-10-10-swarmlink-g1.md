# SwarmLink G1: in-app probe over same-LAN and an app-created Wi-Fi Direct group

Date: 2026-10-10, 01:24 to 01:43 IST (+05:30), soak 01:5x to 02:1x. Phones: A = iQOO I2501 `c6f72247`, B = `477684c1`, C = `82d2676d` (build 16.0.x, Android 16). App: `com.swarmos.app.link` (node port 8797, probe port 8798), branch `worktree-agent-aa47143252c31db53`.

Tool: the in-app Kotlin port of `scripts/tput/tput.c` (`link/Tput.kt`), triggered over the signed Node API with `scripts/swarm-call.sh <phone>:8797 POST /v1/link/tput '{"peerNodeId":"...","megabytes":100,"link":"LAN|DIRECT"}'`. Raw TCP on the probe port, 100 MiB in 1 MiB writes, Mbps is receiver-timed (server clock, first byte to EOF, reported back over the same socket). RTT = 200 one-byte ping-pongs with TCP_NODELAY on a fresh connection, taken before the bulk run. Every result is also in `filesDir/tput.jsonl` on the sending phone and returned as JSON.

Link under test:
- LAN = both phones associated to the same AP ("Chulbul", WPA3, 5765 MHz, `Wi-Fi standard 11ac`, PHY 433 Mbps per `dumpsys wifi`). Path = phone, AP, phone, two air hops.
- DIRECT = Wi-Fi Direct autonomous group created by the app on phone A (`DIRECT-sw-c6f72247`, WifiP2pConfig.Builder with 5 GHz band request, known passphrase). B and C joined with `WifiP2pManager.connect` by name and passphrase, no dialog. Interface `p2p0`, GO address 192.168.49.1, clients 192.168.49.175 (B) and 192.168.49.109 (C). B to C traffic crosses the group owner.

## Reading the numbers

- DIRECT is 3 to 4 times faster than LAN between the same phones: mean A to B 233 Mbps against 75 Mbps, B to A 245 against 68. All LAN pairs sit at 56 to 83 Mbps, in line with the 2026-10-09 baseline (50 to 61 Mbps, native tool).
- Client to client over DIRECT (B and C) is about half of client to owner (107 against 158 to 178 Mbps), because every byte crosses the group owner's radio twice. B was also running a heavy workload from another worker during those runs (see column "other workload").
- RTT is the weak point and does not meet the PRD target of 8 ms average: minimum 4 to 6 ms on DIRECT and 6 to 12 ms on LAN, but the average is 30 to 60 ms with a p95 of 60 to 180 ms. The native `scripts/tput` binary run in the same minute on the same two phones (A to B over LAN, 01:3x, 200 pings each, three runs) gave avg 62.3, 54.1 and 55.1 ms (min 6.9 to 12.4, max 137 to 344 ms), so the in-app figure is not an artefact of the Kotlin port; the 2026-10-09 baseline (11 ms avg) was taken on idle phones. Likely cause is Wi-Fi power save on isolated small packets plus the shared channel, not measured here; phase 2 covers it (low-latency lock, keep-alive).
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

Radio description recorded by the app with each LAN run was empty in this first build (see "Defects found"); the AP link was 11ac, 433 Mbps PHY, 5765 MHz on both ends per `dumpsys wifi` (B at 01:33, A at 01:38). DIRECT runs recorded interface `p2p0` and frequency 5765 MHz from the WifiP2pGroup.

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
| Discovery on a shared LAN | 01:19 after install and naming | A, B and C each listed the other two as online with no user action (`GET /v1/link/peers` on each). NSD service type `_swarmos._tcp`, hello then signed health. |
| Force-stop B | 01:2x | A marked B away 2219 ms after the force-stop, C 2147 ms (3 missed 1 Hz heartbeats; times are from each observer's own logcat, corrected by measured clock offsets). Required: within 5 s. |
| Restart B | 01:2x | A and C had B online again 996 ms and 979 ms after `am start`. Required: within 10 s. |
| Deep-link pairing | 01:2x | A `POST /v1/link/host` rotated its secret; the payload from the debug route `GET /v1/link/pairing-payload` went to B as `swarmos://pair?d=...` over adb. B adopted it (secret fingerprint 4146 on both) and A listed B online; C, still on the old secret, went away on A and B, then returned after the same injection. A request signed with the old secret got HTTP 401 `bad signature or stale timestamp`. All three were put back on the dev secret with a payload that carries it. |
| QR | 01:3x | ZXing encodes the payload (316 characters) and ML Kit decodes the bitmap back to the identical text (`GET /v1/link/qr-selftest`: `ok:true, decodedMatches:true`). The camera scan itself was not exercised (nobody can aim a phone). |

## Soak, screen off on B and C

SOAK_PLACEHOLDER

## Defects found while measuring

- Heartbeat and WifiInfo: `WifiInfo.getNetworkId()` is redacted to -1 while the app is not in the foreground with location access, so `HealthMonitor` (core, not mine) shows no link standard and the first build of the probe recorded no Wi-Fi standard, PHY speed or frequency for LAN runs. The probe now treats a positive frequency or link speed as "associated". Core fix needed in `core/HealthMonitor.kt`: drop `wi.networkId != -1` from `connected`.
- Phones sleep the app: see the soak section.
