# SwarmLink baseline: phone-to-phone over the current access point

Date: 2026-10-09. Tool: scripts/tput/tput-arm64 (TCP, 1 MiB writes, TCP_NODELAY on RTT test). Both iQOO I2501 associated to the same AP ("Chulbul", WPA3, 5765 MHz, Wi-Fi standard 11ac, PHY 433 Mbps as reported by `dumpsys wifi`). Path = phone -> AP -> phone, two air hops on one radio channel. Phones idle, plugged in.

| Direction | Run | Bytes | Seconds | Mbps (sender) | Mbps (receiver) |
|---|---|---|---|---|---|
| A -> B | 1 | 104857600 | 13.81 | 60.7 | 60.9 |
| A -> B | 2 | 104857600 | 16.61 | 50.5 | 50.6 |
| A -> B | 3 | 104857600 | 15.90 | 52.8 | 52.8 |
| B -> A | 1 | 104857600 | 14.97 | 56.0 | 56.1 |
| B -> A | 2 | 104857600 | 13.99 | 60.0 | 60.1 |
| B -> A | 3 | 104857600 | 13.86 | 60.5 | 60.8 |

TCP 1-byte ping-pong RTT, 200 iterations:
| Direction | min ms | avg ms | max ms |
|---|---|---|---|
| A -> B | 6.60 | 11.07 | 19.79 |
| B -> A | 6.85 | 11.61 | 51.72 |

ICMP ping A -> B (10 packets): avg 14.6 ms, max 36.6 ms.

Reading: this AP is the floor, not the target. 55 Mbps / 11 ms is enough for Agent Choir (KB-sized JSON) and Private Data Union (snippets), and enough for pipeline-parallel decode (8-10 KB per token per hop = tens of tokens/s ceiling), but too slow for shipping model shards (6 GB at 55 Mbps = 15 min). G1 must measure the same probe over (a) one phone's 5 GHz hotspot with the other as client, (b) a Wi-Fi Direct group, and record `Wi-Fi standard`, `Link speed`, `Frequency` from `dumpsys wifi` during the run. Wi-Fi Aware is not available on these phones (feature absent), so it is not in the test matrix.

Invalid earlier attempts (do not repeat): toybox `nc` on these phones closes the socket early and reports 1-4 MB received of 100 MB sent. Use the tput binary.
