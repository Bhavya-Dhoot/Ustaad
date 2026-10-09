# Gate 0 — Research and PRD (closed 2026-10-09 by the Fable orchestrator session)

## Decisions
1. Scope locked to SwarmOS + SwarmLink + Agent Choir + MemoryCloud + Private Data Union + ExpertMesh (stretch). Others dropped; Blackout Cloud is a footnote.
2. One inference runtime: llama.cpp (ggml) via JNI. LiteRT-LM is the fallback if G2 decode is under 10 tok/s for the 4B class.
3. Models: Qwen3.5-4B Q4 (solver), a second family for the alt-solver (Gemma 4 E2B or Granite 4.2 3B), EmbeddingGemma 2 (fallback Qwen3-Embedding-0.6B), Qwen3-14B Q4 for the 2-phone split, Qwen3-30B-A3B Q4 for 3 phones. Exact files pinned at G2.
4. SwarmLink primary = phone hotspot 5 GHz, fallback Wi-Fi Direct, then LAN. No Wi-Fi Aware (absent on the phones).
5. MemoryCloud = layer-range pipeline only. Tensor parallel, live weight paging, and intra-layer remote experts rejected on bandwidth and RTT math (docs/research/03).
6. Agent Choir = sampling + tool verification; the claim is throughput and verification, not intelligence, unless the 20-problem table shows otherwise.
7. Private Data Union sends query text, never embeddings; only snippets return; share policy per folder.

## Deltas from the original idea list
- "Wi-Fi 7 peer-to-peer" is not available to apps on these phones; design for 11ac/ax-class links. Current shared-AP path measured at 50-61 Mbps, 11 ms RTT.
- MemoryCloud's "RAM over Wi-Fi" framing is replaced by shard distribution + pipeline.
- ExpertMesh is a presentation of the same pipeline on a MoE model, not a separate system.

## Open risks (carried to G1)
- Native RAM ceiling per app unmeasured.
- llama.cpp rpc on Android unproven.
- Hotspot and Direct throughput unmeasured (only the shared-AP baseline exists).
- OriginOS background killing unmitigated until the foreground service exists.
- Hackathon track, multi-phone stage rules, third phone ETA: founder to answer (PRD section 10).

## Ledger
- Research: 6 Sonnet subagents, about 950k tokens total, reports in docs/research/01..06.
- Device probes, throughput probe (NDK C tool), benchmark doc: orchestrator, direct.
- PRD, agents, plan, this gate: orchestrator.

## Artifacts
CLAUDE.md, HANDOFF.md, docs/PRD.md, docs/research/01-06, docs/device-probes/iqoo-A*, iqoo-B*, docs/benchmarks/2026-10-09-swarmlink-lan-baseline.md, scripts/probe-device.sh, scripts/adb-connect.sh, scripts/tput/, .claude/agents/.
