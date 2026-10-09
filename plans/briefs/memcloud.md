# Brief: MemoryCloud + ExpertMesh (G5/G6) — swarm-inference-engineer

Task (one sentence): Build the MemoryCloud module (package `com.swarmos.app.memcloud`) so one phone drives a model split by layer range across two or three phones using llama.cpp's RPC backend inside the app, shows where every layer lives and the measured tokens/s, and degrades cleanly when a phone drops.

Read first: CLAUDE.md, docs/CONTRACTS.md (all), .claude/agents/swarm-inference-engineer.md, plans/briefs/_common.md, docs/PRD.md sections 5.5 and 5.6, docs/benchmarks/2026-10-10-rpc-split-spike.md (the exact rpc-server and --rpc command lines that worked on these phones and their measured numbers; build on exactly those), docs/llama-server-api-notes.md, docs/models.md. Then the code: app/app/src/main/java/com/swarmos/app/AppGraph.kt, core/*, llm/*, link/NodeClient.kt, link/NodeServer.kt, memcloud/*.

Build flags: none (base id com.swarmos.app). You are the only worker installing the base id now.

You own: app/app/src/main/java/com/swarmos/app/memcloud/** , app/app/src/test/java/com/swarmos/app/memcloud/** , docs/benchmarks/2026-10-10-memorycloud-inapp.md. Use llm/LlamaRuntime through its public API; if it lacks something (extra args, RPC role, log tail), make the minimal edit and list it.

Build:
1. Worker side: signed POST /v1/memcloud/rpc/start {port, cache: true} starts rpc-server through LlamaRuntime (role RPC) bound to the link interface with the on-disk tensor cache enabled; POST /v1/memcloud/rpc/stop; GET /v1/memcloud/rpc/state (state, RSS MB, bytes cached, last log lines).
2. Coordinator side: pick a model file present on this phone, pick peers (online, from AppGraph.registry), compute the split (layers per device proportional to each phone's available RAM; default equal), start the rpc-servers on the peers, then start llama-server (role CHAT) with the --rpc list and the layer-placement flags proven in the spike. Parse the load log for per-device layer assignment and progress. Expose state: PLANNING, STARTING_PEERS, LOADING (bytes sent per peer, percent), READY, DEGRADED(peer), FAILED(reason). Signed routes POST /v1/memcloud/start {file, peers} and /stop and GET /v1/memcloud/state so it can be driven without touching the screen.
3. Chat on the split model through the normal LlmClient (so Choir and Data Union can use it), streamed in the MemoryCloud screen with decode tok/s, time to first token, per-hop RTT from the heartbeat, and each phone's RSS and thermal status (poll the peers' /rpc/state and /v1/health).
4. Baseline panel: "Try on this phone alone" attempts to load the same model locally with no RPC and reports honestly what happened (failed to allocate, killed, or tok/s), with a timeout; and shows the small single-phone model's tok/s for scale.
5. Failure handling: if a peer's heartbeat goes away or the server errors mid-generation, stop the stream, show DEGRADED with the phone's name, and offer "Re-plan with remaining phones". No crash, no hang beyond 10 s.
6. ExpertMesh section: for the mixture-of-experts model, show experts per layer and active experts per token from the GGUF metadata, the layer ranges per phone, and a short explanation with the measured per-hop RTT: remote experts inside a layer would cost one round trip per MoE layer per token (show the computed tokens/s ceiling from the measured RTT), which is why experts are grouped by layer range. Numbers on this screen come from the model file and live measurements only.

Verify on the phones:
- With the small 4B model split across A (driver) + B, then A + B + C: READY, a coherent streamed answer, tok/s, RSS per phone; the second load is faster thanks to the cache (report both load times).
- With the large MoE model (docs/models.md) driven from the phone that holds the file: the three-phone split loads and generates; record load time, time to first token, decode tok/s over a 200-token answer, RSS per phone, thermal after 5 minutes of generation. Then the single-phone attempt and what happened.
- Pull test: force-stop the app on one peer mid-generation: DEGRADED within 10 s, re-plan works.
- Write docs/benchmarks/2026-10-10-memorycloud-inapp.md with every number and the exact server arguments used.

Screen reference: ui-kit/screens/memorycloud_plan_split, memorycloud_loading, memorycloud_running, memorycloud_phone_dropped, expertmesh_explainer (read ui-kit/README.md first: the layer strip is ONE row of small cells, phones are Phone A/B/C, model names come from the GGUF file, no invented labels).
