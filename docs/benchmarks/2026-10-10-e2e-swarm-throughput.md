# End-to-end check: swarm throughput, one phone against two (2026-10-10)

What was run: `POST /v1/sched/batch` with the built-in `bench32` (32 prompts "Explain <topic>", system prompt "Answer in one short sentence.", at most 48 new tokens, temperature 0) from Phone A, first in mode `local` (A alone) and then in mode `swarm` (A plus C), twice each, in the order local, swarm, local, swarm. Before every batch the script waited until every phone it used showed Thermal Status 0 or 1 and reloaded the chat model if it had been unloaded to cool. The scheduler reported capacity 1 slot per phone (`GET /v1/sched/phones`). All 32 items finished in every run, no failures, no fake model.

| Field | Value |
|---|---|
| Date and time | 2026-10-10, main run 05:25 to 06:11 IST (steps a to d, f, g); eval continuation from 06:13 IST (step e, see the choir note) |
| Commit tested | master at `8f18a3e` (debug `app-debug.apk` built 05:14, still current for that commit when checked at 05:23; installed on A and C with `adb install -r` at 05:23 and 05:24). Master moved on during the run (`5803b26`, `25b7ac5`, the MemoryCloud merge); those commits are not in the tested build. |
| Script | `scripts/e2e-check.sh` (first committed as `8bddc5c`; the eval continuation used the version that adds retries and `--resume`) |
| Command | `bash scripts/e2e-check.sh 192.168.242.89 192.168.242.76` (raw responses in the scratchpad results directory `e2e-results/20261010-052551`) |
| Phones | A = "Phone A", serial 10BFC41T2N001UZ, 192.168.242.89; C = "Phone C", serial 10BFAT216Z000XQ, 192.168.242.76. Phone B was not contacted. App launched with `am start -n com.swarmos.app/.MainActivity --es name "Phone A" --ez setupDone true` (and "Phone C"). |
| Models | Chat `Qwen3.5-4B-Q4_K_M.gguf`, embedder `embeddinggemma-300M-Q8_0.gguf`, on both phones. `roles.chat.backend` was `CPU` on both phones in every state read: the GPU server did not start (finding F1 below). |
| Link | Same Wi-Fi through the access point (11ac, link speed A 433 Mbps, C 390 Mbps per `/v1/health`), plus a Wi-Fi Direct group hosted by A (SSID DIRECT-sw-c6f72247, 5 GHz, 5765 MHz) with C as client. `/v1/link/peers` listed both paths as online: A sees C over LAN at 31.3 ms and over DIRECT at 17.9 ms; C sees A at 38.2 ms on both. Which path carried the Choir, Data Union and scheduler calls was not recorded. |
| Pairing secret | Not the debug dev secret. Both phones held the same rotated secret (SHA-256 prefix 29757566, read from `GET /v1/link/pairing-payload`); a request signed with `swarmos-dev-secret` was rejected, so the script signed with the secret the debug build publishes. The phones trusted each other throughout (peers online, cross-phone calls worked). After the run both were put back on the dev secret (details in the Choir note, F9). |
| Thermal status at start | Recorded beside every number below (`A=` / `C=`); the script waits for 0 or 1 on every phone it uses before each generation step. |
| Foreground | The base app was brought to the front on both phones at 05:24 and again at 06:13; the `.link` variant of another worker took the foreground between those times at least once (seen at 06:13). |

## Measured

| Run | Mode | Thermal at start A / C | Peak thermal A / C | Wall-clock s | Items done / failed | Total tokens | Aggregate tokens/s | Model | Fake |
|---|---|---|---|---|---|---|---|---|---|
| local-1 | local | 1 / - | 2 / - | 116.4 | 32 / 0 | 916 | 7.87 | Qwen3.5-4B-Q4_K_M | no |
| local-2 | local | 1 / - | 2 / - | 122.9 | 32 / 0 | 916 | 7.45 | Qwen3.5-4B-Q4_K_M | no |
| swarm-1 | swarm | 1 / 0 | 2 / 1 | 44.8 | 32 / 0 | 916 | 20.45 | Qwen3.5-4B-Q4_K_M | no |
| swarm-2 | swarm | 1 / 0 | 2 / 0 | 88.8 | 32 / 0 | 916 | 10.32 | Qwen3.5-4B-Q4_K_M | no |

| Run | Phone | Jobs | Tokens | Share of tokens | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |
|---|---|---|---|---|---|---|
| local-1 | Phone A | 32 | 916 | 100 % | 7.87 | 10.24 |
| local-2 | Phone A | 32 | 916 | 100 % | 7.45 | 9.17 |
| swarm-1 | Phone C | 20 | 549 | 60 % | 12.26 | 15.85 |
| swarm-1 | Phone A | 12 | 367 | 40 % | 8.19 | 10.42 |
| swarm-2 | Phone C | 12 | 336 | 37 % | 3.78 | 16.00 |
| swarm-2 | Phone A | 20 | 580 | 63 % | 6.53 | 10.97 |

Local mean 7.66 tokens/s over 2 run(s) (7.87, 7.45); swarm mean 15.38 tokens/s over 2 run(s) (20.45, 10.32); ratio swarm / local 2.01.

"Peak thermal" in the first table is the highest status seen while the batch ran (polled every 5 s), not the status at the last instant.

## Headline

Two phones together: **15.4 tokens/s** (mean of 20.45 and 10.32) against **7.7 tokens/s** for one phone (mean of 7.87 and 7.45), a ratio of **2.0**, with the two swarm runs 2.0 times apart from each other (1.3 times and 2.7 times the one-phone mean). All six numbers are aggregate tokens per second over the batch wall-clock, short answers (916 tokens in 32 items, 19 to 48 tokens each, mean 28.6, one item hit the 48-token cap). Quote the range, not only the mean.

## Deviations, failures and anything that looked wrong

- **The two swarm runs disagree by a factor of two.** In `swarm-1` the work was balanced: A busy 35.2 s for 12 jobs, C busy 34.6 s for 20 jobs, wall-clock 44.8 s. In `swarm-2` C generated for only 21.0 s (12 jobs, 336 tokens) while A generated for 52.9 s (20 jobs, 580 tokens) and the wall-clock was 88.8 s, so C idled for about 68 s although it decodes faster than A (16.0 against 11.0 tokens/s in that run). Nothing failed and no retry shows in the report; the cause of the uneven split was not found. Both phones were at Thermal Status 0 (C) and 1 (A) when the batch started. Before each swarm batch A had been unloaded and reloaded by the thermal wait ("A still hot after 3 min"), which may matter (first requests after a load are slower); that was not isolated.
- **Per-phone decode speed differs by 1.5 times.** C decodes 15.9 and 16.0 tokens/s in the batches, A 10.2, 9.2, 10.4 and 11.0. A was at Thermal Status 1 at the start of every batch and reached 2 during it; the gate 2 baseline for A was 14.9 tokens/s cool.
- **The one-phone number is not pure decode.** In `local-1` A spent 89.5 s generating out of 116.4 s wall-clock; the other 26.9 s (23 %) went to prompt processing and request handling. With short answers that overhead is part of the "tokens/s" and splits across two phones as well.
- **The 2.7 times of `swarm-1` is not a clean "two phones give 2.7 times".** The one-phone runs ended at Thermal Status 2 and decoded at 9.2 to 10.2 tokens/s, below the 14.9 cool baseline of gate 2, so part of the ratio is a throttled one-phone baseline; this was not isolated. Use the mean and the range.
- Chat ran on the CPU build on both phones: the GPU server failed to start (see the Choir note, finding F1). Nothing here says what the GPU build would give.
- Before every batch A read Thermal Status 2 at first and the script waited about 2 to 4 minutes for it to fall to 1; phone C was at 0 every time.
