# llama-server / rpc-server on the iQOO phones: working command lines and API shapes

Contract for the Kotlin client. Everything below was run on the phones (iQOO I2501, Snapdragon 8 Elite Gen 5, Android 16) with the binaries in `native/prebuilt/arm64-v8a/` (llama.cpp **v0.6.0**, commit `d812350`, CPU variant `i8mm` = `-march=armv8.6-a+dotprod+fp16+i8mm`, static, no OpenSSL/curl). The files are executables despite the `lib*.so` names (so the APK can ship them in `jniLibs`; run them from `nativeLibraryDir` with `Runtime.exec`/`ProcessBuilder`).

## Recommended production flags (copy these)

Measured on the phones; reasons and numbers in `docs/benchmarks/2026-10-10-throughput-tuning.md` and `2026-10-10-llama-cpu-baseline.md`. Two binary sets ship: `native/prebuilt/arm64-v8a-opencl/` (GPU, **default**) and `native/prebuilt/arm64-v8a/` (CPU i8mm, **fallback** if OpenCL fails to initialise.

Chat model (Qwen3.5-4B Q4_K_M), port 8080, GPU:

```
libllama-server.so -m <dir>/Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080     -ngl 99 -t 4 -c 8192 -np 2 --reasoning off
```

CPU fallback (same model):

```
libllama-server.so -m <dir>/Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080     -t 6 -c 8192 -np 2 --reasoning off
```

- GPU vs CPU (cool start, phone C): prefill **128 vs 32 tok/s**, decode 13.6 vs 13.6 tok/s, process RSS 1.2 vs 5.4 GB, both heat the phone (sustained GPU run: Thermal Status 4 after 10 min). `-ngl` between 1 and 32 is slower than 99.
- `-t`: irrelevant on the GPU path (4 = 8). On the CPU path 8 threads gave the best prefill and 6 loses about 6% decode; 6 leaves two cores for the UI.
- `-np`/`-c`: parallel slots add **no** aggregate throughput (11.7-12.4 tok/s aggregate at np 1, 2, 4); `-np 2` only keeps a second client from blocking. `-c` is the total context, each slot gets `c/np`.
- `--reasoning off` switches Qwen3.5 thinking off for all requests (a default request spent 217 tokens thinking vs 5 with it off). Per-request alternative: `"chat_template_kwargs":{"enable_thinking":false}`.
- Do not use `--mlock`. CPU path: Q4_K weights are repacked into a 2.8 GB anonymous copy while the 2.7 GB file pages stay resident until evicted (VmHWM 5.4 GB). GPU path: process RSS 0.7-1.2 GB but system `MemAvailable` fell by 4.0 GB (weights in GPU buffers plus page cache).
- First request after start pays about 3 s of page-in: send one throwaway request after `/health` is ok.
- GPU build in an app: manifest `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` (the library is in `/vendor/etc/public.libraries.txt`), **and** `LD_LIBRARY_PATH=<nativeLibraryDir>:/vendor/lib64:/system/vendor/lib64` for the exec'd child. The manifest entry only opens the library to the app's own class loader; the exec'd server is linked in the `(default)` linker namespace and stops with `library "libOpenCL.so" not found` without the vendor directories (measured on phone C, `docs/benchmarks/2026-10-10-inapp-llm.md`; an absolute `LD_PRELOAD` path is refused as "not accessible for the namespace"). First start compiles kernels for a few seconds.
- **Heat**: any sustained inference (CPU or GPU) moved the phones to Thermal Status 3-4 (skin 46-51 C) within 2-10 minutes; a 10-minute GPU run measured decode 12.3 / 10.6 / 6.8 tok/s at minutes 1 / 5 / 10. Surface `timings` tokens/s rather than assuming 13, and budget 3-4 minutes of continuous generation per phone.

Embedding model (EmbeddingGemma-300M Q8_0), port 8081 (CPU, tiny):

```
libllama-server.so -m <dir>/embeddinggemma-300M-Q8_0.gguf --host 127.0.0.1 --port 8081     --embedding -t 2 -c 2048 -np 2
```

Pooling comes from the GGUF (mean); output is 768 floats, L2-normalised. Warm latency on phone C: 10 ms for a 12-token text, 31 ms for three texts. `embeddinggemma-2` does not load on this llama.cpp tag.

RPC worker (MemoryCloud), port 50052, started on demand:

```
LLAMA_CACHE=/data/local/tmp/llama/cache ./librpc-server.so -H 0.0.0.0 -p 50052 -t 8 -c
```

`-c` enables the on-disk tensor cache (second load of the same model: 41 s instead of 153 s and 187 MB instead of 1.6 GB over Wi-Fi); `LLAMA_CACHE` is mandatory on Android (no `$HOME`). Bind to the link interface IP instead of `0.0.0.0` if it is known; the protocol has no authentication. Use the CPU build for the worker unless the GPU path is tested for the model (the GPU rpc-server exposes the Adreno as a second device).

RPC driver (only for a model that does not fit one phone; a 4B model split over two phones measured 3.2 tok/s vs 14.9 on one phone):

```
libllama-server.so -m <big>.gguf --rpc 192.168.242.A:50052[,192.168.242.B:50052] -ngl N -ts 1,1     -ot '^output[.]weight=CPU' -c 2048 -np 1 --reasoning off --host 127.0.0.1 --port 8080
```

`-ngl N` offloads the last N-1 layers **and the output layer** (see the spike note); `-ot '^output[.]weight=CPU'` keeps the lm_head on the driver so the ~0.6 MB of logits per token is not shipped back (only meaningful for models with an untied `output.weight`, e.g. Qwen3-30B-A3B; the option was accepted by llama-bench, its effect was not verified on that model because the 30B load failed, and on the tied 4B model it changes nothing). In `llama-server`/`llama-cli` `-ts` is comma-separated; in `llama-bench` use `/`.

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

## Who may call the models routes

The app's own routes under `/v1/models/` (`GET /state`, `/load`, `/stop`, `/autoload`, `/gpu`, `/selftest`, `/bench`) are signed Node API routes like every other `/v1` route, and any paired phone may call every one of them. That is by design: MemoryCloud stops the chat and embedding servers on a lender phone to make room for `rpc-server` and restores them afterwards, so the driving phone must be able to call `/stop` and `/load` there. The pairing secret is the only gate; there is no per-route or per-phone permission, so a phone that holds the secret can unload or replace the models of any other paired phone. llama-server itself is not reachable from the network (loopback only, no API key).
