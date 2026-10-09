# Gate 1 — SwarmLink (closed 2026-10-10 by the orchestrator, phase 1; phase 2 in progress)

Gate criterion: two phones discover each other and exchange 100 MB with Mbps and RTT measured in-app. Met on three phones. Evidence: docs/benchmarks/2026-10-10-swarmlink-g1.md (measured with the `.link` build variant; the same code is merged into master at 99b9b6f).

## Decisions
1. Bulk path = Wi-Fi Direct autonomous group on 5 GHz, created by the app, joined with known network name and passphrase. No user dialog, forms in 0.5 s (host) and 1.3 to 1.8 s (clients). Station and P2P run concurrently; wireless adb never dropped.
2. Zero-setup path = same Wi-Fi (NSD `_swarmos._tcp` + signed health check). Debug builds share a dev secret; pairing by QR or the `swarmos://pair` deep link replaces it.
3. Heartbeat every 1 s over signed HTTP; a peer is marked away after 3 misses.
4. Every NodeClient call already carries a unique `X-Swarm-Req` id (server-side dedup lands in phase 2).

## Measured (quiet phones, app in front, 100 MiB, A and B)
| Link | Mbps | RTT min / avg / p95 ms |
|---|---|---|
| Wi-Fi Direct, 5 GHz | 305 to 503 | 2.7 to 5.0 / 12 to 20 / 24 to 50 |
| Same Wi-Fi (through the access point) | 96 to 104 | 6.7 to 7.7 / 23 to 26 / 42 to 54 |

Other results: all three phones list each other 3.7 to 5.3 s after a cold start (limit 15 s); a force-stopped peer is marked away after 2.1 to 2.2 s and is back 1.0 s after restart; a request signed with the wrong secret gets 401.

## Deltas from plan
- PRD target "at most 8 ms average RTT" is NOT met (12 to 26 ms average, though the minimum is 2.7 ms). Likely Wi-Fi power save and per-request connection setup; phase 2 step 3 addresses it.
- The Direct group sits on the same channel as the access-point link (5765 MHz, one shared radio). Channel width, Wi-Fi standard and PHY speed of the P2P interface cannot be read without root, so "Wi-Fi 7" is not claimed anywhere.
- Phones C pairs were measured only while other workloads ran (93 to 185 Mbps on Direct); not re-measured quiet.
- Custom 4-byte framing was replaced by HTTP + JSON; Ed25519 by HMAC with the pairing secret (docs/CONTRACTS.md section 1).

## Open risks
- OriginOS freezes the app when the screen is off or the app is not in front (peers saw 22 to 265 missed heartbeats; recovery 0.6 s after returning to front). Needs the battery exemption from the first-run setup screen on each phone, which only the founder can tap. Master keeps the screen on while the app is in front.
- QR scan through the camera is untested (nobody can aim a phone); the QR encode/decode round trip passes in-process and the deep-link path is verified.
- Throughput numbers depend on thermal state and on other workloads; official numbers must be retaken on cool, idle phones before the demo.

## Ledger
- SwarmLink phase 1: one Sonnet worker in a worktree (about 560k tokens). Merge, conflict check, gate artifact: orchestrator.
- Phase 2 (multi-transport with Bluetooth, low-latency work, striping, dedup, failover): same worker resumed, brief plans/briefs/link2.md.
