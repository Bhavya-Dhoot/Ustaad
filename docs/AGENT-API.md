# Ustaad agent endpoint, version 1

Other apps on the same phone can use Ustaad's model through an OpenAI-style HTTP API on the loopback address. Nothing leaves the phone for `ustaad-local`. It is off until the owner turns it on.

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
- `POST /v1/chat/completions`: `model`, `messages` (system, user, assistant; `developer` counts as system; content is text or a list of text parts, joined with a newline), `max_tokens` or `max_completion_tokens` (default 256, clamped to 1024), `temperature` (0 to 2, default 0.7), `stop` (up to 4), `seed`, `stream`, `response_format` `{"type":"json_schema","json_schema":{"schema":{...}}}` (the answer follows the schema). Other fields are ignored.
- Answers carry `usage` and `system_fingerprint` = `ustaad:<name of the phone that ran it>`. A streamed `ustaad-swarm` answer says `ustaad:swarm`, because the swarm does not name the phone before the stream ends.
- `stream: true` answers as server-sent events and ends with `data: [DONE]`; usage is on the last chunk. A comment line (`: ping`) goes out every 2 s. Closing the connection ends the generation and frees the model.
- `POST /v1/embeddings`: `model` (either name; this phone's embedder answers), `input` as a string or a list of up to 64 strings, `encoding_format` `float` (default) or `base64`. `usage` is 0: token counts are not measured.

## Limits and refusals
Errors are `{"error":{"message","type","code"}}`.
- 401 missing or wrong key; 403 a caller that is not on this phone.
- 400 naming the field: `tools`, `tool_choice` (and the old `functions`, `function_call`), tool messages, `n` above 1, image or audio parts, more than 64 messages, invalid JSON or JSON nested deeper than 64 levels, bad values, `response_format` other than `json_schema` or `text`.
- 404 unknown model or route. 413 body over 1 MiB.
- 503 `No chat model is loaded on this phone` (the placeholder model never answers another app; same for the swarm and for embeddings), and 503 `model_busy` when the model stayed busy for 30 s (it runs one call at a time, shared with the rest of Ustaad).
- No tool calling, no file search, no saved answers, no access from other devices.

## Trust model
- Listener on `127.0.0.1` only, and a request from any other address is refused as well. Another device on the Wi-Fi cannot reach it.
- Any app on the phone with the INTERNET permission can open the port. Only a holder of the key is served; the key is compared in constant time, there are no CORS headers, and every route (even an unknown one) asks for the key first.
- The key lives in Ustaad's private storage and in the owner's clipboard when copied. Anyone who has it can use the model until the owner presses "New key" or switches access off.
- A key holder can make this phone generate text (battery, heat, and the model busy for Ustaad's own features) and can embed text it sends. With `ustaad-swarm` the prompt is also sent to the other paired phones over SwarmLink.
- A key holder cannot read anything of the owner's: not Data Union files or answers, not the Knowledge Bank, not Choir history, the audit log, the pairing secret or the node API. None of those is reachable through this endpoint.
- Traffic is plain HTTP inside the phone. The audit log records one entry per request (model, character and token counts, milliseconds, status) and never the text of a prompt or an answer.
