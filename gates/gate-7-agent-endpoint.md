# Gate 7 — Agent endpoint (closed 2026-10-10 by the orchestrator: another app's process on the phone gets real answers through a keyed, loopback-only OpenAI-format endpoint)

Added on the founder's instruction of 2026-10-10 ("build the agent endpoint so other apps can use it"), which reopened the scope lock in CLAUDE.md for this one item. Criterion: the nine success criteria in plans/2026-10-10-agent-endpoint.md, each checked on a phone. All nine hold. Evidence: docs/benchmarks/2026-10-10-agent-endpoint.md (checks a to j with scripts/agent-check.py on Phone C; raw answers in docs/benchmarks/raw/agent-endpoint/), master df102b8 for the checks and 3206cb4 for the build now on the phones (one later fix, below), 830 unit tests with 0 failures, debug and demo APKs built, debug build on phones A, B and C since 11:58 with agent access off.

## Decisions
1. The endpoint is a second listener inside the app on 127.0.0.1:8790 that speaks the OpenAI chat format (`/v1/models`, `/v1/chat/completions` with streaming and `json_schema`, `/v1/embeddings`). An app or agent framework that can talk to an OpenAI-style server needs only a base URL and a key. How to call it: docs/AGENT-API.md.
2. Off by default. The owner switches "Agent access" on in Settings; that makes a key and opens the port. Switching it off closes the port and forgets the key.
3. Two models: `ustaad-local` (this phone's chat model through the same one-at-a-time gate as every other feature) and `ustaad-swarm` (the scheduler picks a phone; the prompt then travels to paired phones, and Settings says so).
4. Version 1 gives other apps the model only. No tool calling, no Data Union search, no Knowledge Bank, no large split model, no access from other devices. File search for other apps would open the owner's files to whoever holds the key; it needs the founder's explicit yes and its own review.
5. The app's own model servers (ports 8080 and 8081) now require a random key made at each app start. Before this they answered any local caller, which would have made the endpoint's key meaningless.
6. The placeholder model never answers another app: without a real model the endpoint returns 503.

## Measured (Phone C, real models, three phones online; one run each)
| What | Value |
|---|---|
| Key | no key 401, wrong key 401, right key 200 |
| From another device on the Wi-Fi | connection refused |
| Another UID on the phone (curl as adb shell) | HTTP 200 with a real answer |
| Streamed chat | first token 0.39 s, 87 tokens in 6.6 s |
| JSON to a schema | parsed, 2.8 s |
| Embeddings | 256 numbers per input, 0.09 s for two |
| `ustaad-swarm` | answered; the non-streamed call named the phone that ran it (Phone A, then Phone C) |
| Two calls at once | the second waited: 6.7 s and 12.9 s against 6.6 s alone |
| Unmodified OpenAI Python client | chat, stream, embeddings and model list all worked |
| Ustaad in the background for 3 minutes | same speed as in front (first token 0.34 to 0.38 s) |
| Overhead against the signed node API | none measurable (5.2 to 5.4 s against 5.4 to 5.5 s) |
| Model ports without the new key | 401 on chat, models and embeddings; health 200 |
| Existing features after the change | cross-phone question, small MemoryCloud split and three-phone batch (39.8 tok/s) all ran |

## Deltas from plan
- The independent review found no blocker in the new code and one older hole (decision 5), which was fixed in the same gate although it touches the model runtime outside the plan's file list.
- A streamed `ustaad-swarm` answer says "ustaad:swarm" instead of naming the phone.
- Added beyond the contract, all refusals or compatibility: base64 embeddings (the OpenAI client asks for them), a Host header check, 429 limits, a 16 KiB schema cap, a 30 s write timeout, a 300 s idle timeout.

## Open risks
- Found after the checks: agent access came back on by itself on Phone C about ten minutes after it had been switched off. The activity had been recreated (the phone was lying on its side and rotated) and the debug-only launch extras of the test, which said "on" with the test key, were applied again. Fixed in 3206cb4: launch extras are applied only on a fresh start. The app was rebuilt (830 unit tests, 0 failures), installed on phones A, B and C at 11:58 with agent access off and no agent extras in the launch, and the port was closed on all three afterwards. The recreation itself was not triggered again on a phone, so the fix is verified by reading the code, not by a run. The demo build never honoured these extras.
- No real third-party Android app has called it. The checks used curl under another UID on the phone and clients on the PC through adb. A real client must allow cleartext to 127.0.0.1 in its own manifest.
- Background use was measured for 3 minutes with the phone charging and the screen on. OriginOS has frozen the app in the background before; the battery exemption still has to be granted by hand.
- The 30B split was not repeated on the build with the model key; a 4B split was, and it generated.
- A key holder can keep the model busy and heat the phone; calls are limited to one at a time and 1024 tokens, not by a quota.
- `ustaad-swarm` has no upper wait when every phone is busy.
- On the demo build the switch can only be set on the screen; that path was not exercised.
- In debug builds any app on the phone can switch agent access and set the key through the intent extras. The phones carry the debug build.
- Two helper scripts under native/bench call port 8080 directly and will now get 401 against an app-started server.

## Ledger
- Implementation, review fixes and the model-key change: one Sonnet builder in its own worktree (about 670k tokens over two runs). Trust-boundary review: one read-only Sonnet reviewer (about 150k tokens). Acceptance script, all phone checks, merge, benchmark note and this artifact: orchestrator.
