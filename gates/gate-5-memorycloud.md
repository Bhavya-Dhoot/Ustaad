# Gate 5 — MemoryCloud (closed 2026-10-10 by the orchestrator: an 18.6 GB model that does not run on one phone ran split across three)

Gate criterion: a model that does not fit one phone's budget runs split across phones. Met on phones C (driver), A and B (lenders), through the app. The criterion was written for two phones; on this hardware the chosen model needs three, and that is what was measured. Evidence: docs/benchmarks/2026-10-10-memorycloud-inapp.md section "Three phones" (master e2d41e4, debug build, 09:18 to 09:37), screenshots docs/design/shots/memcloud-ready-30b.png and memcloud-degraded-30b.png, and for the baselines docs/benchmarks/2026-10-10-rpc-split-spike.md.

## Decisions
1. MemoryCloud is a layer-range split using llama.cpp's RPC backend: lender phones run `rpc-server`, the driver runs `llama-server --rpc ...`. The output head stays on the driver when it is not tied to the embeddings, so full-vocabulary logits do not cross the link.
2. It is a capacity tool, never a speed tool. The app says so on screen when the chosen model fits one phone.
3. rpc-server has no authentication, so it binds only to the address of the link in use (the Wi-Fi Direct group address), starts only on a signed request from a paired phone, and stops on request or when the driver's 10 s lease is not renewed.
4. A lender stops its own chat and embedding servers before lending memory and restores them afterwards. Every failure path runs the same teardown.
5. Threads for child processes follow the cores the app is allowed to use.
6. The weights travel over the app's own Wi-Fi Direct group, not the access point.

## Measured: Qwen3-30B-A3B-Instruct-2507 Q4_K_M (18.6 GB, 48 layers), three phones, Wi-Fi Direct
| What | Value |
|---|---|
| One phone alone | no token after 134 s, swap thrashing, 23 background apps killed |
| Two phones | did not load (0.47 of 9.3 GB reached the lender in 8 minutes) |
| Three phones, load with empty lender caches | 319 s |
| Three phones, load with warm caches (re-plan) | 65 s |
| Time to first token | 1.5 to 2.8 s |
| Decode | 5.7 to 6.4 tok/s over 100 and 200 token answers |
| Layer split | C 0 to 15, A 16 to 31, B 32 to 47 |
| Weights received by lenders | A 5743 MB, B 5991 MB |
| Memory of app plus children while serving | A 5.9 GB, B 6.1 GB, C 5.1 GB; 5.2 to 5.3 GB still available on each phone |
| Link | Direct group at 5765 MHz; C to A 680 Mbps, C to B 245 Mbps, round trip about 7 ms |
| Lender force-stopped mid-answer | driver shows DEGRADED naming Phone B after 1.8 s, keeps the 27 tokens written; re-plan READY in 65 s |
| Thermal | 1, 1, 1 at the start; 0, 0, 1 at the end |

Same mechanism with a model that fits one phone (Qwen3.5-4B, two phones): 6.9 to 7.0 tok/s split against 10.8 to 14.7 on one phone. Splitting costs speed; it buys capacity.

## Deltas from plan
- Two phones were not enough for the 30B model; three were. Any sentence that says "two phones run a model neither can run alone" is not supported. The supported sentence is "three phones run an 18.6 GB model that one phone cannot".
- The first start failed in 12 s because port 50052 was still held on Phone A. The teardown left nothing behind and the second start worked. Not reproduced, cause not found.
- The cold load takes 5.3 minutes. A presenter must load before the demo and keep the caches.

## Open risks
- One run. No repeat of the cold load, no sustained generation beyond 40 s per answer, no measurement at thermal status 2 or above.
- The DEGRADED button can read "Re-plan with 3 phones" for a few seconds after a phone is lost, because the registry still lists it.
- A start does not retry by itself when a lender reports its port in use.
- The system killed cached apps on the driver during both loads (camera, search, messaging). Ustaad itself was not killed while it was in front.
- Per-phone layer placement is read from the plan and from the bytes each lender received, not from a server log line.
- The run used the debug build and the development pairing secret. The demo build has not carried a split.

## Ledger
- Native RPC spike and tuning: one Sonnet worker. In-app module, lease, teardown, screen fixes and the three-phone run: one Sonnet worker (about 1.3M tokens over its runs). Merge and this artifact: orchestrator.
