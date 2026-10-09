# llama-server / rpc-server on the iQOO phones: working command lines and API shapes

Contract for the Kotlin client. Everything below was run on the phones (iQOO I2501, Snapdragon 8 Elite Gen 5, Android 16) with the binaries in `native/prebuilt/arm64-v8a/` (llama.cpp **v0.6.0**, commit `d812350`, CPU variant `i8mm` = `-march=armv8.6-a+dotprod+fp16+i8mm`, static, no OpenSSL/curl). The files are executables despite the `lib*.so` names (so the APK can ship them in `jniLibs`; run them from `nativeLibraryDir` with `Runtime.exec`/`ProcessBuilder`).

## Recommended production flags (copy these)

These are the flags that survived measurement. Throughput and thermal numbers behind them are in `docs/benchmarks/2026-10-10-llama-cpu-baseline.md` and `docs/benchmarks/2026-10-10-throughput-tuning.md`; where a flag is still provisional it says so.

Chat model, port 8080 (Qwen3.5-4B Q4_K_M):

```
libllama-server.so -m <dir>/Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 \
    -t 6 -c 4096 -np 2 --reasoning off --no-webui
```

- `--host 127.0.0.1` for the chat and embedding servers (contract: loopback only). The rpc-server is the only listener on the link interface.
- `-t`: decode is memory-bandwidth bound (about 12-15 tok/s for this 2.7 GB model, roughly 35 GB/s); prefill scales with cores. 8 threads gave the best prefill on a cold phone; 6 leaves two cores for the app and Android. Final choice and the pinning result are in the tuning note.
- `-c` is the total context; with `-np N` each slot gets `c/N` (unified KV is off). Keep at least 2048 per slot.
- `--reasoning off` switches Qwen3.5 thinking off for every request (verified: a default request returns 5 completion tokens instead of 217 and no `reasoning_content`). Per-request alternative: `"chat_template_kwargs":{"enable_thinking":false}`.
- Do not pass `--mlock`. With the i8mm build Q4_K weights are repacked into an anonymous copy (about 2.8 GB) while the mmapped file pages (2.7 GB) stay resident until the kernel evicts them: measured peak RSS (VmHWM) 5.4 GB, anonymous 2.8 GB for the 4B model. Budget about 3 GB anonymous per 4B instance, 5.5 GB if the page cache is not reclaimed.
- First request after start is slow (about 3 s extra prefill, weights page in). Send one throwaway request after `/health` returns ok.

Embedding model, port 8081 (EmbeddingGemma-300M Q8_0):

```
libllama-server.so -m <dir>/embeddinggemma-300M-Q8_0.gguf --host 127.0.0.1 --port 8081 \
    --embedding -t 2 -c 2048 -np 2
```

Pooling is taken from the GGUF (mean); no `--pooling` flag needed. Output is 768 floats, L2-normalised (norm 1.0000). Warm latency on phone C: 10 ms for one 12-token text, 31 ms for a batch of three.

RPC worker (MemoryCloud), port 50052:

```
LLAMA_CACHE=/data/local/tmp/llama/cache ./librpc-server.so -H 0.0.0.0 -p 50052 -t 8 -c
```

(`-c` enables the on-disk tensor cache; `LLAMA_CACHE` is needed because Android has no `$HOME`. See the RPC spike note for the measured benefit and for `-t`.) Bind to the link interface address if possible instead of 0.0.0.0: the RPC protocol has no authentication.

RPC driver: `libllama-server.so ... --rpc hostB:50052[,hostC:50052] -ngl N [-ts a,b]` (details and measured results in `docs/benchmarks/2026-10-10-rpc-split-spike.md`).

## Process management facts

- Start with `ProcessBuilder`, keep the process in a foreground service, redirect stdout/stderr to a log file (llama.cpp logs a lot).
- Ready check: poll `GET /health`. Connection refused (HTTP 000) while the socket is not bound yet, then `503 {"error":{"message":"Loading model","type":"unavailable_error","code":503}}`, then `200 {"status":"ok"}`. Model load from a warm page cache took 9 s on phone A, cold longer.
- `kill` of a `shell`-user server from `adb shell` works; the app's own processes cannot be killed from `adb shell` as `shell`.
- Binaries were run from `/data/local/tmp/llama/<variant>/` over ADB. From the app, the executable must live in `nativeLibraryDir` (extractNativeLibs=true) or be copied to an executable location.

## Endpoints verified

| Endpoint | Result |
|---|---|
| `GET /health` | `{"status":"ok"}` 200 when ready (see above for loading) |
| `GET /v1/models` | OpenAI list, `data[0].id` is the model path as passed to `-m` |
| `GET /props` | default generation settings, chat template info |
| `POST /v1/chat/completions` | OpenAI chat, non-stream and SSE stream |
| `POST /v1/embeddings` | `{"model","object":"list","usage":{"prompt_tokens","total_tokens"},"data":[{"embedding":[768 floats],"index":0,"object":"embedding"}]}` |
| `POST /completion` | native llama.cpp completion, used by the soak script |

## Chat, non-streaming

Request (`curl -d @file`):

```json
{"model":"qwen","messages":[{"role":"user","content":"Reply with exactly: hello from the phone"}],
 "max_tokens":300,"temperature":0,"chat_template_kwargs":{"enable_thinking":false}}
```

Response (trimmed):

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"hello from the phone"}}],
 "created":1791573464,"model":"models/Qwen3.5-4B-Q4_K_M.gguf","object":"chat.completion","id":"chatcmpl-...",
 "usage":{"completion_tokens":5,"prompt_tokens":20,"total_tokens":25,"prompt_tokens_details":{"cached_tokens":14}},
 "timings":{"cache_n":14,"prompt_n":6,"prompt_ms":345.6,"prompt_per_second":17.4,
            "predicted_n":5,"predicted_ms":312.9,"predicted_per_token_ms":78.2,"predicted_per_second":12.78}}
```

`timings` is present on every chat response (non-stream) and is the source for tokens/s in the UI. With thinking ON (server started without `--reasoning off`, no kwarg) the answer arrives as `message.content` and the chain of thought as a separate `message.reasoning_content` field: 217 completion tokens for the same prompt, 17 s at 12.9 tok/s. Prefer thinking off for workers and verifiers.

## Chat, streaming (SSE)

Request: same body plus `"stream":true,"stream_options":{"include_usage":true}`.

Wire format: lines `data: {json}\n\n`, terminated by `data: [DONE]\n\n`. Chunks, in order:

1. `{"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}],...,"object":"chat.completion.chunk"}`
2. one chunk per token or few tokens: `"delta":{"content":"Red"}` (with thinking on, `"delta":{"reasoning_content":"..."}` chunks come first)
3. `{"choices":[{"finish_reason":"stop","index":0,"delta":{}}],...}`
4. **only with `include_usage`**: `{"choices":[],...,"usage":{"completion_tokens":6,"prompt_tokens":20,"total_tokens":26,"prompt_tokens_details":{"cached_tokens":0}},"timings":{"cache_n":0,"prompt_n":20,"prompt_ms":549.5,"prompt_per_second":36.4,"predicted_n":6,"predicted_ms":385.3,"predicted_per_token_ms":77.06,"predicted_per_second":12.98}}`
5. `data: [DONE]`

So the `timings` object arrives in the last data chunk (the one with empty `choices` and `usage`). If the client does not need it, omit `stream_options`. SSE keep-alive pings are sent every 30 s by default (`--sse-ping-interval`).

## JSON-schema constrained output

Request body adds:

```json
"response_format":{"type":"json_schema","json_schema":{"name":"person","strict":true,"schema":{
  "type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"},"city":{"type":"string"}},
  "required":["name","age","city"],"additionalProperties":false}}}
```

Verified on "Maria Lopez is 34 years old and lives in Pune.": `message.content` = `{"name": "Maria Lopez","age": 34,"city": "Pune"}` (27 tokens, newlines between members), parsed and matched the schema. The constraint is enforced by a grammar, so the output always parses; parse `message.content` as JSON, do not regex it.

## Embeddings

```
POST /v1/embeddings  {"model":"embeddinggemma","input":["text1","text2","text3"]}
```

Verified: 768-d, norm 1.0, cosine("I love hiking in the mountains.", "Trekking in the Himalayas is amazing.") = 0.49 vs 0.23 for the unrelated tax sentence. EmbeddingGemma was trained with task prefixes (`task: search result | query: ...` for queries, `title: none | text: ...` for documents); the prefixes were not used in the checks above, use them for retrieval quality.

## Gotchas for the client

1. Always send `chat_template_kwargs.enable_thinking=false` if the server was not started with `--reasoning off`, or budget for hundreds of hidden tokens.
2. `max_tokens` counts thinking tokens too.
3. The first request after load pays a page-in penalty; warm it.
4. Hot phones are slower: decode fell to 8-11 tok/s once Thermal Status reached 3-4 (see the tuning note). Surface tokens/s from `timings`, do not assume 13.
5. No authentication on llama-server; loopback only. The rpc-server has none either.
