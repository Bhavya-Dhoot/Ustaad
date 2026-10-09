# Brief: SwarmOS scheduler, use every phone — swarm-android-builder

Founder requirement (2026-10-10): optimise heavily for throughput and for the phones working with each other; use all resources of all phones consistently.

Task (one sentence): Build the SwarmOS scheduler (package `com.swarmos.app.sched`): any phone can run a model request on any other phone through one `LlmClient`, a load-balanced client spreads requests over all healthy phones according to measured capability, and a batch runner pushes many requests through the whole swarm with exactly-once results and a measured aggregate tokens/s.

Read first: CLAUDE.md, docs/CONTRACTS.md (all), plans/briefs/_common.md, docs/PRD.md section 5.7. Then the code: app/app/src/main/java/com/swarmos/app/AppGraph.kt, SwarmModule.kt, core/*, llm/LlmClient.kt, llm/FakeLlmClient.kt, link/NodeClient.kt, link/NodeServer.kt.

Build flags: `-PidSuffix=.sched -PportOffset=50`.

You own: app/app/src/main/java/com/swarmos/app/sched/** and app/app/src/test/java/com/swarmos/app/sched/** only. Expose one `object SchedModule : SwarmModule` (id "sched", title "Swarm load"); the orchestrator decides later whether it gets a tab or is embedded in the Swarm screen, so also expose a standalone `@Composable fun ThroughputPanel()`.

Peers: read online peers from AppGraph.registry; the discovery module is built by another worker, so add a signed POST /v1/sched/debug/peers {ips: [...]} that calls GET /v1/hello on each and registers them.

Build:
1. Remote execution: signed POST /v1/sched/chat {jobId, request: ChatRequest} runs the request on this phone's AppGraph.llm and returns {jobId, text, stats, nodeId, modelName}; POST /v1/sched/embed likewise for embeddings. A phone runs at most `capacity` jobs at once (default 1; read from a setter so it can follow the model server's parallel slots later) and answers 429 with its queue length when full. Completed jobIds are remembered for 10 minutes and a repeated jobId returns the stored result instead of running again. `RemoteLlmClient(peer) : LlmClient` wraps these routes.
2. Capability and health: GET /v1/sched/caps returns {nodeId, modelName, capacity, running, queued, lastDecodeTokPerSec, thermalStatus, batteryPct, charging, ramAvailMb}. `Scheduler.score(caps)` ranks phones: faster measured tokens/s is better; a phone at thermal status 2 or more, or below 15 % battery and not charging, is used last; a phone whose model differs from the requested model is skipped when the caller pins a model. Unit-test the scoring with fixed inputs.
3. `SwarmLlm : LlmClient` (the whole swarm as one client): each call goes to the best-scored phone with free capacity (this phone included), waits when all are busy, and on timeout or connection failure retries the same jobId on the next phone. Streaming calls run locally or fall back to non-streaming remote.
4. `BatchRunner`: takes a list of ChatRequests, runs them through SwarmLlm with one in-flight job per free slot across all phones, returns results in input order exactly once, and measures wall-clock, total completion tokens, aggregate tokens/s, and per-phone share (jobs, tokens, tokens/s). Routes: POST /v1/sched/batch {requests or builtin: "bench32"} and GET /v1/sched/batch/{id}. Built-in "bench32" = 32 short prompts with a fixed token cap.
5. ThroughputPanel: aggregate tokens/s as the hero number, one row per phone (jobs done, tokens/s, running/queued, thermal), and buttons "Run on this phone only" and "Run on the swarm" that run bench32 both ways and show the two results side by side. Real values only; label FAKE when the model is the fake client.

Verify:
- JVM unit tests: scoring, exactly-once (duplicate jobId), ordered results, failover when one fake phone fails mid-batch, backpressure with capacity 1.
- On the phones with the fake model (the real runtime is built by another worker): install your variant on A, B, C; register peers; run bench32 on one phone and on the swarm: jobs are spread over the three phones, results complete and ordered; force-stop your app on one phone mid-batch: the batch still finishes with every result exactly once. With the fake model report job distribution and wall-clock only (no tokens/s claims).
- Everything is written against LlmClient and NodeClient only, so real models need no change in your package.
