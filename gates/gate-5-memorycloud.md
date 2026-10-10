# Gate 5 — MemoryCloud (OPEN as of 2026-10-10: mechanism proven, capacity claim not yet proven)

Gate criterion: a model that does not fit one phone's budget runs split across phones. NOT met yet. What is proven is the mechanism, with a model that does fit one phone. Evidence: docs/benchmarks/2026-10-10-rpc-split-spike.md (standalone), docs/benchmarks/2026-10-10-memorycloud-inapp.md (in the app), docs/benchmarks/2026-10-10-throughput-tuning.md.

## Decisions
1. MemoryCloud is a layer-range split using llama.cpp's RPC backend: worker phones run `rpc-server`, the driver runs `llama-server --rpc ...`. The output head stays on the driver when it is not tied to the embeddings, so full-vocabulary logits (about 1 MB per token) do not cross the link.
2. It is a capacity tool, never a speed tool. The app says so on screen when the chosen model fits one phone.
3. rpc-server has no authentication, so it binds only to the address of the link in use (the Wi-Fi Direct group address when there is one), starts only on a signed request from a paired phone, and stops on request or 10 s after the driver's heartbeat is lost.
4. A worker phone stops its own chat and embedding servers before lending memory and restores them afterwards.
5. Threads for child processes follow the cores the app is allowed to use (a fixed `-t 8` inside a process limited to six cores gave 0.3 tok/s; now 6.9).

## Measured, in the app, phones C (driver) and A (worker), Qwen3.5-4B, Wi-Fi Direct link
| What | Value |
|---|---|
| Load, worker cache cold | 28 s (1640 MB sent to A) |
| Load, cache warm | 11.3 to 12.0 s |
| Time to first token, 200-token answer | 2.3 to 2.6 s |
| Decode, split over two phones | 6.9 to 7.0 tok/s |
| Decode, same model on C alone | 10.8 to 14.7 tok/s |
| Memory | C 2.7 GB (peak 3.7), A 1.2 GB (peak 1.66) |
| A phone force-stopped mid-answer | driver shows DEGRADED naming the phone after 1.2 s; re-plan READY in 12.0 s |

Standalone over the shared Wi-Fi the same split gave 3.2 to 3.5 tok/s; the Direct link and the thread fix account for the difference.

## The large model (Qwen3-30B-A3B-Instruct-2507 Q4_K_M, 18.6 GB)
- One phone: no token after 134 s, swap thrashing, 23 background apps killed. This is the measured "cannot run on one phone" baseline.
- Two phones (C driver, A worker): did not load. The driver thrashed and the worker received 0.47 of 9.3 GB in 8 minutes. About 11 GB free per phone would be needed.
- Three phones: never run. Phone B has been reserved by the founder since the three-phone attempt was due; the exact commands are in both benchmark files.

## What closes this gate
A three-phone run of the 30B model through the app that produces tokens, with load time, time to first token, decode tok/s, memory per phone and thermal state recorded. If it does not load on three phones either, the honest fallback is a smaller file of the same model (a 13 to 15 GB quantisation) that fails on one phone and loads on two; that needs another download of about 14 GB.

## Open risks
- The capacity claim may not hold on this hardware at all; the demo script must work without it (docs/DEMO.md marks the step PENDING MEASUREMENT with a proven fallback).
- Screens of the Memory tab and the ExpertMesh explainer were never looked at on a phone.
- Per-device layer placement is read from the plan, not from the server log (the server prints no per-device lines at default verbosity).
- Sustained generation reaches thermal status 3 to 4 within minutes; a split uses two or three hot phones at once.

## Ledger
- Native RPC spike and tuning: one Sonnet worker. In-app module: one Sonnet worker (about 520k tokens). Merge and this artifact: orchestrator.
