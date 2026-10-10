# Ustaad agent endpoint, version 2

Other apps on the same phone can use Ustaad's model through an OpenAI-style HTTP API on the loopback address, with tool calling and file search. Nothing leaves the phone for `ustaad-local`, except a file search with scope `swarm`, which sends the query text to paired phones. It is off until the owner turns it on. Version 1 was chat, streaming, `json_schema` and embeddings; version 2 adds tool calling and, behind its own switch, file search.

## Turn it on
Settings, "Agent access". Switching it on makes a key and opens the listener (inside Ustaad's foreground service); switching it off closes the port and forgets the key. Settings shows the base URL, the key (Copy), "New key" (the old key stops working at once) and how many requests were answered since Ustaad started.

- Base URL: `http://127.0.0.1:8790/v1` (a build made with `-PportOffset=N` listens on 8790+N).
- Key: 32 random bytes, base64url, sent as `Authorization: Bearer <key>` on every request.

## Calls
```
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Authorization: Bearer $USTAAD_KEY" -H "Content-Type: application/json" \
  -d '{"model":"ustaad-local","messages":[{"role":"user","content":"Say hi"}],"max_tokens":64}'
```
From a PC, `adb forward tcp:8790 tcp:8790` makes the same URL work. From Kotlin, no library needed:
```kotlin
val conn = (URL("http://127.0.0.1:8790/v1/chat/completions").openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"; doOutput = true; readTimeout = 180_000
    setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json")
}
conn.outputStream.use { it.write("""{"model":"ustaad-local","messages":[{"role":"user","content":"Say hi"}]}""".toByteArray()) }
val json = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
```
Android blocks plain HTTP by default for apps that target API 28 or later. The calling app must allow cleartext to 127.0.0.1 only: `android:networkSecurityConfig="@xml/network_security_config"` on its `<application>`, and
```xml
<network-security-config><domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">127.0.0.1</domain></domain-config></network-security-config>
```
It also needs the INTERNET permission. Use `127.0.0.1`, not `localhost`.

## Routes and models
- `GET /v1/models` lists `ustaad-local` (this phone's chat model, one call at a time) and `ustaad-swarm` (the best free phone of the swarm).
- `POST /v1/chat/completions`: `model`, `messages` (system, user, assistant, tool; `developer` counts as system; content is text or a list of text parts, joined with a newline), `tools` (below), `max_tokens` or `max_completion_tokens` (default 256, clamped to 1024), `temperature` (0 to 2, default 0.7), `stop` (up to 4), `seed`, `stream`, `response_format` `{"type":"json_schema","json_schema":{"schema":{...}}}` (the answer follows the schema; the schema text may be up to 16 KiB). It works only while the default chat model is loaded: another model fails the call. Other fields are ignored.
- Answers carry `usage` and `system_fingerprint` = `ustaad:<name of the phone that ran it>`. A streamed `ustaad-swarm` request tries this phone first, then the best free phone, and says `ustaad:swarm` because the swarm does not name the phone before the stream ends.
- `stream: true` answers as server-sent events and ends with `data: [DONE]`; usage is on the last chunk. From the first streamed chunk on, a comment line (`: ping`) goes out every 2 s, closing the connection ends the generation and frees the model, and a write that blocks for 30 s (a client that stopped reading) ends the stream. Before the first chunk (the model is still reading the prompt) there are no pings and a closed connection is noticed only at the first write.
- `POST /v1/files/search`: the owner's shared files, off until a second switch is on (below).
- `POST /v1/embeddings`: `model` (either name; this phone's embedder answers), `input` as a string or a list of up to 64 strings, `encoding_format` `float` (default) or `base64`. `usage` is 0: token counts are not measured.

## Tool calling
OpenAI function calling; the calling app runs the tools, Ustaad only chooses. Both models.
- `tools`: up to 16 of `{"type":"function","function":{"name","description","parameters"}}`. `name`: 1 to 64 of `A-Za-z0-9_-`; `description`: up to 1024 characters; `parameters`: a JSON Schema object (left out: no arguments); all definitions together up to 16 KiB. `tool_choice`: `"auto"` (default), `"none"`, `"required"` or `{"type":"function","function":{"name":"..."}}`. `parallel_tool_calls` (default true): up to 4 calls in one answer; false: 1.
- Earlier turns go in `messages` in order: assistant messages with `tool_calls` (content may be null) and `{"role":"tool","tool_call_id":"...","content":"..."}`.
- Answer when the model calls tools: `message.content` null, `message.tool_calls` `[{"id":"call_...","type":"function","function":{"name","arguments":"<JSON text>"}}]`, `finish_reason` `"tool_calls"`. Otherwise an ordinary answer; output cut off by `max_tokens` is not valid JSON and comes back as words with `finish_reason` `"length"`.
- How, `"auto"` (default): the tools go into the system message in the model's own format (`<tools>`, one JSON line per tool; every `<` in a description or schema is written `<`, and tool results and earlier answers have the tag names defused, so none of them can open or close a block) and the model answers freely. Every `<tool_call>` block in its answer is one call; text around the blocks is dropped; no block means the whole text is the answer. A call is accepted when its JSON parses, its name is a declared tool, its arguments are a JSON object and every `required` key of that tool's `parameters` is present. That is all that is checked: with `"auto"` the arguments are not validated against the rest of the schema (types, enums, extra keys). If a call fails the check, the same conversation is asked once more under a schema constraint (for that tool, or any tool if the name was unknown); if that fails too, the model's text comes back as words, status 200. Repairs are counted in the audit entry (`repairs`); their tokens are not in `usage`.
- `"required"` and a named function: the output is held by the model server's schema constraint to `{"tool_calls":[{"name","arguments"}]}`, so names are declared ones and arguments match that tool's `parameters`. `"none"` sends neither the tool text nor a constraint. Either way the model can still choose the wrong tool or a poor value.
- `stream: true` with tools: the answer is held back (pings go out meanwhile), then one chunk carries the whole `tool_calls` delta (`index`, `id`, `type`, `function.name`, `function.arguments`), then the last chunk with `finish_reason` and `usage`, then `[DONE]`. Words are sent in one chunk.
- Where a schema is sent (`"required"`, a named function, `response_format`), one the model server cannot turn into a grammar is 400 `unsupported_schema`. Also 400: `tools` with `response_format`, a type other than `function`, duplicate or bad names, over the limits above, a `"required"` or named constraint over 16,000 characters (what a swarm phone accepts), `tool_choice` naming an unknown tool or `"required"` without `tools`, a `tool` message without `tool_call_id`.

## File search
A second switch under Agent access, "File search for other apps", off by default. It works only while access is on: switching access off switches it off, "New key" leaves it as it is. Off: `POST /v1/files/search` is 403 `file_search_off`.
- Request: `{"query":"<1 to 1000 characters>","scope":"phone"|"swarm","max_results":1 to 10}`; `scope` defaults to `phone`, `max_results` to 5.
- Answer: `{"object":"list","data":[{"phone","title","date" (YYYY-MM-DD or null),"kind","snippet" (up to 400 characters)}], best match first,"scope","incomplete","phones":[{"name","status":"ok"|"offline"|"error"}]}`. `incomplete` is true when a paired phone did not answer. No score, file path, URI or hash of one, file bytes, embeddings or count of files held back; `phone` and `name` are cut to 64 characters.
- What may come back is decided by the rule that already serves a paired phone, and nothing new: this phone's part is exactly what it would answer to a paired phone (folders the owner shares; never a folder named Private; never the owner's own "(private file on this phone)" placeholder). With `"scope":"swarm"` the same signed query the Data Union sends also goes to the online paired phones, each of which answers under its own rule. Hits are merged as the Ask screen merges them and cut to `max_results`.
- 503 `index_unavailable` when this phone has no ready embedding model, or an index made by another one (an empty index is an empty list). 429 `rate_limited` above 2 searches at once. A paired phone has 3 s to answer; later it is `offline`.
- Audit: one `agent.files` entry per search with the scope, the query length, the number of results, each phone's status and how many results it gave, how many paired phones were sent the query and how many bytes of it, and milliseconds; never the query, a title or a snippet.

## Example: declare `search_files`, let the model call it, run the search, answer
```python
import json, urllib.request
from openai import OpenAI

BASE, KEY = "http://127.0.0.1:8790/v1", "<key from Ustaad Settings>"   # file search must be on in Settings
client = OpenAI(base_url=BASE, api_key=KEY)
tools = [{"type": "function", "function": {
    "name": "search_files", "description": "Search the user's shared files (bills, photos, documents).",
    "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}}]

def search_files(query):   # the app runs the tool: Ustaad's own route, on this phone and the paired ones
    body = json.dumps({"query": query, "scope": "swarm", "max_results": 3}).encode()
    req = urllib.request.Request(f"{BASE}/files/search", body, {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    return urllib.request.urlopen(req).read().decode()

messages = [{"role": "user", "content": "When is my electricity bill due?"}]
reply = client.chat.completions.create(model="ustaad-local", messages=messages, tools=tools).choices[0]
if reply.finish_reason == "tool_calls":
    messages.append(reply.message)                      # the call, as the model made it
    for call in reply.message.tool_calls:               # arguments: JSON text that fits the tool's parameters
        result = search_files(**json.loads(call.function.arguments))
        messages.append({"role": "tool", "tool_call_id": call.id, "content": result})
    reply = client.chat.completions.create(model="ustaad-local", messages=messages, tools=tools).choices[0]
print(reply.message.content)
```

## Limits and refusals
Errors are `{"error":{"message","type","code"}}`.
- 401 missing or wrong key; 403 a caller that is not on this phone, or a `Host` header other than `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` (a web page using DNS rebinding).
- 429 `rate_limited` when more than 4 requests are being read at once, or when more than 2 embedding calls or more than 2 file searches run at once. Try again shortly.
- 400 naming the field: the old `functions` and `function_call`, `n` above 1, image or audio parts, more than 64 messages, invalid JSON or JSON nested deeper than 64 levels, bad values, `response_format` other than `json_schema` or `text`, a schema (`parameters`, `response_format`) with a bound above 64 (`minItems`, `maxItems`, `minLength`, `maxLength`, `minProperties`, `maxProperties`, `{n,m}` in a `pattern`) or a `$ref` that does not start with `#`, and a `ustaad-swarm` request over a phone's limits (32,000 characters a message, 128,000 in all, 64 messages after the tool text is added, 16,000 of schema). A pydantic-style `$defs` reference is not supported: inline the sub-schema.
- 404 unknown model or route. 413 body over 1 MiB.
- 503 `No chat model is loaded on this phone` (the placeholder model never answers another app; same for the swarm and for embeddings), and 503 `model_busy` when the model stayed busy for 30 s (it runs one call at a time, shared with the rest of Ustaad). 503 `swarm_unavailable` when the swarm could not run a job (phone timeout, phone gone, all busy) and `model_unavailable` when the connection to the model was lost. Any other failure is 500 `The model call failed.`; a 5xx never carries the text of an exception.
- A connection that is silent for 300 s is closed, so a non-streamed call that needs longer fails: stream long answers.
- Ustaad never runs a tool for the caller. No saved answers, no opening or downloading of files, no access from other devices.
- `usage.prompt_tokens` counts the tokens the model processed after its prompt cache, so a repeated prompt reports a small number.
- Ustaad must be running. Measured on one phone: calls answered at the same speed for 3 minutes with Ustaad in the background, charging, screen on (docs/benchmarks/2026-10-10-agent-endpoint.md). Longer, on battery or with the screen off is not measured; grant Ustaad the battery exemption.

## Trust model
- Listener on `127.0.0.1` only, and a request from any other address is refused as well. Another device on the Wi-Fi cannot reach it.
- Any app on the phone with the INTERNET permission can open the port. Only a holder of the key is served; the key is compared in constant time, there are no CORS headers, and every route (even an unknown one) asks for the key first.
- The key lives in Ustaad's private storage and in the owner's clipboard when copied. Anyone who has it can use the model until the owner presses "New key" or switches access off.
- A key holder can make this phone generate text (battery, heat, and the model busy for Ustaad's own features) and can embed text it sends. With `ustaad-swarm` the prompt is also sent to the other paired phones over SwarmLink.
- With file search off, a key holder cannot read anything of the owner's: not Data Union files or answers, not the Knowledge Bank, not Choir history, the audit log, the pairing secret or the node API. None of those is reachable through this endpoint.
- With file search on, a key holder can read what a paired phone could: title, date, kind and a snippet of up to 400 characters of every shared file that matches a query, 10 hits at a time, for as many queries as it likes (only 2 run at once), so a patient app can read the shared files' text piece by piece. With `"scope":"swarm"` this reaches the shared files of every online paired phone as well, without asking those owners, and the query text goes to those phones over SwarmLink; there the search is logged as an ordinary question from this phone, so their owners cannot tell an app's search from a person's. The key holder sees how long each search took and which phones answered. It cannot open, download or list files, and never sees a file in a folder that is private or named Private, nor how many were held back. Never reachable even then: the Knowledge Bank, Choir history, the audit log, the pairing secret, the node API.
- Traffic is plain HTTP inside the phone. The audit log records one entry per answered request and per 5xx (model, character and token counts, milliseconds, status) and never the text of a prompt, an answer, a query or a snippet. Refused requests (4xx) are only counted in memory.
