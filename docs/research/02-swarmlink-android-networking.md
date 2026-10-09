# SwarmLink: Android local-link research (as of 2026-10-09)

Scope: 2-3 iQOO phones (model I2501, Snapdragon 8 Elite Gen 5, OriginOS 6 on Android 16, Wi-Fi 7 silicon) forming an internet-free, high-throughput, low-latency data link for distributed AI.

**Evidence policy.** Web search returned almost NO published measured Mbps/ms for Wi-Fi Aware, Nearby Connections or P2P on current phones. Every number below is (a) from a cited source, (b) a labelled anecdote, (c) a device fact measured over ADB by the coordinator (Section 0), or (d) marked "(unverified)", an engineering estimate to be replaced by the Section 5 test plan.

## 0. Verified on iQOO I2501 (measured over ADB, 2026-10-09, supplied by coordinator)

| Fact | Result | Consequence |
|---|---|---|
| `pm list features` | has `android.hardware.wifi.direct` and `android.hardware.wifi`; **NO `android.hardware.wifi.aware`** | Wi-Fi Aware not available |
| `dumpsys wifiaware` | "Can't find service: wifiaware" | Confirms: NAN is absent in this ROM, not just disabled |
| OS | OriginOS 6, Android 16 (SDK 36), vivo | Targets SDK 36 behavior (Local Network opt-in) |
| Current STA link | Wi-Fi standard **11ac**, 433 Mbps PHY, 5765 MHz, WPA3 AP | The AP in use is not Wi-Fi 7/6; says nothing about P2P |
| `dumpsys` chip caps | `chipCapabilities={7, 8, 26, 30, 64}` (meaning not decoded) | Decode against `WifiManager` feature IDs before drawing conclusions |
| Interface combos | allow STA+AP, STA+P2P, dual STA | Phone can host a hotspot while on a router, or run P2P while on a router |
| SoftAP features | `wifi_softap_acs_supported`, `wifi_softap_wpa3_sae_supported` | Hotspot is WPA3-capable and auto-channel capable |
| Bluetooth | LE channel sounding feature present | Useful for BLE ranging/proximity, not bulk data |
| Not seen in feature list | `android.hardware.wifi.rtt` and UWB flag were not reported to me | Treat RTT/UWB as absent until the full list is checked |

Implication: the candidate set is **Wi-Fi Direct, LocalOnlyHotspot/SoftAP (+STA), same-router LAN, Nearby Connections (falls back to Direct/hotspot/BT), USB**. Aware is dropped.

## 0.1 Bottom line

| Item | Verdict |
|---|---|
| Primary link | Phone-hosted SoftAP (hotspot or LocalOnlyHotspot) on 5 GHz (6 GHz if the ROM offers it) with plain TCP sockets bound to the `Network`. Expect 300-900 Mbps, 2-8 ms RTT (unverified) |
| Fallback 1 | Wi-Fi Direct group (GO = hub). 200-500 Mbps (unverified) |
| Fallback 2 | Same-router LAN (works offline if the router has no WAN; Wi-Fi 7 only if router is 11be) |
| Fallback 3 | Nearby Connections (STAR/P2P) |
| Fallback 4 | BLE control channel only; USB/ADB for dev |
| Wi-Fi 7 verdict for P2P | **Not proven.** Android documents Wi-Fi 7 for STA and SoftAP (OEM overlay). Nothing found says Wi-Fi Direct (or Aware) uses 802.11be/MLO. Plan for 11ac/ax-class |
| Biggest unknown | Whether vivo enables 11be SoftAP and lets LOHS pick band: probe in 10 minutes (Section 5, step 0) |

## 1. Android local-link options

### 1.1 Comparison table

| Option | Throughput (Mbps) | Latency | Setup time | Max peers | Internet? | Same router? | Evidence |
|---|---|---|---|---|---|---|---|
| Wi-Fi Aware (NAN) | n/a on I2501. Generic: 11ac/ax-class (unverified) | 2-10 ms (unverified) | Instant mode (Android 13+) lasts 30 s | `getAvailableAwareResources()` | No | No | [Android guide](https://developer.android.com/develop/connectivity/wifi/wifi-aware), [AOSP](https://source.android.com/docs/core/connect/wifi-aware); **absent on I2501 (Section 0)** |
| Wi-Fi Direct (P2P) | 11ac/ax-class; anecdote ~54 MB/s (~440 Mbps) Galaxy S6 to Note 9 file copy (old hardware) | 2-10 ms (unverified) | 3-10 s incl. negotiation/consent (unverified) | 1 GO + clients; practical 3-8 (unverified) | No | No | [Android P2P](https://developer.android.com/develop/connectivity/wifi/wifi-direct), [WifiP2pConfig](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig) |
| LocalOnlyHotspot / SoftAP + LAN | SoftAP can be 11be if OEM sets `config_wifiSoftapIeee80211beSupported` | 2-8 ms (unverified) | 2-5 s (unverified) | OEM-set, several | No (LOHS has no upstream) | No (phone is the AP) | [AOSP Wi-Fi 7](https://source.android.com/docs/core/connect/wifi-7); LOHS band control unverified (API reference unreadable in this session) |
| Same-router LAN | Router-bound; Wi-Fi 7 needs phone AND AP 11be | 2-6 ms (unverified) | Instant | Router limit | Not required | Yes | [AOSP Wi-Fi 7](https://source.android.com/docs/core/connect/wifi-7); I2501 currently on an 11ac AP |
| Nearby Connections | Medium auto-chosen; no official numbers. A 2023 paper tested STAR and P2P ([ScienceDirect](https://www.sciencedirect.com/science/article/pii/S2542660523002184)); fetch returned 403, unread | Above raw sockets (unverified) | 3-10 s | Strategy-dependent | No | No | [Strategies](https://developers.google.com/nearby/connections/strategies) |
| Bluetooth LE | ~0.1-1.4 (unverified) | 7.5-50 ms | <1 s | ~7 (unverified) | No | No | general knowledge |
| Bluetooth Classic | ~1-3 usable (unverified) | 20-40 ms | 1-3 s | few | No | No | general knowledge |
| USB tether / ADB | Hundreds of Mbps for tether (unverified); adb reverse faster | <1 ms | Manual | 1 per cable | No | No | dev baseline only |
| UWB | ranging only, no data | n/a | n/a | n/a | No | No | FastConnect 7900 integrates UWB ([9to5Google](https://9to5google.com/2024/02/25/qualcomm-fastconnect-7900/)); not confirmed present on I2501 |

### 1.2 Wi-Fi Aware (reference only; unavailable on I2501)
- Public API since Android 8.0; needs framework + Vendor HAL (AIDL Android 14+) + firmware. Android 12 simplified data-path setup; Android 13 added instant mode (30 s). Data path is IPv6 link-local. Messages ~255 B. [Android guide](https://developer.android.com/develop/connectivity/wifi/wifi-aware), [AOSP](https://source.android.com/docs/core/connect/wifi-aware)
- No Android 16 specific Aware changes appeared in fetched docs. A patent text says NDP is 2.4/5 GHz with 6 GHz needing regulatory steps ([USPTO](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11722950), secondary). No evidence Aware uses 11be or MLO.
- Keep the code path optional: other vendors (Pixel, Samsung) may have it, but SwarmLink on iQOO must not depend on it.

### 1.3 Wi-Fi Direct (available on I2501)
- `WifiP2pConfig` supports 2.4/5/6 GHz group-owner band selection and Wi-Fi Direct R2 modes; release that added each is not stated. [WifiP2pConfig](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig)
- AOSP page covers HAL requirements only; nothing on 11be. [AOSP](https://source.android.com/docs/core/connect/wifi-direct)
- The GO acts as an AP; client-to-client traffic hairpins via the GO. STA+P2P combo is allowed on I2501, so P2P can run while the phone is on a router.
- A user consent dialog appears on connect unless persistent groups / newer paths are used (unverified on Android 16).

### 1.4 LocalOnlyHotspot / SoftAP
- Wi-Fi 7 hooks exist for SoftAP (OEM overlay) and 320 MHz reporting; Wi-Fi 7 STA requires both ends 11be. Android 13+. [AOSP Wi-Fi 7](https://source.android.com/docs/core/connect/wifi-7)
- This is the only phone-to-phone mode with documented Wi-Fi 7 hooks. Whether vivo enables it is unknown; check `dumpsys wifi` for 11be/EHT and client `WifiInfo#getWifiStandard()`.
- I2501 reports STA+AP combo and ACS support, so SoftAP concurrent with a router STA link is plausible.
- LOHS: local only, no internet; app-selectable band is limited (unverified).

### 1.5 Does a Wi-Fi 7 phone get Wi-Fi 7 for P2P/Aware?
| Mode | 11be documented? | MLO? | Expectation |
|---|---|---|---|
| Infrastructure STA to 11be AP | Yes (Android 13+) | Yes, scoring Android 14+ | Wi-Fi 7 only if AP also 11be |
| SoftAP | Yes via OEM overlay | Not stated | Possible; verify |
| Wi-Fi Direct | **Not stated in any source found** | Not stated | Assume 11ac/ax (unverified) |
| Wi-Fi Aware | Not stated; absent on I2501 | Not stated | Moot |

FastConnect 7900 supports 11be/ax/ac/n and High Band Simultaneous multi-link ([Qualcomm brief](https://docs.qualcomm.com/bundle/publicresource/87-73970-1_REV_C_Qualcomm_FastConnect_7900_Mobile_Connectivity_System_Product_Brief.pdf)), so silicon can; the open question is the vendor HAL/supplicant exposing it for P2P. Test via `dumpsys wifi` (wifiStandard, channelWidth, linkSpeed) while a link is up.

### 1.6 Nearby Connections
- Strategies: CLUSTER (mesh, lower bandwidth), STAR (hub + spokes, higher), POINT_TO_POINT (1:1, highest). [Google](https://developers.google.com/nearby/connections/strategies)
- Payloads: BYTES (<=32 KB), FILE, STREAM. [Overview](https://developers.google.com/nearby/connections/overview)
- Per reverse-engineering, BT/BLE for rendezvous, Wi-Fi Direct / soft-AP for STAR and P2P ([NDSS 2019](https://www.ndss-symposium.org/wp-content/uploads/2019/02/ndss2019_06B-3_Antonioli_paper.pdf)). On I2501 it will therefore use Direct/hotspot/BT, not Aware.
- Requires Google Play services (check the unit has GMS). You cannot force band or Wi-Fi 7.

### 1.7 Wired fallback
`adb reverse/forward` phone-to-PC proves the app stack independent of radio; USB tether gives phone-to-PC only. Phone-to-phone wired needs OTG host networking (unverified).

## 2. Transport libraries (Kotlin)

| Library | Local discovery | Multi-peer | Reconnect | Fit | Notes |
|---|---|---|---|---|---|
| Raw TCP over `Network.socketFactory` | External | You build | You build | Excellent, zero overhead | Recommended v1 |
| Cronet / QUIC | No | Client/server | Migration | HTTP-oriented, no easy local server | Poor fit |
| OkHttp / WebSocket | No | Hub-and-spoke | Manual | OK for control plane | |
| gRPC-Kotlin | No | Hub-and-spoke | Channel reconnect | Protobuf overhead on tensors | Good for control |
| WebRTC data channels | ICE on LAN without STUN; needs signalling | Mesh | ICE restart | SCTP throughput limited (unverified), big dependency | Avoid |
| iroh (Rust/QUIC) | mDNS crate exists; relay can be disabled | Yes | Yes | Strong in principle | Kotlin: official artifact needs source build on Android; a third party reports the JNA artifact hangs on Android (unverified). [Docs](https://docs.iroh.computer/languages/kotlin), [iroh-kmp](https://klibs.io/project/Azula-App/iroh-kmp) |
| libp2p | mDNS | Yes | Yes | Heavy | Overkill |
| ZeroMQ (JeroMQ/libzmq) | No | Patterns | Auto-reconnect | Good, simple | Fine for 3 peers |

Serialization (recommendation, general knowledge): raw bytes with a small fixed header (id, dtype, shape, offset, crc) for tensors; protobuf only for control; FlatBuffers if zero-copy structured metadata is needed; msgpack gives no benefit for bulk binary. Chunk 256 KB-4 MB, direct ByteBuffers, `SocketChannel`.

## 3. Discovery and permissions

| Mechanism | Infra-free? | Notes |
|---|---|---|
| Nearby endpoint discovery | Yes | Play services; BLE+BT+Wi-Fi |
| Wi-Fi Direct service discovery | Yes | [Android doc](https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct) |
| NSD/mDNS (`NsdManager`) | Only on an existing L2 (hotspot, P2P group, router) | Multicast on hotspots can be flaky (unverified) |
| BLE advertise + GATT | Yes | Best out-of-band channel for hotspot SSID/PSK |
| Fixed gateway IP (SoftAP/GO) | Yes | Simplest: clients connect to the AP gateway address, no mDNS needed |

**Permissions (Android 16, target SDK 36):**
- `NEARBY_WIFI_DEVICES` (runtime; `neverForLocation` if you derive no location): Direct, LOHS, Nearby. [Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`
- `ACCESS_FINE_LOCATION`: Wi-Fi Direct docs still list it; LOHS historically needs it with Location ON (unverified for 16). Request it.
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (runtime)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` (or `DATA_SYNC`), `POST_NOTIFICATIONS`, `WAKE_LOCK`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS)
- Android 16 Local Network Protection is opt-in (test: `adb shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>` then grant `NEARBY_WIFI_DEVICES`); Android 17 with target SDK 37 switches to `ACCESS_LOCAL_NETWORK` and blocks LAN TCP/UDP/multicast/`NsdManager` by default, for all libraries. Doc does not say whether Direct/Aware interfaces are covered: test. [Doc](https://developer.android.com/privacy-and-security/local-network-permission)

## 4. Vendor quirks (vivo / iQOO)

- Background killing: restrictions not fully mapped, **no developer-side fix listed**; user steps: Autostart on, battery Unrestricted / Not optimized, allow high background power use, lock in Recents. [dontkillmyapp](https://dontkillmyapp.com/vivo)
- Mitigation: foreground service with notification, partial wake lock, `WifiManager.WifiLock` (high-perf/low-latency), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, onboarding wizard for the vivo settings; verify with screen off 30 min.
- Wi-Fi Aware: absent on I2501 (Section 0). Other vivo/iQOO models were not checked; no community source found.
- Specs: Snapdragon 8 Elite Gen 5, Wi-Fi 7, BT 6.0, USB 3.2 Gen1 for iQOO 15 ([GSMArena](https://m.gsmarena.com/iqoo_15_debuts_with_snapdragon_8_elite_gen_5_soc_7000mah_battery-news-69980.php), [vivo MY](https://shop.vivo.com/my/product/2585)); UWB unconfirmed.
- Thermal: sustained NPU + 5 GHz radio will likely throttle within minutes (unverified); log `PowerManager.getCurrentThermalStatus()` and `dumpsys thermalservice`.
- Concurrency: I2501 allows STA+AP, STA+P2P, dual STA, but not necessarily AP+P2P at once: test.

## 5. Afternoon test plan (2 phones, ~3-4 h)

Setup: USB debugging, `adb -s <serial>`. iperf3 via Termux (`pkg install iperf3`, F-Droid build) or `adb push iperf3 /data/local/tmp && adb shell chmod 755 /data/local/tmp/iperf3`. iperf3 measures throughput only; use `ping -c 200 -i 0.2` plus an app-level echo for latency.

| Step | What | How | Pass |
|---|---|---|---|
| 0 | Probes (already partly done) | `pm list features`; `dumpsys wifi \| grep -iE "11be\|EHT\|standard\|6GHz"`; decode chipCapabilities; `pm list features \| grep -iE "rtt\|uwb"` | Know 11be SoftAP, 6 GHz, RTT/UWB |
| 1 | Wired baseline | `adb reverse tcp:5201 tcp:5201`, iperf3 | CPU/copy ceiling |
| 2 | Hotspot + STA | Phone A hotspot, 5 GHz (then 6 GHz if offered), compat mode off; B joins; `iperf3 -s` on A, `iperf3 -c <gw> -t 20 -P 4` on B; read `wifiStandard/linkSpeed/channelWidth` on B | Mbps and standard |
| 3 | LOHS | Tiny app calling `startLocalOnlyHotspot`; read band; iperf3 | Compare with step 2 |
| 4 | Wi-Fi Direct | Android connectivity samples ([android/connectivity-samples](https://github.com/android/connectivity-samples), verify folder) or own app; iperf3 over the p2p interface | Mbps, GO band, connect time |
| 5 | Router LAN | Both on same router, iperf3; repeat with router 5 GHz vs 6 GHz | Baseline infra |
| 6 | Nearby | Nearby sample apps, 100 MB FILE and STREAM, STAR and P2P; log medium | MB/s, setup s |
| 7 | Latency | 64 B ping-pong x10,000 per link, p50/p95/p99 | p95 < 15 ms |
| 8 | Soak + thermal | 20 min of 200 MB chunks, screen off, foreground service; log thermal and Mbps/10 s | <30% drop, no kill |
| 9 | Kill test | Swipe away, battery opt on/off, Recents lock | Which vivo settings are mandatory |
| 10 | Concurrency | Hotspot ON while on router; Direct ON while hotspot ON | Which combos survive |

## 6. Recommendation (consistent with no Aware)

| Rank | Link | Expected Mbps / RTT (unverified until Section 5) | Condition |
|---|---|---|---|
| 1 | Phone-hosted SoftAP (hotspot/LOHS) 5 or 6 GHz + TCP to gateway IP | 300-900 (up to ~1200 if 11be SoftAP enabled); 2-8 ms | Hotspot band control OK |
| 2 | Wi-Fi Direct group | 200-500; 3-10 ms | Consent dialog acceptable |
| 3 | Same-router LAN | router-bound; 2-6 ms | A router exists (needs no WAN) |
| 4 | Nearby Connections STAR/P2P | medium-chosen | Play services present |
| 5 | BLE | <1 Mbps, control/heartbeat only | Always |
| Dev | USB / ADB | n/a | Testing |

Rendezvous: BLE GATT or Nearby to hand over SSID/PSK; hub-and-spoke through the SoftAP phone. Hide the radio behind a `PeerLink` interface over `Network`-bound sockets. Spoke-to-spoke traffic crosses the hub (extra hop, shared airtime): prefer pipeline-parallel over all-to-all.

### Five risks
1. **No Wi-Fi 7 on P2P**: Direct likely 11ac/ax-class; SoftAP 11be unproven. Measure; design for ~300 Mbps.
2. **Aware absent (confirmed on I2501)**: no phone-to-phone infra-free mode without a hub or consent dialogs; Direct needs user consent unless persistent groups work.
3. **OriginOS background killing** with no developer fix: foreground service, locks, onboarding wizard.
4. **Android 17 Local Network Protection** blocks LAN sockets/NSD by default at target SDK 37; coverage of P2P/AP interfaces unknown.
5. **Thermal throttling and hub airtime contention** under NPU + radio load; also unknown AP+P2P concurrency limits.

Library note: iroh Android bindings immature per third-party reports; use raw sockets or ZeroMQ for v1.

## Source gaps
- No measured Mbps/ms for Direct/Nearby on 2025-26 phones found.
- ScienceDirect Nearby paper returned 403.
- LOHS API reference unreadable; band choice unverified.
- Android 16 Aware/Direct-specific changes: none found.
- I2501 UWB/RTT flags and chipCapabilities meaning not verified by me.
