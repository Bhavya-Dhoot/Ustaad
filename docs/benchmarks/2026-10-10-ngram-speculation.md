# Speculation without a draft model (n-gram options of llama-server) on Phone B's GPU: slower, never faster (2026-10-10, about 16:03 to 16:30)

Question: does llama-server's speculative decoding that needs no second model (it guesses the next tokens from text already in the prompt and the answer) raise decode speed on the phone's GPU for this app's real prompts, and does it give the identical answer?

Answer: this llama.cpp has the options, and on Phone B they never raised decode speed. The one setting that fired often (`ngram-simple`, 4-token lookup, 8-token guess) cut decode speed by 25 to 43 % on the Data Union and Choir prompts (against the nearest baseline passes) and changed one answer in twelve. The llama.cpp default (`ngram-mod`) never fired on these prompts. Recommendation: leave it off.

## What was run

- iQOO Phone B (192.168.242.23). Only this phone was contacted. Script: `scripts/spec-probe.py`. Raw files: `docs/benchmarks/raw/gate-9b/speculation/` (`pass-NN-*.json` with every answer and every `timings` object, `server-NN-*.log`, `prompts.json`, `report.md`, console logs).
- Binary: `libllama-server-gpu.so` from the installed app (OpenCL build), SHA256 `4156717d...89335a6`, identical to `native/prebuilt/arm64-v8a-opencl/libllama-server.so`. `--help` on the phone lists `--spec-type none,draft-simple,draft-eagle3,draft-mtp,draft-dflash,draft-dspark,ngram-simple,ngram-map-k,ngram-map-k4v,ngram-mod,ngram-cache`, `--spec-ngram-simple-size-n/-m/-min-hits`, `--spec-ngram-map-k-*`, `--spec-ngram-mod-n-match/-n-min/-n-max`, `--spec-draft-n-max/-n-min`.
- Model: `Qwen3.5-4B-Q4_K_M.gguf` (2,740,937,888 bytes, SHA256 `00fe7986...ef11a4` checked on the phone, same as `docs/models.md`), the app's own file under `/sdcard/Android/data/com.swarmos.app/files/models/`. Nothing was pushed to the phone.
- Server: started by the script, standalone, on loopback port 8090 with the app's chat arguments from the running process (`-ngl 99 -t 4 -c 8192 -np 2 -ctxcp 6 -cram 1024 --reasoning off --no-webui`, own random api key). That is backend OpenCL GPU, context 8192 total (4096 per slot), 4 threads. The app's chat server was stopped for the runs and loaded again at the end. Reached through `adb forward tcp:18090 tcp:8090`, removed at the end.
- 12 prompts, temperature 0, fixed seed, `cache_prompt` on, thinking off, at most 128 new tokens (the 3 plain ones at 48, as the app's bench32 does):
  - 6 Data Union: the app's system prompt and source layout (`PromptBuilder` in `pdu/Ranking.kt`), 6 numbered sources each taken from `docs/demo-corpus` (bills, receipts, yoga, dentist, meeting notes), 664 to 810 prompt tokens. Answers quote amounts, dates and source numbers.
  - 3 Choir with the JSON schema: a MATH solve (`m1`), a CODE solve (`c2`) from `assets/choir/problems.json`, and a revise round (`m3`) whose prompt carries earlier answers and steps (the three candidate answers are written by hand in the shape the app builds).
  - 3 plain: bench32 topics 0, 2 and 4 with "Answer in one short sentence."
- Order: baseline, variant, baseline, variant, then the two other settings once each, each bracketed by baselines. Before every pass the script waited for Thermal Status 0 or 1 (never needed more than 60 s). A fresh server per pass, one throw-away request first.
- Baseline prefill (prompt tokens per second): Data Union 111 to 136, Choir 71 to 93 (short prompts), plain 22 to 25 (tiny prompts). Server RAM high-water (`VmHWM`) 2.10 to 2.32 GB in every pass, no difference between settings beyond 0.2 GB.

## Results: decode tokens per second, median of the prompts in the group (range)

Settings: **none** = baseline; **simple 4/8** = `--spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8`; **mod** = `--spec-type ngram-mod` (defaults 24 / 48 / 64, the same as `--spec-default`); **simple 12/48** = `--spec-type ngram-simple` (defaults). "Same text" is against the first baseline (pass 1). Drafted/accepted are token counts summed over the group; "none" means the server reported no draft at all.

Data Union (6 prompts)

| Pass | Setting | Thermal before / after | Decode tok/s | Drafted | Accepted | Same text |
|---|---|---|---|---|---|---|
| 1 | none | 0 / 1 | 13.34 (13.22-13.46) | none | none | 6 of 6 |
| 2 | simple 4/8 | 1 / 1 | 7.57 (5.57-8.94) | 160 | 46 (29 %) | 5 of 6 |
| 3 | none | 1 / 1 | 11.66 (11.45-11.85) | none | none | 6 of 6 |
| 4 | simple 4/8 | 1 / 1 | 7.64 (5.50-8.93) | 160 | 46 (29 %) | 5 of 6 |
| 5 | mod | 1 / 1 | 11.60 (11.41-11.91) | none | none | 6 of 6 |
| 6 | none | 1 / 2 | 11.54 (11.32-11.80) | none | none | 6 of 6 |
| 7 | simple 12/48 | 1 / 2 | 11.54 (8.34-11.75) | 48 | 4 (8 %) | 6 of 6 |
| 8 | none | 1 / 2 | 12.65 (12.60-12.91) | none | none | 6 of 6 |

Choir (3 prompts)

| Pass | Setting | Thermal before / after | Decode tok/s | Drafted | Accepted | Same text |
|---|---|---|---|---|---|---|
| 1 | none | 0 / 1 | 13.17 (13.02-13.72) | none | none | 3 of 3 |
| 2 | simple 4/8 | 1 / 1 | 8.88 (6.73-9.06) | 147 | 28 (19 %) | 3 of 3 |
| 3 | none | 1 / 1 | 11.90 (11.89-11.91) | none | none | 3 of 3 |
| 4 | simple 4/8 | 1 / 1 | 8.94 (6.78-9.07) | 147 | 28 (19 %) | 3 of 3 |
| 5 | mod | 1 / 1 | 11.98 (11.84-12.13) | none | none | 3 of 3 |
| 6 | none | 1 / 2 | 11.96 (11.90-11.98) | none | none | 3 of 3 |
| 7 | simple 12/48 | 1 / 2 | 11.97 (10.88-12.11) | 48 | 4 (8 %) | 2 of 3 |
| 8 | none | 1 / 2 | 13.12 (13.02-13.15) | none | none | 3 of 3 |

Plain (3 prompts)

| Pass | Setting | Thermal before / after | Decode tok/s | Drafted | Accepted | Same text |
|---|---|---|---|---|---|---|
| 1 | none | 0 / 1 | 12.96 (12.85-13.07) | none | none | 3 of 3 |
| 2 | simple 4/8 | 1 / 1 | 11.98 (11.65-12.07) | none | none | 3 of 3 |
| 3 | none | 1 / 1 | 11.86 (11.77-12.25) | none | none | 3 of 3 |
| 4 | simple 4/8 | 1 / 1 | 11.76 (11.73-12.13) | none | none | 3 of 3 |
| 5 | mod | 1 / 1 | 11.86 (11.75-11.87) | none | none | 3 of 3 |
| 6 | none | 1 / 2 | 11.73 (11.62-11.90) | none | none | 3 of 3 |
| 7 | simple 12/48 | 1 / 2 | 11.75 (11.51-12.04) | none | none | 3 of 3 |
| 8 | none | 1 / 2 | 12.91 (12.89-13.10) | none | none | 3 of 3 |

Per-prompt numbers, draft counts and the differing answers are in `raw/gate-9b/speculation/report.md`.

## What the numbers say

- Heat still moves decode: the four baselines of the Data Union group run 13.34, 11.66, 11.54, 12.65 tok/s, and the phone read Thermal Status 1 for most of them. The comparisons below use the baseline passes on both sides of each variant.
- `simple 4/8` fired on 9 of 12 prompts (all Data Union and Choir, none of the plain ones) and every time lost speed: Data Union 7.6 against 11.7 to 13.3, Choir 8.9 against 11.9 to 13.2. Only 19 to 29 % of the drafted tokens were accepted. Two runs gave the same draft counts to the token, so the loss is not noise. The server logs show the cost for the best case, prompt `du1` (14 of 24 drafted tokens accepted): 4,475 ms for its 41 tokens with speculation (111.9 ms per token) against 3,018 ms (pass 1) and 3,431 ms (pass 3) without.
- Plain prompts never drafted (answers are one sentence, no repeated 4-token run), so they show no difference beyond heat.
- `mod` (llama.cpp's default, needs a 24-token match and at least 48 drafted tokens) drafted nothing on any of the 12 prompts: same speed as the baselines next to it.
- `simple 12/48` drafted on 2 prompts only (48 tokens each, 4 accepted each), `du6` fell to 8.34 tok/s against 11.7 to 12.7 on the other passes and `ch2` to 10.88.
- Identical answer: three baseline reruns reproduced the first baseline on all 12 prompts, so the model is repeatable on this GPU. With speculation, 11 of 12 matched for both `simple` settings, and the one that did not differed each time it was run. `simple 4/8` changed `du5` from "launch the dashboard on 20 Nov and freeze features on 6 Nov" to "on November 20, ... November 6" (same facts, different words). `simple 12/48` changed the Choir code answer `ch2`: the steps are worded differently and the reply ran to the 128-token cap and stopped mid-JSON (the baseline ended by itself at 110 tokens). So speculation here does not guarantee the identical text even at temperature 0.

## Not measured

- Other lookup lengths or guess lengths, `ngram-map-k`, `ngram-map-k4v` (named in the script, not run) and `ngram-cache`. Draft-model settings (`draft-simple`, `draft-mtp`, ...) need a second model file, which would be an internet download, and were not in scope.
- The app's own temperatures (Data Union 0.2, Choir 0.3): the runs are at temperature 0 as briefed.
- Long verbatim copying (summaries that repeat 50 or more tokens of the source), the one case the `mod` default is built for; none of the app's calls does that.
- The calls through the app's scheduler and client; these runs go straight to the server with the app's arguments.
- Phone A and Phone C. Memory beyond the server process.
- The signed API on port 8787 stopped answering several times while the GPU server ran (a call timed out at the start of the second batch; `state` returned nothing for a few minutes) and answered again after the runs; I did not find the cause.

## Leftovers on the phone

- `/data/local/tmp/llama.cpp/cl-cache/` (about 5.2 MB of compiled OpenCL programs, `.clbin`, written 16:04 to 16:05 by the first standalone server, because it ran without the app's cache directory). No `rm` is allowed here, so it stays; it is harmless.
- Nothing else was pushed. No server of mine is running, the adb forward is removed, chat is READY on the GPU again and embed is READY.
