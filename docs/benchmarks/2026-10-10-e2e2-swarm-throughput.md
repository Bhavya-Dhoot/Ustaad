# End-to-end check 2, swarm throughput on the GPU build (2026-10-10)

What was run (`scripts/e2e2-check.sh --steps t,t2`, build `5569e36`): `POST /v1/sched/batch` with the built-in `bench32` (32 prompts "Explain <topic>", at most 48 new tokens, temperature 0). First `local` on each phone alone (once each), then `swarm` posted to Phone B, which uses every phone that is online: five runs with three phones online and, after the app on Phone A was force-stopped, two runs meant for two phones. Before every batch the script waited for Thermal Status 0 or 1 on the phones it guarded and reloaded the chat model if it had been unloaded. All 32 items finished in every run, no failures, no fake model, chat backend GPU, answer MAC checked on every call.

| Field | Value |
|---|---|
| Date and time | 2026-10-10, 08:18 to 09:17 IST (queue acquired for phones A, B, C at 08:18:25, markers released at 09:17:19). Data Union and privacy 08:22 to 08:29, Knowledge Bank 08:29 to 08:33, throughput 08:33 to 08:43, Choir pass 1 08:46 to 09:05, pass 2 09:06 to 09:17. |
| Commit tested | Data Union, Knowledge Bank and throughput: master `5569e36` (debug APK built 08:08:10, installed on all three phones 08:18 to 08:20 with `adb install -r`, no `pm clear`). Agent Choir: master `e2d41e4` (APK built 08:25:52, installed 08:44). Not `5569e36` for the Choir. |
| Script | `scripts/e2e2-check.sh` with `scripts/e2e/e2e2.py`, committed with these files (hash in the report that came with this run). |
| Phones | B = "Phone B", 192.168.242.23 (asker and coordinator, first address); C = "Phone C", 192.168.242.76; A = "Phone A", 192.168.242.89 (back on the network at 08:16, third phone). App launched with `am start -n com.swarmos.app/.MainActivity --es name "Phone X" --ez setupDone true --ez autoload true`. |
| Models | Chat `Qwen3.5-4B-Q4_K_M.gguf`, embedder `embeddinggemma-300M-Q8_0.gguf` on all three. **`roles.chat.backend` was `GPU` on all three phones** in every state read (s6 checks at 08:21 and 08:44, and in every batch row below). |
| Link | Same Wi-Fi through the access point (11ac; link speed B 325, C 390, A 390 Mbps). No Wi-Fi Direct group was up. `/v1/link/peers` RTT over LAN: B to A 42.8 ms, B to C 58.9 ms, C to A 38.4 ms, C to B 52.6 ms, A to B 58.3 ms, A to C 41.3 ms. |
| Pairing secret | The debug dev secret `swarmos-dev-secret` on all three phones (signed health answered 200). Request signing: raw URI, `X-Swarm-Req` on every call. **Every answer was checked: `X-Swarm-Rsp` equals HMAC-SHA256(secret, "rsp\n$status\n$reqid\n$body")**: 332 answers checked over the whole run (steps 10, 16 in the aborted first Data Union attempt, 46, 69, 78, 28, 56 and 29), 0 wrong, 0 missing. |
| Thermal status at start | Recorded beside every number (`Phone B=` / `C=` / `A=`); the script waits for 0 or 1 on every phone it uses before each generation step. A read 3 at 08:16 (another worker) and 2 at 08:21. |
| Foreground | Base app launched on each phone at install time (08:18 to 08:20 and again 08:44 for the Choir build). Whether another worker's variant took the front during the run was not checked on every phone. |

## Measured

| Run | Mode | Phones | Coordinator | Thermal at start | Peak thermal | Wall-clock s | Done / failed | Tokens | Aggregate tokens/s | Backend |
|---|---|---|---|---|---|---|---|---|---|---|
| single-Phone-B | local | 1 | Phone B | Phone B=0 | Phone B=1 | 88.8 | 32 / 0 | 896 | 10.09 | GPU |
| single-Phone-C | local | 1 | Phone C | Phone C=0 | Phone C=0 | 74.5 | 32 / 0 | 896 | 12.03 | GPU |
| single-Phone-A | local | 1 | Phone A | Phone A=0 | Phone A=1 | 87.1 | 32 / 0 | 896 | 10.29 | GPU |
| swarm3-a | swarm | 3 | Phone B | Phone B=0 Phone C=0 | Phone B=0 Phone C=0 | 29.0 | 32 / 0 | 896 | 30.94 | GPU |
| swarm3-b | swarm | 3 | Phone B | Phone B=0 Phone C=0 | Phone B=0 Phone C=0 | 28.1 | 32 / 0 | 896 | 31.94 | GPU |
| swarm3-c | swarm | 3 | Phone B | Phone B=1 Phone C=0 Phone A=1 | Phone B=1 Phone C=0 Phone A=1 | 28.2 | 32 / 0 | 896 | 31.76 | GPU |
| swarm3-d | swarm | 3 | Phone B | Phone B=1 Phone C=0 Phone A=1 | Phone B=1 Phone C=0 Phone A=2 | 28.9 | 32 / 0 | 896 | 31.00 | GPU |
| swarm2-1 | swarm | 2 | Phone B | Phone B=1 Phone C=0 | Phone B=1 Phone C=0 | 47.6 | 32 / 0 | 896 | 18.83 | GPU |
| swarm2-2 | swarm | 3 | Phone B | Phone B=1 Phone C=0 | Phone B=2 Phone C=0 | 31.0 | 32 / 0 | 896 | 28.90 | GPU |

| Run | Phone | Jobs | Tokens | Share of tokens | Busy s (sum of generation time) | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |
|---|---|---|---|---|---|---|---|
| single-Phone-B | Phone B | 32 | 896 | 100 % | 67.2 | 10.09 | 13.34 |
| single-Phone-C | Phone C | 32 | 896 | 100 % | 56.6 | 12.03 | 15.84 |
| single-Phone-A | Phone A | 32 | 896 | 100 % | 64.8 | 10.29 | 13.83 |
| swarm3-a | Phone B | 11 | 304 | 34 % | 23.2 | 10.50 | 13.10 |
| swarm3-a | Phone C | 11 | 317 | 35 % | 22.4 | 10.95 | 14.13 |
| swarm3-a | Phone A | 10 | 275 | 31 % | 20.6 | 9.50 | 13.38 |
| swarm3-b | Phone B | 10 | 267 | 30 % | 20.8 | 9.52 | 12.86 |
| swarm3-b | Phone C | 12 | 322 | 36 % | 22.8 | 11.48 | 14.15 |
| swarm3-b | Phone A | 10 | 307 | 34 % | 22.9 | 10.94 | 13.41 |
| swarm3-c | Phone B | 10 | 293 | 33 % | 22.6 | 10.39 | 12.98 |
| swarm3-c | Phone C | 12 | 322 | 36 % | 23.0 | 11.41 | 13.99 |
| swarm3-c | Phone A | 10 | 281 | 31 % | 20.9 | 9.96 | 13.43 |
| swarm3-d | Phone B | 11 | 295 | 33 % | 22.9 | 10.21 | 12.91 |
| swarm3-d | Phone C | 11 | 306 | 34 % | 22.9 | 10.59 | 13.39 |
| swarm3-d | Phone A | 10 | 295 | 33 % | 22.0 | 10.21 | 13.39 |
| swarm2-1 | Phone B | 16 | 460 | 51 % | 35.3 | 9.67 | 13.03 |
| swarm2-1 | Phone C | 16 | 436 | 49 % | 32.5 | 9.16 | 13.40 |
| swarm2-2 | Phone B | 10 | 296 | 33 % | 22.2 | 9.55 | 13.34 |
| swarm2-2 | Phone C | 11 | 306 | 34 % | 22.8 | 9.87 | 13.41 |
| swarm2-2 | Phone A | 11 | 294 | 33 % | 21.9 | 9.48 | 13.44 |

Single phones: single-Phone-B 10.09, single-Phone-C 12.03, single-Phone-A 10.29. Fastest single phone: single-Phone-C at 12.03 tokens/s.
Swarm of 2 phones: 18.83 tokens/s (mean 18.83); against the fastest single phone: 1.56x (mean 1.56x).
Swarm of 3 phones: 30.94, 31.94, 31.76, 31.00, 28.90 tokens/s (mean 30.91); against the fastest single phone: 2.57x, 2.65x, 2.64x, 2.58x, 2.40x (mean 2.57x).

"Peak thermal" is the highest status seen while the batch ran (polled every 5 s). Run names: `swarm3-a` and `swarm3-b` were started as "two-phone" runs by the script (tags `swarm2-1/2`), but swarm mode uses every phone that is online, and Phone A was online, so they are three-phone runs; I renamed their files. Only Phone B and Phone C were thermally guarded for those two (the thermal columns above show just those two), Phone A's status was not read.

## Headline

Aggregate tokens per second over the batch wall-clock, against the **fastest single phone, Phone C at 12.03**:

| Phones | Runs (tokens/s) | Mean | Against the fastest single phone (12.03) |
|---|---|---|---|
| 1 (each alone) | B 10.09, C 12.03, A 10.29 | 10.80 | fastest is C |
| 2 (B + C) | 18.83 (one run) | 18.83 | 1.56 times |
| 3 (B + C + A) | 30.94, 31.94, 31.76, 31.00, 28.90 | 30.91 | 2.57 times (2.40 to 2.65) |

Job split per phone is in the second table: with three phones each took 30 to 36 % of the tokens (10 to 12 of the 32 jobs) and was generating for 21 to 23 s of a 28 to 31 s batch; with two phones 16 and 16 jobs, busy 35.3 s and 32.5 s of 47.6 s.

## Deviations, failures and anything that looked wrong

1. **Only one valid two-phone run.** After the app on Phone A was force-stopped (08:40:57) the first swarm run had two phones; for the second, about a minute later, Phone A's app was back online and took 11 of the 32 jobs (the run is counted as a three-phone run, 28.90 tokens/s). Something restarted the app on A within about two minutes of a force-stop (the apps I force-stopped on C and B in earlier steps were relaunched by my script, so there is nothing to compare with); I did not look for the cause. The script logged `FAIL` three times (two batches tagged as two-phone runs in the first batch step, and `swarm2-2`): that is my assertion ("exactly N phones took jobs") being wrong about the scheduler, not a failed batch.
2. **The two-phone run is slower than the sum of the parts.** B and C alone give 10.09 + 12.03 = 22.1 tokens/s; together they gave 18.83 (85 %), with each phone idle for a quarter to a third of the 47.6 s; both phones had their model servers reloaded just before this run (the thermal wait had unloaded them), so a cold first request may be in it (inference, not isolated). The three-phone runs reach 95 % of the sum of three single phones (30.9 of 32.4).
3. **The fastest single phone is C, not the coordinator.** All swarm runs were posted to Phone B (10.09 tokens/s alone); with three phones the swarm beats the fastest single phone by 2.57 times. Per-phone decode speed: C 15.8, A 13.8, B 13.3 tokens/s (single runs); the same ordering inside the swarm runs (C 13.4 to 14.2).
4. **Compared with the first run on the CPU build** (`2026-10-10-e2e-swarm-throughput.md`): Phone A alone 7.66 tokens/s then, 10.29 now (1.34 times); two phones then 15.38 against the CPU single phone, now 18.83. The first run's uneven split (20 of 32 jobs on the slower phone, 68 s idle on the faster) did not show up: in all six swarm runs here every phone got 10 to 16 jobs and the split followed the speeds. Different builds and different phones' thermal states; not a controlled comparison.
5. **Thermal.** Every guarded phone started at 0 or 1; Phone A and Phone B reached 2 at most (once each) during a batch; nothing throttled visibly (per-phone decode speed 12.9 to 14.2 tokens/s in the swarm runs, 13.3 to 15.8 alone).
6. The batch uses 28.6 tokens per item on average (896 tokens in 32 items), short answers, so prompt processing and request handling are a large share of the wall-clock (each phone generated for about 75 % of it).
