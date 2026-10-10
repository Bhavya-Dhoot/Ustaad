# Gate 8 — Agent endpoint version 2: tool calling and file search for other apps (closed 2026-10-10 by the orchestrator)

Added on the founder's instruction of 2026-10-10 ("yes build file search and tool calling for other apps"). Criterion: the seven success criteria in plans/2026-10-10-agent-tools-files.md, each checked on a phone. All seven hold. Evidence: docs/benchmarks/2026-10-10-agent-tools-files.md (scripts/agent-check2.py and scripts/agent-check.py on phones C and B; raw answers in docs/benchmarks/raw/agent-tools-files/), master c2ca589, 970 unit tests with 0 failures, debug and demo APKs built, debug build on phones A, B and C since 13:07.

## Decisions
1. Tool calling follows the OpenAI format (`tools`, `tool_choice`, `tool_calls`, `role: "tool"`); the calling app runs the tools. The endpoint runs none.
2. With `tool_choice` "auto" the endpoint speaks the tool format the model was trained on (a `<tools>` block in, `<tool_call>` blocks out) and checks each call for valid JSON, a declared name and the required keys; a call that fails is repaired once through a schema-constrained request. "required" and a named tool are schema-constrained from the start. This replaced a first design (our own constrained JSON for every case) after it failed on a phone: see Deltas.
3. File search is a plain route, `POST /v1/files/search`, behind its own switch "File search for other apps", off by default and only usable while Agent access is on. Turning Agent access off turns it off.
4. What a key holder can get is exactly what a paired phone can get: this phone's part of every search goes through `PeerResponder.respond`, the call that answers a paired phone. Files in private folders never appear, not as a title, a placeholder or a count.
5. Scope: "phone" by default. "swarm" on request also sends the query to the paired phones under the same owner switch; each applies its own rule. This inclusion was the orchestrator's call and is recorded for the founder to overrule.
6. An answer carries phone, title, date, kind and a snippet of at most 400 characters. No score (it is ranked over private chunks too), no path, no hash, no file bytes.
7. The model decides when to search only if the calling app declares a search tool and runs the route itself. docs/AGENT-API.md shows that loop.

## Measured (phones C and B, real models, three phones online; one run each on c2ca589)
| What | Value |
|---|---|
| Tool selection, 12 prompts, 3 tools | right tool 8 of 8, right key argument 8 of 8, direct answer 4 of 4; mean 2.5 s |
| `tool_choice` required, none, named; two-step conversation | all as specified; two steps in 6.0 s |
| Unmodified OpenAI Python client, streamed tool call | assembled correctly, finish reason `tool_calls` |
| File search off | 403 |
| Search on this phone / across three phones | 0.11 s / 0.19 s, right files from the right phones |
| Private salary slip on Phone B | never returned: not across the swarm, not on Phone B itself; the owner's own ask on that phone does find it |
| Forbidden fields in any answer | none |
| Whole loop (model calls `search_files`, caller searches the swarm, model answers) | 3 of 3 correct, about 9 to 10 s each |
| Version 1 checks on this build | a to h and j pass |

## Deltas from plan
- The plan's tool design (constrain every answer to our own JSON shape) passed the 12-prompt selection test and then failed the real loop: the model told the user it had no access to their files, or invented a file, instead of calling the tool. The model's trained format gave 20 of 20 on the same prompts. The design was changed for "auto" (decision 2). Cost: with "auto" the arguments are not grammar-constrained, so they are validated and repaired instead.
- `score` was in the contract and was removed after review (decision 6).
- The review found no blocker. Four medium findings were fixed before the merge: the file-search switch is re-read before searching and before answering; deeply nested JSON inside past tool-call arguments is refused; a schema rejection is told apart from other model-server errors; large numeric bounds and external `$ref` in schemas are refused.
- An empty index answers 200 with an empty list instead of 503.

## Open risks
- A key holder can search every file the owner shares with the swarm, on this phone and (with scope "swarm") on the paired phones, and can do so repeatedly. The key is the only barrier. The Settings text says so; whether an owner reads it is another matter.
- A paired phone's owner cannot tell a search made by an app on another phone from a question asked by a person: the audit line is the same. Marking it would change the wire format; not done.
- Search time, the names of the paired phones and whether each answered are visible to a key holder.
- Tool selection was measured on 12 prompts with 3 tools in English. Nothing is known about many tools, long descriptions, several calls in one answer or other languages. The repair path has not fired on a phone.
- No real third-party Android app or agent framework has used it. Checks used clients on the PC through adb and, for version 1, curl under another UID on the phone.
- After the merge, the owner's Ask and a three-phone batch were not re-run on c2ca589: Phone A was reserved by the founder at 13:12. Ask ran at 12:41 on the branch build that already contained the only change to the Data Union.
- In debug builds any app on the phone can switch agent access and file search on through the intent extras. The phones carry the debug build.

## Ledger
- Implementation in three passes (first design, the model's tool format, review fixes): one Sonnet builder in its own worktree (about 500k tokens). Review of the file-search path and the tool parsing: one read-only Sonnet reviewer (about 190k tokens). Acceptance script, the probes that found the failing design, all phone checks, merge, benchmark note and this artifact: orchestrator.
