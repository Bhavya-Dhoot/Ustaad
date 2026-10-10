# SwarmLink phase 2: paths, round trip, striped bulk, Bluetooth, failover

Date 2026-10-10 IST (+05:30), 03:12 to 06:21. Phones: iQOO I2501, A (`c6f72247`) and C (`82d2676d`). **Phone B was reserved for the founder from 03:40 and received no command, so every pair below is A and C only.** A was charging at 100 % for the whole session and ran at Thermal Status 1 to 3; C ran 0 to 3 (3 while another worker loaded a model at 03:5x). The thermal value is recorded beside each number. Another worker's inference jobs ran on both phones at times (marker file `base-id-owner.txt` = memcloud, `librpc-server.so` on A bound to 192.168.49.1, `libllama-server.so` on C); no timed run below was taken while a `llama` or `rpc-server` process was running unless the row says so. App: `com.swarmos.app.link` (node port 8797, probe port 8798), debug build, both phones on the same secret, screens kept awake and the app in front by a script (a key event and `am start` every 3 s).

**Transport trust model.** Peer traffic is authenticated with an HMAC (requests, and now responses, are signed with the pairing secret) but it is not encrypted, so on someone else's Wi-Fi a sniffer can read the questions and snippets that cross the LAN path. On the app's own Wi-Fi Direct group the group's WPA2 key protects the traffic, and Bluetooth carries only small control messages signed the same way, with the Wi-Fi Direct credentials sealed (AES-256-GCM) under the pairing secret.

Tools: `POST /v1/link/rtt`, `POST /v1/link/tput`, `GET /v1/link/paths`, `POST /v1/link/failover-test`, `POST /v1/link/ble-bench`, all signed, driven from the PC (`scripts/swarm-call.sh` or the Python equivalent). Throughput is receiver-timed (first byte to last byte on the receiving phone, reported back over the same connection). Raw = the phase-1 probe (`streams` 0). Striped = chunks of 1 MiB with index and CRC32, N TCP streams, reassembled in order on the receiver; "CRC bad / missing" must be 0 / 0 and always was.

## 1. The finding that matters most: the Wi-Fi Direct group must sit on the station's channel

These phones have one Wi-Fi radio path for station and Wi-Fi Direct together (`dumpsys wifi`: `STA=true, P2P=true`, `mIsDbs: false`). When the group owner is on another channel than the station, the radio time-slices between the two channels (multi-channel concurrency) and the link collapses:

| Group channel | Station channel | Time (A to C, DIRECT) | Result |
|---|---|---|---|
| 5220 MHz (chosen by the framework from `setGroupOperatingBand(5 GHz)`) | 5765 | 04:22:49, 100 MiB raw | **10.3 Mbps**; heartbeat round trip EWMA 300 to 670 ms |
| 5765 MHz (`setGroupOperatingFrequency` = the station's frequency) | 5765 | 04:34:52, 100 MiB raw | **426 Mbps**; TCP echo RTT 2.6 / 8.3 / 17.3 / 68.5 ms |
| 5765 MHz, later, quiet | 5765 | 05:09 to 05:45 (section 3) | 650 to 960 Mbps |

`dumpsys wifip2p` showed `channelFrequency=5785` at 03:06 and `5220` at 03:10 for the same group name; the framework picks the channel itself. Phase 1 saw 5765 by luck. `DirectLink.host()` now asks for the station's own 5 GHz channel by default (`WifiP2pConfig.Builder.setGroupOperatingFrequency`), falls back to the plain band request if the radio refuses, and records requested against negotiated frequency in `GET /v1/link/direct` (`requestedMhz`, `frequencyMhz`). On 04:34 the group on A was re-created for this test (about 5 s of Direct outage; C re-joined 6.1 s later).

6 GHz: not available. Country code `IN`; `dumpsys wifi` lists `SupportedChannelListIn6g[]` empty and the 5 GHz list as 36 to 48, 149 to 165 and the DFS channels. Not tried.

Shared radio, one channel (answer to "two radios or one"): station and group were both on 5765 MHz, so it is one radio and one channel. LAN traffic goes phone to AP to phone (two air hops on that channel), Wi-Fi Direct goes straight (one hop), which is why the same 5 GHz channel gives about 90 Mbps via the AP and 650 to 960 Mbps direct. Channel width and PHY rate of `p2p0` are not exposed without root; the station reports 11ac, 390 to 433 Mbps; the p2p link carried up to 959 Mbps, so it negotiated at least a 2x2 VHT80 rate.

## 2. Latency (step 3): the Wi-Fi lock does not change the round trip; the channel mismatch and the software stack do

Round trip A to C, 200 requests back to back unless a gap is given; min / avg / p95 / max ms. HTTP = signed `GET /v1/health` through the Node API (what a request costs); TCP = 200 one-byte ping-pongs on a fresh connection with TCP_NODELAY (the link). Group on 5765 MHz (matched channel), A thermal 1, C thermal 1, 04:36 to 04:42.

| Time | Wi-Fi lock | Link | HTTP back to back | HTTP at 1 Hz (30) | TCP |
|---|---|---|---|---|---|
| 04:36:56 | none | LAN | 14.5 / 62.6 / 235.8 / 407.7 | 21.5 / 112.7 / 305.4 / 1035 | 5.3 / 9.7 / 17.3 / 56.0 |
| 04:37:36 | none | DIRECT | 10.9 / 28.6 / 77.9 / 290 | 15.0 / 57.9 / 125.0 / 134 | 4.0 / 6.4 / 10.0 / 14.5 |
| 04:38:21 | low latency | LAN | 14.6 / 37.2 / 39.8 / 2056 | 20.2 / 31.8 / 41.5 / 53.5 | 5.2 / 9.5 / 14.9 / 36.3 |
| 04:38:57 | low latency | DIRECT | 9.7 / 21.0 / 29.8 / 50.3 | 15.1 / 55.4 / 134 / 139 | 3.0 / 7.4 / 11.9 / 71.0 |
| 04:39:40 | none | LAN | 14.2 / 27.8 / 39.3 / 73.8 | 20.1 / 70.0 / 116.9 / 1058 | 5.6 / 9.9 / 12.3 / 228 |
| 04:40:18 | none | DIRECT | 11.0 / 21.8 / 29.8 / 54.8 | 16.5 / 46.6 / 123.5 / 139 | 4.0 / 6.7 / 10.7 / 35.8 |
| 04:41:00 | low latency | LAN | 16.3 / 27.1 / 36.6 / 60.6 | 22.1 / 99.6 / 65.4 / 2080 | 5.3 / 9.0 / 12.8 / 43.0 |
| 04:41:38 | low latency | DIRECT | 10.8 / 20.1 / 28.5 / 37.6 | 15.4 / 42.3 / 123.8 / 135.6 | 2.6 / 6.3 / 10.0 / 13.8 |

Reading:
- **Lock none against low latency: no consistent difference** (DIRECT TCP avg 6.4 / 6.7 without, 7.4 / 6.3 with; LAN 9.7 / 9.9 without, 9.5 / 9.0 with). `WifiManager.WIFI_MODE_FULL_HIGH_PERF` is treated as low latency by Android 10 and later (`dumpsys wifi` shows `type=4` for both and `Current operation mode: 4`; with the lock released it shows `0`), so "high perf" is not a third condition. An ICMP check from the PC to C at 5 Hz with the lock released and held (`Current operation mode: 0` and `4`): 03:23 to 03:25, avg 32.1 / 30.6 / 15.1 / 19.7 ms, i.e. run to run noise larger than any lock effect. Policy kept (low latency while the app is in front with a swarm, high perf otherwise, owned by `link/WifiLocks`) because it costs nothing measurable and holds the radio awake for the screen-off case; it is not what fixes the round trip.
- The big levers were the matched channel (section 1: avg TCP echo 37 to 60 ms with the group on another channel, 6.3 to 8.3 ms on the same one) and the software: a signed HTTP request over the Node API costs about 3.6 to 3.9 ms on one phone with no radio (loopback probe: `POST /v1/link/rtt` with the phone's own node id, 03:5x, HTTP 3.00 / 3.60 / 4.28 / 15.2 ms on A, 3.37 / 3.92 / 4.89 / 7.08 on C, TCP echo 0.04 ms), and 7 to 14 ms at one request a second (CPU wake up), so the HTTP round trip is about 3 times the TCP round trip.
- Target of 8 ms average: met by the TCP link on DIRECT (6.3 to 8.3 ms); not met by LAN (9.0 to 9.9 ms) or by a Node API request (20 to 28 ms on DIRECT, 27 to 62 ms on LAN).
- Idle gap effect on DIRECT: at C thermal 3 (04:42 to 04:46) the HTTP round trip grew from 20 ms back to back to 51 to 64 ms at 0.5 to 1 s gaps; at thermal 0 to 1 (04:59 to 05:02) the same probe gave 21.4 / 19.4 ms at 1 s / 0.5 s gaps. A keep-warm echo ping every 100 ms while requests flow was built and measured at thermal 0 to 1: HTTP avg 22.1 / 20.4 / 24.9 / 22.0 ms with it against 21.4 / 19.4 / 21.3 / 22.3 without, so no benefit; **removed**. The earlier gap effect was the hot phone, not the radio sleeping.
- Heartbeat round trip shown in the peer chips (HTTP GET every second): DIRECT 19 to 27 ms, LAN 25 to 42 ms, BT 36 to 55 ms on quiet phones (05:46 to 05:47).

## 3. Bulk throughput (step 4): striping barely matters on DIRECT, nothing helps on LAN, multipath loses

200 MiB per run, A to C and C to A, group on 5765 MHz. "raw" is one stream without framing. Buffer = `SO_SNDBUF` and `SO_RCVBUF` on both phones (`POST /v1/link/tuning`).

| Time | Link | Dir | Streams | Buffer | rx Mbps | tx Mbps | s | CRC bad / missing | Thermal (sender at end / A, C before) |
|---|---|---|---|---|---|---|---|---|---|
| 05:21:58 | DIRECT | A to C | raw | default | 660.7 | 658.6 | 2.54 | None / None | 1 / A 1, C 0 |
| 05:22:53 | DIRECT | A to C | 1 | default | 772.3 | 754.2 | 2.17 | 0 / 0 | 1 / A 1, C 0 |
| 05:23:48 | DIRECT | A to C | 2 | default | 633.7 | 600.6 | 2.65 | 0 / 0 | 1 / A 1, C 0 |
| 05:24:43 | DIRECT | A to C | 4 | default | 836.3 | 811.8 | 2.01 | 0 / 0 | 1 / A 1, C 0 |
| 05:25:37 | DIRECT | A to C | 8 | default | 824.6 | 786.5 | 2.03 | 0 / 0 | 1 / A 1, C 0 |
| 05:26:31 | DIRECT | C to A | raw | default | 724.9 | 722.4 | 2.31 | None / None | 0 / A 1, C 0 |
| 05:27:25 | DIRECT | C to A | 1 | default | 762.5 | 746.5 | 2.20 | 0 / 0 | 0 / A 2, C 0 |
| 05:28:18 | DIRECT | C to A | 2 | default | 891.0 | 857.8 | 1.88 | 0 / 0 | 0 / A 2, C 0 |
| 05:29:11 | DIRECT | C to A | 4 | default | 848.0 | 824.3 | 1.98 | 0 / 0 | 0 / A 2, C 0 |
| 05:30:04 | DIRECT | C to A | 8 | default | 922.1 | 875.5 | 1.82 | 0 / 0 | 0 / A 3, C 0 |
| 05:30:58 | DIRECT | A to C | raw | 1024 KB | 640.7 | 639.3 | 2.62 | None / None | 3 / A 3, C 0 |
| 05:31:48 | DIRECT | A to C | 1 | 1024 KB | 710.4 | 698.2 | 2.36 | 0 / 0 | 3 / A 3, C 1 |
| 05:32:11 | DIRECT | A to C | 4 | 1024 KB | 866.3 | 840.4 | 1.94 | 0 / 0 | 3 / A 3, C 0 |
| 05:32:34 | DIRECT | C to A | 1 | 1024 KB | 695.2 | 682.3 | 2.41 | 0 / 0 | 0 / A 3, C 0 |
| 05:32:57 | DIRECT | C to A | 4 | 1024 KB | 862.5 | 830.8 | 1.95 | 0 / 0 | 0 / A 3, C 0 |
| 05:33:20 | DIRECT | A to C | 1 | 4096 KB | 814.5 | 796.0 | 2.06 | 0 / 0 | 3 / A 3, C 0 |
| 05:33:43 | DIRECT | A to C | 4 | 4096 KB | 803.6 | 779.9 | 2.09 | 0 / 0 | 3 / A 3, C 0 |
| 05:34:06 | DIRECT | C to A | 1 | 4096 KB | 668.8 | 655.4 | 2.51 | 0 / 0 | 0 / A 3, C 0 |
| 05:34:29 | DIRECT | C to A | 4 | 4096 KB | 792.9 | 768.3 | 2.12 | 0 / 0 | 0 / A 3, C 0 |
| 05:35:09 | LAN | A to C | raw | default | 102.2 | 102.1 | 16.42 | None / None | 3 / A 2, C 0 |
| 05:35:58 | LAN | A to C | 1 | default | 60.0 | 59.6 | 27.96 | 0 / 0 | 2 / A 2, C 0 |
| 05:36:38 | LAN | A to C | 4 | default | 87.1 | 84.4 | 19.25 | 0 / 0 | 2 / A 2, C 0 |
| 05:37:16 | LAN | C to A | raw | default | 95.1 | 95.0 | 17.64 | None / None | 0 / A 2, C 0 |
| 05:37:58 | LAN | C to A | 1 | default | 78.8 | 78.2 | 21.30 | 0 / 0 | 0 / A 2, C 0 |
| 05:38:38 | LAN | C to A | 4 | default | 93.1 | 91.0 | 18.02 | 0 / 0 | 0 / A 2, C 0 |
| 05:43:17 | LAN | A to C | 1 | 1024 KB | 82.9 | 82.2 | 20.24 | 0 / 0 | 1 / A 1, C 0 |
| 05:39:18 | LAN | A to C | raw | 4096 KB | 89.2 | 89.1 | 18.81 | None / None | 2 / A 2, C 0 |
| 05:39:56 | LAN | A to C | 1 | 4096 KB | 99.6 | 98.6 | 16.84 | 0 / 0 | 2 / A 2, C 0 |
| 05:40:35 | LAN | A to C | 4 | 4096 KB | 96.6 | 92.8 | 17.36 | 0 / 0 | 2 / A 2, C 0 |
| 05:41:13 | LAN | C to A | raw | 4096 KB | 92.7 | 92.6 | 18.11 | None / None | 0 / A 1, C 0 |
| 05:41:53 | LAN | C to A | 1 | 4096 KB | 93.4 | 92.3 | 17.96 | 0 / 0 | 0 / A 1, C 0 |
| 05:42:33 | LAN | C to A | 4 | 4096 KB | 91.1 | 89.0 | 18.42 | 0 / 0 | 0 / A 1, C 0 |

Reading: on DIRECT every striped run with 4 or 8 streams is 790 to 920 Mbps against 640 to 725 raw and 670 to 815 with one stream (4 streams beat 1 stream in 5 of 6 pairs, by 8 to 24 %, and was 1 % lower in the sixth, 4 MB buffer A to C; 2 streams is not consistently better than 1: 634 against 772 A to C, 891 against 762 C to A). On LAN raw is 89 to 102 Mbps and striping gives 60 to 99, so nothing is gained there. Buffers of 1 MB and 4 MB (rows with a size) change nothing consistent: LAN 4 MB single stream 99.6 and 93.4 against 60.0 and 78.8 default is the one visible gain, but raw and 4 streams show none. Thermal status of the sender was 0 to 3 across the matrix (last column); no row shows a clear thermal penalty.

Multipath (`paths:["LAN","DIRECT"]`, streams dealt to both paths, chunks taken by whichever stream is free), 200 MiB, two rounds, 05:43 to 05:45, thermal A 1 / C 0:

| Direction | DIRECT alone, 4 streams | LAN alone, 4 streams | LAN+DIRECT, 4 streams | LAN+DIRECT, 8 streams | DIRECT alone, 8 streams |
|---|---|---|---|---|---|
| A to C round 1 | 850 | 88 | 318 (LAN 44, DIRECT 273) | 354 (LAN 67, DIRECT 287) | 913 |
| C to A round 1 | 959 | 93 | 459 (LAN 46, DIRECT 413) | 317 (LAN 60, DIRECT 257) | 892 |
| A to C round 2 | 762 | 89 | 382 (LAN 40, DIRECT 341) | 284 (LAN 64, DIRECT 220) | 897 |
| C to A round 2 | 880 | 93 | 465 (LAN 44, DIRECT 421) | 280 (LAN 63, DIRECT 217) | 951 |

Multipath is **below** the best single path in every run (0.3 to 0.5 times), not 15 % above it, because LAN and DIRECT share one radio and one channel (section 1) and the in-order reassembly lets the slow path hold the fast one back. **Decision: bulk uses the best single path** (`PathPolicy.order(..., Use.BULK)`, measured Mbps, Wi-Fi Direct before LAN until measured; Bluetooth never). `bestBulkAddress(peerNodeId)` returns that path's IP. The multipath option stays in the probe (`paths`) as a measurement, not as a default.

Chosen defaults and why: DIRECT, 4 streams, 1 MiB chunks, system buffer size (best or equal in 5 of 6 comparisons, 790 to 960 Mbps; 1 stream is 640 to 810, buffers of 1 MB and 4 MB give no consistent gain); LAN, single stream, system buffers (raw 89 to 102 Mbps; striping gave 60 to 99, buffers 83 to 100: AP limited, nothing to gain). The Mbps floor of the baseline (55 Mbps over the AP, 2026-10-09) is met on LAN; the 150 Mbps target is met 4 to 6 times over on DIRECT.

## 4. Consistency (step 2): request ids, replay on the server, retry on the next path

- Every request carries `X-Swarm-Req`; the server keeps completed responses for 5 minutes (256 entries, 32 MB) and answers a duplicate with the stored response (`X-Swarm-Replay: 1`) instead of running the handler again; a duplicate that arrives while the first is still running waits for it. JVM tests: `RequestDedupTest`, `FailoverTest` (path 1 fails before the server sees it, path 2 runs once; path 1 runs the request and loses the answer, path 2 gets the stored answer, executed once; application errors are final; all paths failing reports every cause).
- On the phones (`checks.py`, 05:xx): the same id sent twice to `/v1/link/sleep` ran the handler once (`executions` +1) and the second answer carried `X-Swarm-Replay: 1` and a valid response MAC.
- A 120 s request survives: `POST /v1/link/sleep {"ms":120000}` to C returned HTTP 200 after 121.1 s (the CIO default idle timeout of 45 s is now 3600 s on the server; `NodeClient` takes a per-request `timeoutMs` on every `get` and `post`).

Failover on the phones (`POST /v1/link/failover-test` on A towards C: 700 signed requests, one every 100 ms, each with its own id; C counts how often a handler really ran). **The Wi-Fi radio was not switched off in this test**: phone C was not on USB, and switching its Wi-Fi off over wireless adb would have cut the connection that turns it back on. Instead C's debug fault hook (`POST /v1/link/fault`) holds every request that arrives from one source address for 20 s, which is what a path that has gone silent looks like to the sender (the heartbeat and the requests time out; the radios stay up). The Bluetooth radio in the second step is real. A real Wi-Fi-off window is in section 8.

| Time (A thermal 2, C thermal 0) | What was done to C | What A saw |
|---|---|---|
| 05:55:56 | holds requests from 192.168.49.1 (DIRECT silent) | DIRECT `away` 4.1 s later; small requests moved to LAN |
| 05:56:05 | also holds requests from 192.168.242.89 (LAN silent) | LAN `away` 4.1 s later; Bluetooth the only path (`small` = BT) |
| 05:56:16 | signed 900 byte echo over Bluetooth with both Wi-Fi paths silent | `GET` avg 42.4 ms, echo avg 54.4 ms, 265 kbit/s |
| 05:56:20 | fault cleared | LAN and DIRECT `online` again 1.0 s later; small requests back on DIRECT |
| 05:57:34 | request loop finished | **sent 700, ok 700, failed 0, executions on C 700** (nothing lost, nothing run twice), 2 path failovers; slowest requests 3754 ms (#43), 3733 ms (#80), 1062 ms (#394) |

Two requests took 3.7 s and the loop reports 2 path failovers; I read them as the requests that were in flight when DIRECT, then LAN, went silent (not checked request by request): they are cancelled when the path is declared away (4.1 s) and sent again with the same id on the next path, and the executions counter shows each ran once. #394 (1.06 s) did not need a failover.

## 5. Bluetooth (step 6)

Design: both phones advertise a 9 byte service-data record (node id 4, tag 4 = HMAC of the pairing secret and the minute, flags 1) and scan for it; a phone that is not paired cannot be recognised and an unpaired scanner learns nothing. The phone with the smaller node id opens a GATT connection to the other (MTU 247, connection priority high); both then speak the same signed requests over two characteristics (write and notify), 2 byte length framing, and the receiver replays the request against its own node server on loopback, so signature check, dedup and handlers are the ones used over Wi-Fi. Wi-Fi Direct credentials are fetched with `GET /v1/link/p2p-creds` (sealed with AES-256-GCM under the pairing secret, bound to the owner's node id) over whichever path is up, so the group can form with no QR. Bluetooth never carries bulk data; bodies are limited to 1 KB and answers to 8 KB. Both phones needed Bluetooth switched on (`svc bluetooth enable`, see "What I changed").

| Measurement (04:08, A and C, 60 requests each, BT path, Wi-Fi up) | Result |
|---|---|
| Signed `GET /v1/health` round trip A to C | 26.2 / 39.1 / 47.4 / 59.0 ms (min / avg / p95 / max) |
| Signed `GET /v1/health` round trip C to A | 26.0 / 39.2 / 47.4 / 54.4 ms |
| Signed `POST /v1/link/echo` with a 900 byte body, A to C | 43.2 / 53.3 / 70.9 / 74.0 ms; 270 kbit/s counting request and answer |
| Same, C to A | 30.9 / 56.4 / 73.7 / 85.6 ms; 255 kbit/s |
| Time from both apps starting to the BT path listed online | within 12 s, no tap, both directions |

Bluetooth as the only path: the 05:56:10 line above (LAN away, DIRECT away, BT online, `small` = BT) and the 900 byte echo at 05:56:16 are that case, with both Wi-Fi paths silenced by the fault hook and the Bluetooth radio real. A phone with its Wi-Fi really off was not tested (see above). Bodies over 1 KB are not sent over Bluetooth and bulk is never routed over it.

`GET /v1/link/ble` at 06:10 on both phones: adapter on, advertising and scanning, the peer in `seen`. Open: the BT path is listed `online` at some moments with Wi-Fi healthy (05:55, 06:15) and missing at others (06:10 to 06:13, 06:16 to 06:18); I did not find out why, and it never affected the Wi-Fi paths.

## 6. Wi-Fi Direct tuning and re-forming (step 5)

- **5 GHz on the station's channel by default, 6 GHz not available** (section 1). The group's frequency asked for, and the one negotiated, are both in `GET /v1/link/direct` (`requestedMhz`, `frequencyMhz`).
- **Group removed from under the app** (06:04:17, phone C, the debug hook `POST /v1/link/direct/drop` removes the group at the system level and changes nothing else; no Wi-Fi switch): the state left `up` after 1.1 s (`phase=starting`), and the group was **re-formed 2.2 s after the removal with no QR and no tap** (join 630 ms, 5765 MHz) from the stored credentials. A request loop from A ran through it: sent 500, ok 500, failed 0, executions on C 500, 1 path failover, slowest request 2040 ms.
- **No stored credentials at all** (06:10:41, phone C): `POST /v1/link/direct/stop` leaves the group and forgets the credentials; a paired peer that hosts a group was heard, C asked A for the sealed credentials (`GET /v1/link/p2p-creds`, AES-256-GCM under the pairing secret) and **rejoined 4.2 s after the stop with no QR and no tap** (join 425 ms).
- **The host makes a new group with a new passphrase** (A re-created its group at 06:12:44 and at 06:17:07). With the build that tried C's stored passphrase first, C stayed in `client / starting` for the 58 s I watched it (06:12:46 to 06:13:42) and did not come back by itself: a real defect that this test found. Fixed in `DirectRendezvous` (the host's current credentials are asked for first; the stored ones are the fallback when the host cannot be asked). Result with the fix: C rejoined **4.2 s after A's group was re-created** (06:17:07 to 06:17:12, join 418 ms). A phone whose app was reinstalled while it held a stale passphrase (C, 06:14:50) was back in the group at 06:15:20, the second attempt (attempts are 20 s apart) after one failed try with the stale passphrase.
- When the group is gone for good the state is `idle` or `failed` with the error and `lostAtMs`; it never stays `up`. JVM tests cover the state mapping of the pure parts; the Wi-Fi Direct calls themselves are only checked on the phones.

## 7. Hardening checks (review items, on phone C, debug build, 05:xx)

PASS lines from `checks.py`: unsigned `GET /v1/link/pairing-payload` refused (HTTP 401), signed one works; every answer carries a valid `X-Swarm-Rsp` (HMAC over status, request id and body), including the 401, 413 and replayed ones; wrong secret gives 401 `bad signature`; a timestamp 90 s ahead gives 401 `clock skew` with the server time (the peer row shows "clock off by N s"); a 2 MiB body gets 413 before it is read; a state-changing request without `X-Swarm-Req` gets 400; adding peer 8.8.8.8 is refused (only RFC 1918, link-local and the Wi-Fi Direct subnet are accepted); the probe port closes a connection without a valid token. Probe limits: 16 connections, 4 GiB and 300 s per connection. JVM tests cover each of these (`SecurityTest`, `ResponseAuthTest`, `TputAuthTest`, `LocalAddressTest`, `ClockSkewReasonTest`, `HeartbeatThreadSafetyTest`); pairing confirmation was checked by the `pendingPairing` field of `GET /v1/link/peers` (a deep link without the debug extra `confirm` leaves the code pending; with `--ez confirm true` it is adopted).

## 8. Three phones: what to repeat when phone B is free

Phone B was unavailable, so everything is a two phone result. With the three phones (B at 192.168.242.23, node `477684c1`) run, from the PC with `SWARM_SECRET` set to the swarm's secret:
1. Install and pair as for A and C: `bash scratchpad/inst.sh A B C` equivalent (`adb install -r app-debug.apk`, `pm grant` for NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION, CAMERA, POST_NOTIFICATIONS, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT), then open the debug payload with a signed `GET /v1/link/pairing-payload` on A and inject it into B as `swarmos://pair?d=<base64url>` with `--ez confirm true`.
2. B and C as clients of A's group: `POST /v1/link/direct/join {"ssid":...,"pass":...}` on B and on C; the group must be on the station's channel (`GET /v1/link/direct` shows `frequencyMhz` equal to the Wi-Fi station's).
3. B to C goes through the group owner (two air hops on one channel): `POST /v1/link/tput {"peerNodeId":"<C>","megabytes":200,"link":"DIRECT","pings":0,"streams":4}` on B, and the reverse; phase 1 measured about half the client-to-owner figure.
4. Bluetooth mesh: each pair with the smaller node id initiates; check `GET /v1/link/ble` on all three (`connected` lists two ids) and `POST /v1/link/ble-bench` for every pair.
5. Failover with three phones: run `POST /v1/link/failover-test` from A towards B while phone C's Wi-Fi is toggled over USB; B must be unaffected.
6. A real Wi-Fi-off window (not done here: phone C was not on USB): with C on USB, announce the time, run `POST /v1/link/failover-test` from A towards C (700 requests, 100 ms apart), switch Wi-Fi off on C for 10 s over USB (`adb -s 10BFAT216Z000XQ shell svc wifi disable`, then `enable`), and expect ok 700, failed 0, executions on C 700, and the Bluetooth path carrying the small requests meanwhile.

## What I changed on the phones, when, and what I left

Everything below was done only on phones A and C; **phone B received no command from me after the founder reserved it (03:40)**.

- Installed the debug build of `com.swarmos.app.link` (node port 8797) on A and C many times; the last install was about 06:14 (C) and 06:16 (A). Runtime permissions granted with `pm grant`: NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION, CAMERA, POST_NOTIFICATIONS, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT. **Removed again at 06:21:11** (`adb uninstall com.swarmos.app.link`, Success on both); `com.swarmos.app` (the shared main build) is untouched.
- Bluetooth: it was off on A and C. I switched it on at 04:07:49 (`svc bluetooth enable`), and switched it off again at 06:21:07 (`svc bluetooth disable`; `settings get global bluetooth_on` was 1, then 0 on both).
- Wi-Fi Direct: A's group `DIRECT-sw-c6f72247` was created and re-created several times for the tests (04:34 for the channel finding, 06:12:44 and 06:17:07 for the credential tests); C joined and left it many times through the app's own routes. Removed at 06:21:04 (`host/stop` and `direct/stop` on C then A); afterwards `ip -4 addr | grep -c p2p` was 0 on both phones. No rpc or llama server was using the 192.168.49.x addresses (the processes that were running at 06:21 belong to the end-to-end run on the main build over the LAN, port 8787); the group removal does not touch the LAN.
- **No Wi-Fi switch on any phone, no setting changed.** The only system-level commands: `svc bluetooth enable/disable`, `input keyevent` and `am start` of the app (below), `pm grant`, `screencap`, read-only `dumpsys`.
- **Keep-awake loop** (`KEYCODE_WAKEUP` and `am start` of `com.swarmos.app.link` every 3 s on A and C, so the screen stays on and the app in front for the timed runs): ran in several stretches from 03:09; the last one 05:47 to 06:19, stopped at 06:19. While it ran, the link build was in front on A and C. **Anything that needs the main build in front during those stretches (the end-to-end run on `com.swarmos.app` was active from 05:25 to at least 06:19) was displaced by it every 3 s**: this may have cost that run foreground time, and I did not check its logs for it.
- Files on the phones: I pushed nothing in phase 2. `/data/local/tmp/tput` (23:44 on 2026-10-09, phase 1 native probe) is still on A and C, and some files of other workers (`llama`, `router`, `human.*`, `devcap.sh`, `ui_*`); I left all of them because they predate this phase or are not mine.
- Processes: all my loops and background scripts are stopped (`wake.sh`, the bulk-matrix waits). Nothing of mine runs on the PC or on the phones.
