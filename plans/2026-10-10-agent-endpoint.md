# Agent endpoint: other apps on the phone use Ustaad as their model (gate 7)

Founder request, 2026-10-10 10:55: "build the agent endpoint so other apps can use it". This reopens the scope lock in CLAUDE.md on the founder's own instruction. It is built on a branch and merged only after it passes on a phone, so the measured build (master 0a71f33) stays intact until then.

## What it is
A second HTTP listener inside the app, bound to the phone's loopback address only, that speaks the OpenAI chat format. Any app or agent framework on the same phone that can talk to an OpenAI-style server points its base URL at `http://127.0.0.1:8790/v1` with a key the owner copies from Settings. The model behind it is this phone's chat model, or the swarm when other phones are present.

## Contract (version 1)
- Listener: Ktor CIO on `127.0.0.1`, port `Ports.AGENT = 8790 + BuildConfig.PORT_OFFSET`. Never on another interface. Requests whose remote address is not loopback are refused as well.
- Off by default. Settings gets an "Agent access" switch; turning it on generates a key (32 random bytes, base64url) and starts the listener; turning it off stops the listener. Settings shows the base URL, the key with Copy, "New key", and the number of requests served. The listener lives inside the existing foreground service.
- Auth: `Authorization: Bearer <key>` on every route, compared in constant time. Missing or wrong key: 401. No CORS headers.
- Routes, OpenAI shapes for requests, responses and errors (`{"error":{"message","type","code"}}`):
  - `GET /v1/models`: `ustaad-local`, `ustaad-swarm`.
  - `POST /v1/chat/completions`: `model`, `messages` (role and text content; an array of text parts is joined), `max_tokens` or `max_completion_tokens` (default 256, clamped to 1024), `temperature`, `stop`, `seed`, `stream`, `response_format` of type `json_schema` (mapped to `ChatRequest.jsonSchema`). `stream: true` answers as server-sent events ending with `data: [DONE]`. The response carries `usage` and `system_fingerprint: "ustaad:<phone name that ran it>"`.
  - `POST /v1/embeddings`: `input` as a string or a list of strings, answered by this phone's embedder.
  - `ustaad-local` goes through `AppGraph.gatedLlm` (the same one-at-a-time gate as every other model call on the phone). `ustaad-swarm` goes through `Sched.swarm`.
- Refusals: `tools`, `tool_choice`, `n` above 1, image or audio parts: 400 naming the field. Body over 1 MiB: 413. More than 64 messages: 400. No real chat model ready: 503 "No chat model is loaded on this phone" (the placeholder model never answers another app). Model busy past the gate's wait: 503. Unknown model: 404.
- A client that disconnects mid-stream frees the model slot.
- Audit: `agent.start`, `agent.stop`, and one `agent.chat` or `agent.embed` entry per request with model, character and token counts and milliseconds. Never prompt or answer text.
- Debug builds only: intent extras `agentAccess` (boolean) and `agentKey` (string) on MainActivity set the switch and a known key, so tests need no screen taps.
- Not in version 1, on purpose: tool calling, file search (Data Union) and saved answers (Knowledge Bank) for other apps, the large split model, access from other devices. File search for other apps opens the owner's files to whoever holds the key; that needs the founder's explicit yes and its own review.

## Success criteria (each is checked on a phone before the gate closes)
1. With the switch off the port is closed. With it on, a request without the key gets 401 and one with the key gets 200.
2. From another device on the same Wi-Fi the port is unreachable.
3. A process with a different UID on the phone (adb shell) gets a real answer.
4. Non-streamed and streamed chat, a `json_schema` answer that parses, and embeddings all work against the real models.
5. `ustaad-swarm` with three phones online answers and names the phone that ran it.
6. Two requests at once: the second waits for the first (one model call at a time).
7. The same call with Ustaad not in front (another app in front): answered or not, and how much slower. Recorded as measured, whichever way it goes.
8. Time to first token and tokens per second through the endpoint, beside the same request through the node API.
9. Unit tests for auth, limits, request mapping, response shapes and the refusals; the full unit suite stays green; debug and demo APKs build.

## Plan
Constraint: correctness at a trust boundary (another app's process talks to ours), then phone time. So the listener is new code in its own package with its own tests, the model path reuses what is measured, and nothing that touches the owner's files is exposed.
Failure modes: the worker's build breaks master (caught by the full unit suite and the orchestrator's own build before merge); the listener is reachable from the Wi-Fi (caught by criterion 2 on a phone, and by a read-only review of the bind and auth code); a hung or abandoned request holds the model slot (unit test with a cancelled stream, criterion 6 on a phone); OriginOS freezes Ustaad when the calling app is in front (criterion 7 measures it; if it fails the document says so and names the battery exemption); a plausible but wrong report from the worker (the orchestrator runs criteria 1 to 8 itself).
Ledger: implementation delegated to one Sonnet builder in its own worktree (well bounded, verifiable by tests and by the phone checks); trust-boundary review delegated to one read-only Sonnet reviewer (independent read); phone verification, merge, benchmark note and gate artifact kept by the orchestrator (it is the acceptance test, a handful of calls); file search for other apps not delegated and not built (user data, needs the founder).

## Deliverables
- Code in `app/app/src/main/java/com/swarmos/app/agent/` plus the Settings rows, `Ports.AGENT`, the service hook and the debug extras.
- `docs/AGENT-API.md`: how another app calls it (base URL, key, one curl example, one Kotlin example, the cleartext-to-localhost note for Android clients), limits, the trust model.
- `docs/benchmarks/2026-10-10-agent-endpoint.md` and `gates/gate-7-agent-endpoint.md` (orchestrator).
