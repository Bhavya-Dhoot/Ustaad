# Brief: Model runtime (G2) — swarm-inference-engineer

Task (one sentence): Build the `llm` package so the app ships llama.cpp's llama-server and rpc-server binaries, launches them as managed processes, manages GGUF models, and implements LlmClient against the local server, with in-app tokens/s measured on the phones.

Read first: CLAUDE.md, docs/CONTRACTS.md (all), .claude/agents/swarm-inference-engineer.md, plans/briefs/_common.md, docs/PRD.md section 5.2, docs/llama-server-api-notes.md (exact server flags and request shapes, written from runs on these phones), docs/models.md, docs/benchmarks/2026-10-10-llama-cpu-baseline.md. Then the scaffold code: app/app/src/main/java/com/swarmos/app/AppGraph.kt, core/*, llm/*.

Build flags: none (base id com.swarmos.app, offset 0). You are the only worker installing the base id right now.

You own: app/app/src/main/java/com/swarmos/app/llm/** , app/app/src/test/java/com/swarmos/app/llm/** , app/app/src/main/jniLibs/arm64-v8a/** , docs/benchmarks/2026-10-10-inapp-llm.md.

Build:
1. Copy native/prebuilt/arm64-v8a/libllama-server.so and librpc-server.so into app/app/src/main/jniLibs/arm64-v8a/. They are executables named lib*.so so the installer extracts them to applicationInfo.nativeLibraryDir, the only place an app may exec from.
2. LlamaRuntime: `start(role = CHAT | EMBED | RPC, modelPath, extraArgs)` launches the binary with ProcessBuilder from nativeLibraryDir (LD_LIBRARY_PATH = nativeLibraryDir), captures stdout/stderr to a ring buffer and to filesDir/logs/<role>.log, waits for /health, exposes state (STOPPED, STARTING, READY, FAILED + reason) as StateFlow, `stop(role)` kills the process, and everything stops when SwarmService stops. Ports from core/Ports.kt. Thread flags from the API notes. Runs only while the foreground SwarmService is alive. If the system kills child processes (phantom process killer or OriginOS), capture the logcat evidence and report; do not change system settings; propose the in-process JNI fallback instead of building it.
3. LlamaServerClient : LlmClient — streaming through SSE from /v1/chat/completions (parse deltas, map the final timings object to ChatStats), non-streaming chat, jsonSchema through response_format, thinking disabled the way the API notes say, embed through /v1/embeddings on the EMBED server, truncated to 256 dims and L2-normalised. Wire AppGraph.llm / AppGraph.embedder to the real clients when the servers are READY and back to FakeLlmClient when stopped.
4. ModelStore: catalog from docs/models.md (role, file name, URL, bytes, sha256), models dir per CONTRACTS 9.5, list present files, resumable download with progress and SHA-256 check (this is the app's only internet use; count the bytes and expose them as a "WAN bytes" StateFlow for the StatsBar), delete.
5. ModelsModule screen (id "models"): catalog with size and state, Download / Load as chat / Load as embedder / Stop, server state and last log lines, a Bench button that runs a fixed 512-token prompt plus 128-token generation and shows prefill and decode tok/s, RSS of the server process (/proc/<pid>/status VmRSS) and thermal status. Routes: GET /v1/models/state (roles, model names, server states, last tok/s), POST /v1/models/load {role, file}, POST /v1/models/stop {role}, POST /v1/models/bench. Loading the default chat and embedding models automatically at service start when present is required (the phones must come up ready with no taps).
6. Push the small models from H:\UStaaf\models to each phone's models dir with adb (C over USB is fastest) rather than downloading on the phones.

Verify on the phones (A, B, C):
- App installs, service starts, chat model loads, /v1/models/state says READY; a streamed chat renders in the Bench UI; decode tok/s within 20 % of the standalone number in docs/benchmarks/2026-10-10-llama-cpu-baseline.md (explain if not).
- A JSON-schema request returns schema-valid JSON 10 times out of 10.
- The embedding server returns 256-dim unit vectors; cosine("warranty bill for laptop", "laptop purchase receipt") is higher than the cosine with an unrelated sentence.
- Chat and embed servers run at the same time; report total RSS.
- 10-minute test with the screen off and the app in the background: are the child processes still alive? Report logcat evidence either way.
- Write docs/benchmarks/2026-10-10-inapp-llm.md (per phone: model, prefill, decode, RSS, thermal).
