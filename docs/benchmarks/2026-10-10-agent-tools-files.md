# Agent endpoint version 2 on the phones: tool calling and file search (2026-10-10, 13:07 to 13:12)

What was checked: the seven success criteria of `plans/2026-10-10-agent-tools-files.md`, with `scripts/agent-check2.py` (new checks) and `scripts/agent-check.py` (version 1 checks), on the debug build of master c2ca589 (970 unit tests, 0 failures; debug and demo APKs built 13:05; installed on phones A, B and C at 13:07). Phone C (192.168.242.76) was the phone under test; Phone B (192.168.242.23) ran the private-file check; Phone A answered as a paired phone. Chat `Qwen3.5-4B-Q4_K_M` on the GPU and embedder `embeddinggemma-300M-Q8_0` on all three, all three online on the shared Wi-Fi, Thermal Status 0 at every read. The PC reached the phones' loopback port through `adb forward`. Agent access and file search were switched on with a test key through the debug-only intent extras and switched off again at the end. Raw answers: `raw/agent-tools-files/`. One run of each check on this build; earlier runs on the branch builds are named where they matter.

## Tool calling
Three tools were declared in every request of the selection test: `get_weather(city, unit)`, `set_alarm(time, label)`, `search_files(query)`. Temperature 0.

| Check | Result |
|---|---|
| Tool selection, 12 prompts (8 need a tool, 4 do not) | right tool 8 of 8; right key argument 8 of 8 (city, time or search word); answered directly 4 of 4; all arguments parsed as JSON. 1.2 to 5.1 s per request, mean 2.5 s |
| `tool_choice: "required"` on a question that needs no tool | a tool call was returned (`search_files`) |
| `tool_choice: "none"` on a weather question | an answer in words, no call |
| `tool_choice` naming `set_alarm` ("Remind me at seven in the evening.") | `set_alarm({"time": "19:00", "label": "Reminder"})` |
| Two steps: question, tool call, tool result, answer | `get_weather({"city": "Bengaluru", "unit": "celsius"})`, then "The current weather in Bengaluru is 24°C with light rain."; 6.0 s for both calls |
| Unmodified OpenAI Python client, streamed, with tools | 2 events, assembled into `set_alarm({"time":"21:15","label":"medicine"})`, finish reason `tool_calls`; not streamed: `get_weather({"city":"Mumbai","unit":"celsius"})` |
| Refusals | unknown named tool 400; duplicate tool names 400; `tools` with `response_format` 400; a parameter schema with `maxItems` 100000: 400 `limit_exceeded`; an external `$ref`: 400 `unsupported_parameter` |

How the calls are produced matters, and it changed during the day because of a measurement. The first build constrained the output to a JSON shape of our own. On the 12 prompts it scored the same 8 of 8 and 4 of 4, but in a real loop it failed: asked "Where is the warranty bill for my laptop?" with only `search_files` offered, the model said it had no access to the user's files, and with a caller's system message it invented a file name instead of calling the tool. The same prompts in the tool format the model was trained on (a `<tools>` block in the system message, `<tool_call>` blocks in the answer, no constraint) gave the right call 20 of 20 times. The endpoint now uses that format when `tool_choice` is "auto" and keeps the schema-constrained path for "required" and a named tool. With "auto", arguments are checked for valid JSON and required keys; a call that fails the check is repaired once through the constrained path. No repair was needed in this run.

## File search
| Check | Result |
|---|---|
| File search switched off | HTTP 403 `file_search_off` |
| Scope "phone" on Phone C, "When is my electricity bill due?" | `IMG_3301_Electricity_Bill.jpg` on Phone C, 0.11 s |
| Scope "swarm" from Phone C, "warranty bill for my laptop" | certificate on Phone A, bill photo `IMG_2231.jpg` on Phone B, two weaker hits; all three phones "ok"; 0.19 s |
| Private file, across the swarm: "What is my salary for September 2026?" from Phone C | four unrelated files from C and A; nothing from Phone B's Private folder |
| Private file, on the phone that owns it: the same question and "salary slip" through the endpoint on Phone B | no result, and one unrelated photo; never the salary slip and never the "(private file on this phone)" placeholder |
| The same question asked by the owner in the app's own API on Phone B (run at 12:41 on the branch build) | the private file is the first source and the answer states the salary: the file is there and is the best match, and the endpoint still withholds it |
| Shape of an answer | keys per hit: `phone`, `title`, `date`, `kind`, `snippet`; no path, URI hash, id, score, embedding or withheld count anywhere in the JSON; longest snippet 398 characters |
| Limits | empty query 400; 1001 characters 400; unknown scope 400; `max_results` 50: 400 |

## The whole loop from another process (the model decides, the caller runs the search)
The caller declared one tool, `search_files(query)`, with a system message asking for an answer that names the file and the phone. When the model called the tool, the script ran `POST /v1/files/search` with scope "swarm" and sent the result back as the tool message.

| Question | Model's call | Search | Final answer |
|---|---|---|---|
| Where is the warranty bill for my laptop? | `search_files("laptop warranty bill")`, 2.8 s | 3 hits from phones A and B, 0.14 s | names Phone B and `IMG_2231.jpg`, 6.2 s |
| When is my electricity bill due and how much is it? | `search_files("electricity bill due date amount")`, 2.0 s | 4 hits, 0.20 s | 24-Sep-2026 and INR 2,410.00, from `IMG_3301_Electricity_Bill.jpg` on Phone C, 7.4 s |
| When is the yoga class? | `search_files("yoga class schedule time")`, 2.0 s | 5 hits, 0.13 s | the schedule from `Yoga_Class_Schedule.txt` on Phone B, 7.9 s |

3 of 3 loops ended in an answer with the expected fact, in about 9 to 10 s each (the three steps added). The same three loops on the branch build at 12:54 took 13 to 15 s.

## Version 1 checks on this build
Checks a to h and j of `scripts/agent-check.py` all passed on Phone C: key (401, 401, 200), port refused from the Wi-Fi, another UID on the phone answered, chat (6.9 s), stream (first token 0.40 s, 13.9 tokens/s), JSON to a schema, embeddings (256 numbers), `ustaad-swarm`, two calls at once (6.9 s and 13.5 s against 6.6 s alone), refusals, the OpenAI client, the switch. Check i (3 minutes in the background) was not repeated on this build.

## Not measured
- A real third-party Android app or agent framework as the caller.
- Tool selection beyond these 12 prompts and 3 tools: many tools at once, long tool descriptions, more than one call in an answer, a second search after a poor first one, languages other than English. With 12 prompts one miss would be 8 points, so "8 of 8" is a small sample, not a rate.
- The repair path and the 429 limits on a phone (unit-tested only). A paired phone that is offline during a swarm search (unit-tested only).
- The owner's own Ask after this merge: it was run on the branch build that already contained the change to the Data Union's peer call (12:41, sources from all three phones), and that code did not change afterwards; it was not run again on c2ca589 because Phone A was reserved by the founder at 13:12.
- A three-phone batch on c2ca589, for the same reason.
- Timing as a side channel: a key holder can measure how long a search takes, and the search time covers private files too.

## State left on the phones
Agent access and file search off on all three (the default). Phones B and C were restarted cleanly at 13:13 so that no test setting stays in their launch state; the agent port is closed on both and the model port answers 401 without its key. Phone A was not touched after 13:12; it carries the same build, launched at 13:07 with agent access off.
