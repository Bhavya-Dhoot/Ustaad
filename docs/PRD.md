# Ustaad — Product Requirements Document

Naming note (2026-10-10): the product is called Ustaad. This document was written under the working name SwarmOS; read "SwarmOS" below as "Ustaad" for the product and as the coordination layer where it names the layer.

Version 1.0, 2026-10-09. Author: orchestrator session (Fable). Owner going forward: Opus build session.
Status: G0 closed. Build has not started.

## 0. One paragraph

SwarmOS turns two or three iQOO 15 phones on one local Wi-Fi link into one AI system with no cloud. SwarmLink is the transport. On top of it four modules share work: Agent Choir (phones reason in parallel and cross-check), Private Data Union (each phone searches its own private files and only snippets cross the link), MemoryCloud (a model too big for one phone runs split by layer range across phones), ExpertMesh (the same split applied to a mixture-of-experts model, shown honestly as a stretch). The product is the coordination layer; the inference engines are existing open-source runtimes.

## 1. Context that bounds everything

- Event: iQOO Hackathon 2026 Grand Finale, Bengaluru, 2026-10-09 to 2026-10-11, 48 hours. Teams of 1-3. Judging weights: product quality 30, novelty/impact 20, creative phone use 15, technical depth 15, Office Kit usage 10, demo 10. Bonus for a local/open-source model at the core. Demo must run on the iQOO phone. (docs/research/06)
- Hardware: iQOO I2501 = iQOO 15. Snapdragon 8 Elite Gen 5 (SM8850), 16 GB RAM (15.6 usable), Adreno 840, Hexagon NPU with libQnnHtpV81 present, Android 16 / OriginOS 6, arm64-v8a. Two units now (A, B), a third promised. (docs/device-probes/)
- Verified limits on the phones:
  - No Wi-Fi Aware (feature flag and service absent). Wi-Fi Direct, softAP, dual-STA present.
  - Current shared-AP path: 50-61 Mbps TCP, 11 ms avg RTT (docs/benchmarks/2026-10-09-swarmlink-lan-baseline.md). Must be beaten by hotspot or Direct in G1.
  - NNAPI service absent. NPU only via Qualcomm QNN/GenieX. GPU via OpenCL/Vulkan.
  - OriginOS kills background work; no developer-side fix documented. Foreground service + whitelist onboarding required.
  - App heap 512 MB large-heap; native (mmap) memory is what matters for models. Practical native budget per app unknown; assume 8-10 GB until G2 measures it.

## 2. Goals and non-goals

Goals, in priority order:
1. A judge watches 2-3 phones, no internet, finish a task one phone cannot: load a bigger model (MemoryCloud) and answer a private cross-device question (Private Data Union).
2. Every number on screen is measured on these phones during the demo or in docs/benchmarks/.
3. One APK, one Kotlin codebase, installs on all phones; any phone can be coordinator.

Non-goals (do not build): PhoneRack, Spatial Cortex, ModelTorrent, Edge Exchange, Blackout Cloud. Cloud fallback. Accounts. Any UI beyond the demo screens. Tensor parallelism over Wi-Fi (infeasible, docs/research/03). Live weight paging over Wi-Fi (infeasible). Remote experts inside a layer (one RTT per MoE layer caps decode at 4-8 tok/s).

Footnote only: Blackout Cloud = "SwarmLink already works with no internet; in an emergency the same mesh is the network." One slide line, zero code.

## 3. Users and demo story

Primary user for the finale: the judge. Secondary: a privacy-minded owner of several devices; an enterprise team reviewing contracts or code locally.

Demo (under 6 minutes, section 9): airplane-mode panel showing 0 bytes to internet, phones pair, Private Data Union finds "the warranty bill" with per-phone citations, Agent Choir solves a problem where phones visibly disagree then converge with a tool check, MemoryCloud loads a model one phone cannot, streams tokens, one phone is pulled, the system reports degraded state honestly.

## 4. Architecture

Single Android app `com.swarmos.app` (Kotlin, Jetpack Compose, minSdk 31, targetSdk 36; do not target 37, it mandates ACCESS_LOCAL_NETWORK).

```
+--------------------------------------------------------------+
| UI (Compose): Pair | Choir | DataUnion | MemoryCloud | Stats  |
+--------------------------------------------------------------+
| SwarmOS core (Kotlin): NodeRegistry, Scheduler, TaskBus,      |
|   HealthMonitor (thermal, battery, RAM), AuditLog (JSONL)     |
+----------------+-------------------+--------------------------+
| Agent Choir    | Private Data Union| MemoryCloud / ExpertMesh |
| round protocol | index + fed query | layer-range pipeline     |
+----------------+-------------------+--------------------------+
| Inference: llama.cpp via JNI (one runtime for all modules)    |
|   chat, embeddings, grammar-constrained JSON, rpc for split   |
+--------------------------------------------------------------+
| SwarmLink: PeerLink interface -> HotspotLink | DirectLink |   |
|   LanLink ; discovery by QR + NSD/mDNS ; TCP over             |
|   Network.socketFactory ; length-prefixed frames              |
+--------------------------------------------------------------+
| Foreground service (CONNECTED_DEVICE) + wake/wifi locks       |
+--------------------------------------------------------------+
```

Decision: one inference runtime, llama.cpp (ggml) built with the NDK into the app, CPU first, OpenCL Adreno if it builds cleanly. Reason: it is the only runtime that covers chat, embeddings, JSON grammar and a layer split (rpc backend) in one library with GGUF models; 48 hours do not allow two runtimes. LiteRT-LM (Gemma 4 on GPU, 22 tok/s for E4B on the same SoC) is the recorded fallback if llama.cpp decode on these phones is under 10 tok/s for the 4B class at G2. The GenieX NPU path is out of scope for the finale (developer preview, no embedding API).

## 5. Module requirements

### 5.1 SwarmLink (G1)
- R1.1 Pair two phones with no internet: coordinator shows a QR (SSID, PSK, IP, port, Ed25519 public key); peer scans, joins the coordinator's 5 GHz LocalOnlyHotspot or hotspot, opens TCP. Fallback order: hotspot, Wi-Fi Direct group, same-router LAN. Wi-Fi Aware is not an option.
- R1.2 Framing: 4-byte length + 1-byte type + payload. Control messages JSON (1 KB max). Bulk payloads raw bytes in 1 MiB chunks.
- R1.3 Heartbeat every 1 s; peer marked away after 3 misses; UI shows link standard, PHY link speed, measured Mbps and RTT (in-app port of scripts/tput).
- R1.4 Acceptance: 100 MB transfer and 200-round RTT measured in-app on hotspot and on Direct, written to docs/benchmarks/. Target at least 150 Mbps and at most 8 ms avg RTT on one link; floor is the 55 Mbps / 11 ms baseline.
- Permissions: NEARBY_WIFI_DEVICES (neverForLocation), ACCESS_FINE_LOCATION, CHANGE/ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, INTERNET, FOREGROUND_SERVICE and FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, WAKE_LOCK, CHANGE_WIFI_MULTICAST_STATE, CAMERA (QR).

### 5.2 Inference baseline (G2)
- R2.1 llama.cpp JNI wrapper: load GGUF, streaming chat completion, embeddings, JSON schema or grammar constrained output, unload. Models stored in the app's external files dir; downloaded on Wi-Fi with a visible progress UI (the only internet use, stated on screen).
- R2.2 Models (newest verified, docs/research/01): solver Qwen3.5-4B Q4_K_M non-thinking (fallback Gemma 4 E4B GGUF); second family for the alt-solver, Gemma 4 E2B or Granite 4.2 3B; embedder EmbeddingGemma 2 GGUF (fallback Qwen3-Embedding-0.6B GGUF). Pin exact file names and SHA256 in docs/models.md when downloaded.
- R2.3 Acceptance: prefill and decode tok/s at 512 and 2048 context, RAM high-water mark, 10-minute sustained run with thermal status logged, on both phones, in docs/benchmarks/. Practical native memory ceiling measured by loading increasing GGUF sizes until the kill.

### 5.3 Agent Choir (G3)
- R3.1 Roles: A solver, B alt-solver (different model family, same size class, asked for a different method, e.g. code), C critic-then-judge rotating so it never judges its own output. With 2 phones: A solver + B alt-solver, judge runs on A after both answers. A Kotlin tool-checker (arithmetic eval, unit-test run for code, schema check, quote-exists) is the real verifier, not a model.
- R3.2 Rounds: R1 blind parallel solve, 300-token cap, JSON {answer, confidence, key_steps[3 max]}. R2 only on disagreement: exchange answers and steps, 150 tokens, one round. R3 only if still no majority: judge, 100 tokens; tool check wins over judge. Hard deadline 60 s. Thinking mode off everywhere.
- R3.3 Final pick order: tool-verified, then majority, then confidence vote, then judge, then abstain.
- R3.4 UI: three lanes streaming, answer chips, DISAGREE banner, converge state, baseline lane (same model, one phone, same start instant) beside it. Hash-chained JSONL audit log of every message.
- R3.5 Acceptance: 20-problem offline set run on both phones, single-phone vs Choir, with n, accuracy and wall-clock, in docs/benchmarks/. Claim throughput and verification, not "smarter", unless the numbers say so.

### 5.4 Private Data Union (G4)
- R4.1 Index per phone: MediaStore photos (READ_MEDIA_IMAGES) via ML Kit OCR v2; SAF folder of documents (text PDF via pdfbox-android, scanned PDF via PdfRenderer + OCR, DOCX via unzip + XML, txt/md). Chunks 200-300 tokens, 15 % overlap, title prefix. One SQLite DB per phone: sqlite-vec (built with the NDK, loaded via BundledSQLiteDriver.addExtension) + FTS5; fallback Room BLOB brute force. 256-dim MRL embeddings.
- R4.2 Query: coordinator broadcasts signed query text (never embeddings) {qid, k, deadline}; each phone runs dense + BM25, RRF fusion, applies its share policy (folders marked private never return), returns top-5 snippets of 400 chars max with title, date, score; coordinator merges by RRF + min-max, dedupes, synthesizes with the local LLM, cites [Phone B: IMG_2231.jpg]; tapping a citation opens the file on the owning phone.
- R4.3 Deadline 1.5 s; an absent phone is shown as unreachable and the answer flagged incomplete. Nothing from peers is cached.
- R4.4 Acceptance: "find the warranty bill" with the receipt photo on B and the PDF on A, correct citations, time to first visible text 2 s max and full answer 6 s max on the hotspot link, measured. Airplane-mode panel shows 0 bytes WAN.

### 5.5 MemoryCloud (G5)
- R5.1 Pipeline parallel by layer range using the llama.cpp rpc backend compiled into the app (rpc-server thread on worker phones; the coordinator uses the RPC device for the remote layer range). Each phone holds only its shard on disk.
- R5.2 Model for 2 phones: Qwen3-14B Q4_K_M (about 9 GB) split 0-19 / 20-39 (adjust by measured RAM). Model for 3 phones: Qwen3-30B-A3B Q4 (about 18.6 GB) split 0-15 / 16-31 / 32-47. Shard distribution: each phone downloads its own shard on Wi-Fi beforehand; the link carries only activations (8-10 KB per token per hop).
- R5.3 Expected: 4-6 tok/s on 2 phones for 14B, 6-10 tok/s on 3 phones for 30B-A3B (estimates at 50-70 % of theory, docs/research/03). One-phone baseline shown beside: the same model fails to load or runs at seconds per token; Qwen3.5-4B on one phone shown for scale.
- R5.4 UI: per-phone shard bar, tokens/s, per-hop RTT, thermal status; pull a phone and the UI shows a clear degraded message, no crash.
- R5.5 Acceptance: tokens/s, time to first token, RAM per phone, 5-minute sustained, in docs/benchmarks/.

### 5.6 ExpertMesh (G6, stretch)
- Same pipeline mechanism applied to the MoE model, with a screen explaining why experts must be grouped by layer range (one RTT per MoE layer otherwise). If GPT-OSS-20B fits 2 phones at measured RAM, show it. No separate code path beyond model choice and the explanation screen.
- Stretch 2: cross-phone speculative decoding (0.6B draft on the coordinator) only if G5 closes with a day to spare.

### 5.7 SwarmOS core (all gates)
- NodeRegistry (id, role, caps: RAM free, thermal status, battery, models present), Scheduler (assign roles and shards by caps), TaskBus (request/response with ids, deadlines, cancellation), HealthMonitor, AuditLog. Any phone can be coordinator; the one that shows the QR is.

## 6. Build order and time budget (48 h from 2026-10-09)

| Gate | Deliverable | Hours | Worker agent |
|---|---|---|---|
| G1 | SwarmLink pairing + in-app tput, numbers on hotspot and Direct | 5 | swarm-network-engineer, swarm-android-builder |
| G2 | llama.cpp JNI, model download, tok/s + RAM ceiling on both phones | 5 | swarm-inference-engineer |
| G3 | Agent Choir end-to-end, 20-problem eval | 8 | swarm-choir-engineer |
| G4 | Private Data Union end-to-end, warranty demo | 9 | swarm-rag-engineer |
| G5 | MemoryCloud 2-phone split | 10 | swarm-inference-engineer, swarm-network-engineer |
| G6 | ExpertMesh explanation + 3-phone split if phone C arrives; demo polish; rehearsal | 6 | swarm-android-builder |
| buffer | | 5 | |

Parallelism allowed: G3 and G4 workers can run at the same time once G2 closes, on separate files. G5 starts only after G1 numbers exist.

## 7. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Venue Wi-Fi client isolation or no 5 GHz | Demo dead | Own hotspot from phone A is the primary link; Direct fallback; bring a travel router |
| llama.cpp rpc immature on Android ("fragile, insecure") | G5 fails | 2-hour spike at the start of G5 with Qwen3.5-4B split across 2 phones; if it fails, G5 becomes shard distribution + failover and the bigger-model claim is dropped |
| Native RAM ceiling under 8 GB | 14B split needs 3 phones | Measure at G2, pick the model by the number |
| Thermal throttle (reviewers: iQOO 15 keeps about 52 % of peak after about 10 min of stress) | tok/s halves mid-demo | Phones on chargers and a fan; each demo under 3 min; thermal status on screen |
| OriginOS kills the worker | peer drops | Foreground service, whitelist wizard, recents lock, retest after 30 min idle |
| Small models agree on the same wrong answer | Choir looks fake | Two model families, tool checker, "unanimous but unverified" label |
| Overclaiming | lose technical-depth points | Only measured numbers; show baselines; never say first, faster, or private without showing the trust model |

## 8. Metrics shown on screen

Per phone: model, tok/s (prefill, decode), RAM used, thermal status, battery, link standard and PHY speed, measured Mbps and RTT. Per task: wall-clock, tokens, rounds, agreement matrix, bytes to WAN (must read 0).

## 9. Demo script (judges, 6 min max)

1. 0:00 Airplane mode on all phones, hotspot on A, pair B (and C) by QR. Stats bar shows link and 0 bytes WAN.
2. 0:45 Private Data Union: "Where is the warranty bill for my laptop?" Snippets stream with [Phone B: IMG_...] and [Phone A: ...pdf]; tapping a citation opens the photo on B. Then a Hindi query. Then show B's private folder withheld in the audit log.
3. 2:15 Agent Choir: a calibrated problem; lanes stream, chips disagree, R2, tool check, 3/3 verified; baseline lane beside. Scoreboard from the 20-problem set.
4. 3:45 MemoryCloud: load Qwen3-14B split; tokens stream; show one phone cannot load it; pull B's Wi-Fi, degraded message; reconnect, resumes.
5. 5:15 Close: SwarmOS is the coordination layer; ExpertMesh slide; emergency footnote.

## 10. Open questions for the founder (answer in HANDOFF or gate-1)

1. Which hackathon track: Productivity or Developer Tools? (affects the Choir task and the data-union corpus)
2. Does the organizer allow multiple iQOO phones on stage, and does HackTracker telemetry need the Office Kit? (docs/research/06)
3. Third phone ETA (decides 14B vs 30B-A3B for G5).

## 11. Source map

docs/research/01 models and runtimes, 02 networking, 03 distributed inference, 04 RAG, 05 multi-agent, 06 hackathon and landscape. docs/device-probes raw output. docs/benchmarks measured numbers.
