# Ustaad agent endpoint, version 2

Other apps on the same phone can use Ustaad's model through an OpenAI-style HTTP API on the loopback address, with tool calling. Nothing leaves the phone for `ustaad-local`. It is off until the owner turns it on. Version 1 was chat, streaming, `json_schema` and embeddings; version 2 adds tool calling.

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
- `POST /v1/embeddings`: `model` (either name; this phone's embedder answers), `input` as a string or a list of up to 64 strings, `encoding_format` `float` (default) or `base64`. `usage` is 0: token counts are not measured.

## Tool calling
OpenAI function calling; the calling app runs the tools, Ustaad only chooses. Both models.
- `tools`: up to 16 of `{"type":"function","function":{"name","description","parameters"}}`. `name`: 1 to 64 of `A-Za-z0-9_-`; `description`: up to 1024 characters; `parameters`: a JSON Schema object (left out: no arguments); all definitions together up to 16 KiB. `tool_choice`: `"auto"` (default), `"none"`, `"required"` or `{"type":"function","function":{"name":"..."}}`. `parallel_tool_calls` (default true): up to 4 calls in one answer; false: 1.
- Earlier turns go in `messages` in order: assistant messages with `tool_calls` (content may be null) and `{"role":"tool","tool_call_id":"...","content":"..."}`.
- Answer when the model calls tools: `message.content` null, `message.tool_calls` `[{"id":"call_...","type":"function","function":{"name","arguments":"<JSON text>"}}]`, `finish_reason` `"tool_calls"`. Otherwise an ordinary answer.
- How: the chat template is not used. The tools are described in a system message and the output is held (the model server's schema constraint) to `{"content":"..."}` or `{"tool_calls":[{"name","arguments"}]}`, so names are declared ones and arguments are valid JSON for that tool's `parameters`. A constraint is not a judgment: the model can still choose the wrong tool or a poor value. `"none"` sends neither the tool text nor a constraint. `required` and a named function allow only calls.
- `stream: true` with tools: the answer is held back (pings go out meanwhile), then one chunk carries the whole `tool_calls` delta (`index`, `id`, `type`, `function.name`, `function.arguments`), then the last chunk with `finish_reason` and `usage`, then `[DONE]`. Words are sent in one chunk.
- A `parameters` schema the model server cannot turn into a grammar: 400 `unsupported_schema` (the same for `response_format`). Also 400: `tools` with `response_format`, a type other than `function`, duplicate or bad names, over the limits above, a constraint over 16,000 characters (what a swarm phone accepts), `tool_choice` naming an unknown tool or `"required"` without `tools`, a `tool` message without `tool_call_id`.

## Limits and refusals
Errors are `{"error":{"message","type","code"}}`.
- 401 missing or wrong key; 403 a caller that is not on this phone, or a `Host` header other than `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` (a web page using DNS rebinding).
- 429 `rate_limited` when more than 4 requests are being read at once or more than 2 embedding calls run at once. Try again shortly.
- 400 naming the field: the old `functions` and `function_call`, `n` above 1, image or audio parts, more than 64 messages, invalid JSON or JSON nested deeper than 64 levels, bad values, `response_format` other than `json_schema` or `text`.
- 404 unknown model or route. 413 body over 1 MiB.
- 503 `No chat model is loaded on this phone` (the placeholder model never answers another app; same for the swarm and for embeddings), and 503 `model_busy` when the model stayed busy for 30 s (it runs one call at a time, shared with the rest of Ustaad). 503 `swarm_unavailable` when the swarm could not run a job (phone timeout, phone gone, all busy) and `model_unavailable` when the connection to the model was lost. Any other failure is 500 `The model call failed.`; a 5xx never carries the text of an exception.
- A connection that is silent for 300 s is closed, so a non-streamed call that needs longer fails: stream long answers.
- Ustaad never runs a tool for the caller. No file search, no saved answers, no access from other devices.
- `usage.prompt_tokens` counts the tokens the model processed after its prompt cache, so a repeated prompt reports a small number.
- Ustaad must be running. Measured on one phone: calls answered at the same speed for 3 minutes with Ustaad in the background, charging, screen on (docs/benchmarks/2026-10-10-agent-endpoint.md). Longer, on battery or with the screen off is not measured; grant Ustaad the battery exemption.

## Trust model
- Listener on `127.0.0.1` only, and a request from any other address is refused as well. Another device on the Wi-Fi cannot reach it.
- Any app on the phone with the INTERNET permission can open the port. Only a holder of the key is served; the key is compared in constant time, there are no CORS headers, and every route (even an unknown one) asks for the key first.
- The key lives in Ustaad's private storage and in the owner's clipboard when copied. Anyone who has it can use the model until the owner presses "New key" or switches access off.
- A key holder can make this phone generate text (battery, heat, and the model busy for Ustaad's own features) and can embed text it sends. With `ustaad-swarm` the prompt is also sent to the other paired phones over SwarmLink.
- A key holder cannot read anything of the owner's: not Data Union files or answers, not the Knowledge Bank, not Choir history, the audit log, the pairing secret or the node API. None of those is reachable through this endpoint.
- Traffic is plain HTTP inside the phone. The audit log records one entry per answered request and per 5xx (model, character and token counts, milliseconds, status) and never the text of a prompt or an answer. Refused requests (4xx) are only counted in memory.
