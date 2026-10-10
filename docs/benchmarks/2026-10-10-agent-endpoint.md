# Agent endpoint on a phone: acceptance checks (2026-10-10, 11:37 to 11:55)

What was checked: the nine success criteria of `plans/2026-10-10-agent-endpoint.md`, with `scripts/agent-check.py`, on Phone C (iQOO 15, 192.168.242.76), debug build `com.swarmos.app`. Checks a to j ran on the branch build at commit 44fc02d (installed 11:37); that code was then merged unchanged as master df102b8 (830 unit tests, 0 failures; debug and demo APKs built 11:47), installed on phones A, B and C at 11:49, and checks a, b, c and e were repeated on it. Chat `Qwen3.5-4B-Q4_K_M` on the GPU and embedder `embeddinggemma-300M-Q8_0` on all three phones, all three online on the shared Wi-Fi, Thermal Status 0 at every read. The PC reached the phone's loopback port through `adb forward tcp:8790 tcp:8790`; one check ran `curl` on the phone itself. Agent access was switched on with a test key through the debug-only intent extras and switched off again at the end. Raw answers: `raw/agent-endpoint/` (run2 is the full run on 44fc02d, run3 the repeat on df102b8). One run of each check.

Test prompt for the timed calls: "Explain how a heat pump works, in about 80 words.", `max_tokens` 120, temperature 0, 87 tokens generated every time.

| # | Check | Result |
|---|---|---|
| a | Key | no key 401, wrong key 401, right key 200; models listed: `ustaad-local`, `ustaad-swarm` |
| b | Reachable from the Wi-Fi? | no: TCP connect from the PC to 192.168.242.76:8790 refused |
| c | Another app's identity on the phone | `curl` run as UID 2000 (adb shell) on the phone got HTTP 200 and a real answer |
| d | Chat, not streamed | HTTP 200 in 6.99 s, `system_fingerprint` "ustaad:Phone C" |
| d | Chat, streamed | first token 0.39 s, total 6.64 s, 13.9 tokens/s after the first token, ends with `[DONE]` |
| d | `response_format` json_schema | HTTP 200 in 2.77 s; the answer parsed as `{"city": "Tokyo", "population_millions": 13.96}` |
| d | Embeddings | two inputs, two vectors of 256 numbers, 0.09 s |
| e | `ustaad-swarm`, streamed | first token 0.71 s, total 7.00 s; fingerprint "ustaad:swarm" (a streamed swarm answer does not name the phone) |
| e | `ustaad-swarm`, not streamed | HTTP 200 in 5.20 s, ran on "ustaad:Phone A"; on the repeat it ran on "ustaad:Phone C" |
| f | Two requests at once | one alone 6.60 s; together 6.68 s and 12.93 s: the second waited for the first |
| g | Refusals | `tools` 400, `n` = 2 400, image part 400, 65 messages 400, unknown model 404, body over 1 MiB 413 |
| h | The OpenAI Python client, unmodified, `base_url` set to the phone | chat 6.88 s, 80 tokens; streamed first token 0.65 s, 54 chunks; embedding 256 numbers; `models.list()` returned both names |
| i | Ustaad not in front (home screen in front, checked with `dumpsys window`) | 5 s, 60 s and 180 s after pressing Home: first token 0.34, 0.35, 0.38 s; total 6.59, 6.67, 6.65 s. In front just before: 0.72 s and 7.00 s |
| j | Switch | off: the port closes (connection dropped); on again: HTTP 200 |

Beside the node API (criterion 8): the same prompt through the signed route `POST /v1/sched/chat` on Phone C took 5.43 and 5.47 s wall-clock (87 tokens, 17.9 tokens/s decode) at 11:20, when the endpoint took 5.15 to 5.36 s not streamed and 5.19 s streamed (17.7 tokens/s) on the build before the review fixes. No overhead was measurable. The phone alternated between about 14 and about 18 tokens/s across this session with no change of build or thermal status; the cause was not looked for, so compare rows only within one column of time.

## The model's own ports
Before this work the app's llama-server processes answered any local caller on 127.0.0.1:8080 (chat) and 8081 (embeddings) with no key, so a local app could skip the endpoint's key, the one-at-a-time gate and the audit log. The servers now start with a random key made at each app start. Measured on Phone C from UID 2000 with no key: `/v1/models` 401, `/v1/chat/completions` 401, `/v1/embeddings` (port 8081) 401, `/health` 200. On phones A and B after the install: port 8080 without a key 401, port 8790 closed (agent access is off by default).

## Existing features on the changed build
- Data Union ask on Phone C: electricity bill answered from Phone C's photo, sources from C, A and B, first token 5.7 s, total 8.1 s, not incomplete.
- MemoryCloud, small split as a check (Qwen3.5-4B, Phone C driving with the new key, Phone A lending, over the access point): READY in 26.8 and 30.0 s, 77 tokens at 5.1 tokens/s, first token 5.5 s; models restored on both phones after Stop. The 30B split was not repeated on this build.
- Three-phone batch posted to Phone B on df102b8: 32 of 32 jobs, 0 failed, 22.5 s, 39.8 tokens/s aggregate, 11 / 11 / 10 jobs, 18.1 to 18.3 tokens/s decode per phone.
- Settings screen with the Agent access rows: `docs/design/qa4/01-settings-agent-access-landscape.png` (the phone was lying on its side; the portrait layout was not captured).

## Not measured
- A real third-party Android app as the caller (the checks used `curl` under another UID on the phone, and clients on the PC through adb). An Android client needs cleartext to 127.0.0.1 allowed in its own manifest (docs/AGENT-API.md).
- Background behaviour beyond 3 minutes, with the screen off, or on battery: the phone was charging and the battery exemption has not been granted by hand. OriginOS has frozen the app in the background in earlier sessions.
- The demo build (`com.ustaad.app`). It contains the same code; the switch there can only be set on the screen, which was not done.
- Load from many clients, long prompts near the context limit, a client that stops reading (unit-tested only), the 429 limits (unit-tested only).
- `usage.prompt_tokens` reports the tokens the model processed after its prompt cache (27 on the first call, 4 on a repeat of the same prompt), not the length of the prompt.

## After the run: the switch came back on
Found after the checks: agent access came back on by itself on Phone C about ten minutes after it had been switched off. The activity had been recreated (the phone was lying on its side and rotated) and the debug-only launch extras of the test, which said "on" with the test key, were applied again. Fixed in 3206cb4: launch extras are applied only on a fresh start. The app was rebuilt (830 unit tests, 0 failures), installed on phones A, B and C at 11:58 with agent access off and no agent extras in the launch, and the port was closed on all three afterwards. The recreation itself was not triggered again on a phone, so the fix is verified by reading the code, not by a run. The demo build never honoured these extras.

## Mistakes made during the run, for the record
The first attempt to install the fixed build failed silently (a path the Windows adb could not read), so one full run of the checks hit the previous build; it was noticed from the install time and the missing key on port 8080, the build was installed properly and the run repeated. Results of that stray run are not used. One Data Union call used a wrong field name and returned HTTP 500; repeated with the right field.
