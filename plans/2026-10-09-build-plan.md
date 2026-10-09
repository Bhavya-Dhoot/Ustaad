# Build plan — SwarmOS finale build (2026-10-09 to 2026-10-11)

Constraint: wall-clock (48 h) and correctness on two specific phones. Not cost, not context. Shape: short gates, each closed by a measured number, workers in parallel only when files do not overlap.

Failure modes and what catches each:
- Worker returns plausible but unrun code: swarm-device-tester runs it; a gate does not close without its table.
- llama.cpp rpc does not work on Android: 2-hour spike at the start of G5 with a hard stop; the fallback is written in PRD section 7.
- Hotspot or Direct slower than the 55 Mbps baseline: the G1 table shows it; Choir and Data Union still ship; the MemoryCloud claim is reduced to shard distribution + failover.
- OriginOS kills the worker mid-demo: 30-minute idle soak test in G1 and again in G6.
- Worker times out or errors: Opus re-briefs with a smaller file list, never a third identical retry.

Ledger (delegate vs build):
- G0 research: 6 Sonnet subagents (done). Device probes and throughput: direct, because observability on hardware was needed.
- PRD, agent harness, plan, gate-0: orchestrator (plan authorship tier).
- G1-G6 code: Sonnet workers per module. Opus writes glue and gate artifacts only.
- Gate reviews: swarm-reviewer (read-only).

Steps:
1. G1, verify: in-app Mbps and RTT table for hotspot and Direct in docs/benchmarks/, both phones.
2. G2, verify: tok/s and RAM-ceiling table, 10-minute sustained, both phones; docs/models.md pinned.
3. G3 and G4 in parallel, verify: 20-problem Choir table; warranty-bill demo timed on video.
4. G5, verify: split-model tokens/s, baseline failure shown, pull-a-phone recovery.
5. G6, verify: full 6-minute rehearsal recorded, thermal status logged, 0 WAN bytes.
