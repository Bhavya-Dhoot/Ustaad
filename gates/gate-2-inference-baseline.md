# Gate 2 — Inference baseline (closed 2026-10-10 by the orchestrator; cool-start in-app retake still owed)

Gate criterion: the chosen model runs in-app on one phone with tokens/s logged. Met on three phones. Evidence: docs/benchmarks/2026-10-10-llama-cpu-baseline.md (standalone), docs/benchmarks/2026-10-10-inapp-llm.md (in-app), docs/models.md (pinned files and hashes), docs/llama-server-api-notes.md.

## Decisions
1. Runtime = llama.cpp v0.6.0 `llama-server` and `rpc-server`, cross-compiled with the NDK and shipped in the APK as executables; the app launches them as child processes and talks HTTP on loopback. No JNI inference wrapper.
2. CPU build variant = armv8.6 with dotprod, fp16 and i8mm ("i8mm"). It is 3.7 times faster on prefill than the default Android build (38.2 against 10.4 tok/s at 6 threads); SVE2 and KleidiAI add nothing here.
3. Chat model = Qwen3.5-4B Q4_K_M (2.74 GB, Apache-2.0), thinking off. It is also the only model used for JSON-schema output (10 of 10 valid on each phone). Gemma 4 E4B loads but returns empty content on 3 of 10 schema calls, so it is "chat only".
4. Embedder = EmbeddingGemma-300M Q8_0, truncated to 256 dims. EmbeddingGemma 2 was rejected (see docs/models.md).
5. The LiteRT fallback from gate 0 is not needed: in-app decode is above the 10 tok/s threshold.

## Measured
| What | Value | State |
|---|---|---|
| Decode, Qwen3.5-4B, in-app, phone B | 14.8 tok/s | start status not recorded, ended LIGHT to MODERATE |
| Decode, standalone, phone A, 8 threads | 14.86 tok/s | cool start |
| Prefill, CPU, 8 threads | 48 to 60 tok/s | mixed |
| Prefill, GPU (OpenCL, Adreno 840) | 125.6 tok/s (decode 13.3) | cool start, one rep |
| Chat server memory | 4.8 to 5.6 GB resident | |
| Embedder | 256-dim unit vectors, cosine 0.776 related vs 0.404 unrelated | |
| Load to ready | 4 to 6 s warm, 19 to 31 s cold | |

## Deltas from plan
- The phones throttle hard and fast: OriginOS caps core frequencies far below the hardware maximum and thermal status reaches 2 to 3 within minutes of load. Sustained decode fell to 4.9 to 6.1 tok/s at CRITICAL. Every number must carry its thermal state.
- GPU helps prefill (about 2.5 times) and not decode; a GPU-prefill configuration is being evaluated in the throughput-tuning run.

## Open risks
- No clean cool-start in-app table yet: the phones never cooled below status 2 while charging from the PC under continuous work. Owed before the demo (device-tester, cool idle phones).
- OriginOS `pem_freezer` killed the whole app twice on phone B (6 and 11 minutes after screen-off) while it was generating in the background. An idle app survived. The battery exemption is untested because it needs a tap on the phone.
- A 4B chat server plus embedder costs about 6 GB per phone; MemoryCloud must stop them on worker phones before loading a large split.
- The model download screen was not exercised on a phone (metered link); models were pushed over adb.

## Ledger
- Native build, benchmarks, model pins: one Sonnet worker (still running the RPC split and throughput tuning). In-app runtime: one Sonnet worker (about 620k tokens). Merge and gate artifact: orchestrator.
