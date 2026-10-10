# Prompt time of a short request: where the fixed cost comes from (Phone C, 2026-10-10)

Question: a 17-token job costs about 0.8 s of `timings.prompt_ms` in the chat server, but the GPU prefills long prompts at about 126 tok/s (0.13 s for 17 tokens). What is the rest, and can a server argument or request field remove it without losing prompt-cache reuse or changing the answer?

Setup: iQOO Phone C only (192.168.242.76), debug build, `Qwen3.5-4B-Q4_K_M.gguf`, OpenCL (Adreno 840) llama-server build d812350, context 8192 (4096 per slot), 2 slots, 4 threads, `--reasoning off`, app defaults `-ctxcp 6 -cram 1024` unless the table says otherwise. Keep busy off. Prompts go through the app's signed `POST /v1/sched/chat` (the route the batch uses), temperature 0, max 4 new tokens. Script: `scripts/prompt-cost-probe.py`. Raw files: `docs/benchmarks/raw/gate-9b/prompt-cost/`.

## Method

1. Fresh random-word prompts of 1, 30, 150 and 600 words = 14, 51, 210 and 773 prompt tokens (template included). One unmeasured warm-up round, then 5 rounds in round-robin order per variant; no prompt repeats inside a variant. Straight-line fit of `prompt_ms` against `prompt_n`: the intercept is the fixed cost, the slope the prefill speed.
2. Exact repeat of a 46-token and a 202-token prompt (tokens evaluated on the second call = prompt minus tokens re-used). Then A, B, A with 209-token prompts.
3. Three fixed questions at temperature 0 (32 tokens each) plus one repeat; compared word for word with the default variant.
4. One reload per variant (`POST /v1/models/load` with `args`, 17.5 to 18.8 s to READY, GPU every time). Thermal status read over adb before and after each variant; it never needed a wait (status was 0 or 1 at every start).
5. Explanation runs: the same GPU server started by hand as the adb shell user with the app's arguments plus `-lv 5` (trace log with microsecond stamps, `timings.cache_n` in the answer): `direct-default`, `direct-cram0`, `direct-cacheprompt`. Debug logging makes these runs a little slower; they are used for what happens, not for the table of speeds.

## Result

| variant | extra args | thermal before to after | fixed cost ms | prefill tok/s | promptMs at 14 / 51 / 210 / 773 tokens | tokens re-used on exact repeat (of 46 / of 202) | repeat promptMs (46 / 202) | A,B,A: tokens evaluated for 2nd A (of 209) | request wall minus server time, median ms | answers = default |
|---|---|---|---|---|---|---|---|---|---|---|
| default | none | 0 to 0 | 485 | 128.4 | 652 / 901 / 1996 / 6614 | 42 / 198 | 332 / 343 | 4 | 103 | reference |
| ctxcp0 | -ctxcp 0 | 0 to 1 | 194 | 127.0 | 354 / 618 / 1737 / 6318 | 0 / 0 | 583 / 1713 | 209 | 72 | yes |
| default2 | none | 1 to 1 | 481 | 126.6 | 649 / 903 / 2023 / 6619 | 42 / 198 | 340 / 338 | 4 | 91 | yes |
| cram0 | -cram 0 | 1 to 1 | 475 | 126.1 | 644 / 904 / 2016 / 6661 | 42 / 198 | 336 / 360 | 4 | 47 | yes |
| both | -ctxcp 0 -cram 0 | 1 to 2 | 197 | 127.2 | 351 / 620 / 1745 / 6277 | 0 / 0 | 595 / 1712 | 209 | 52 | yes |
| ctxcp1 | -ctxcp 1 | 0 to 1 | 474 | 125.8 | 647 / 899 / 2017 / 6659 | 42 / 198 | 355 / 356 | 4 | 99 | yes |

Order run: default, ctxcp0, default2, cram0, both, ctxcp1. The two default runs (485 and 481 ms) bracket ctxcp0 (194 ms), so heat does not explain the gap. R squared of every fit is 0.998 or better. Answers: all four temperature-0 answers (three questions and the repeat) were identical word for word in all six variants.

Host cache (3 distinct 210-token prompts through 2 slots, then the first again), direct runs with `cache_n` from the server:

| run | thermal at start | E again after F and G: cache_n / prompt_n / prompt_ms |
|---|---|---|
| default (`-cram 1024`) | 1 | 207 / 4 / 340 |
| `-cram 0` | 0 | 0 / 211 / 2045 |

Request field `cache_prompt` (direct run, default args, thermal 1, fresh 51-token prompts alternating): off 921, 913, 914 ms; on 939, 914, 922 ms. No difference.

Fresh server, built-in 32-job batch on Phone C (`batch-freshserver.json`, thermal 0 to 1): promptMs median 674 ms for 16 to 29 prompt tokens, decode 13.4 tok/s. The recorded batch of earlier today (`gate-9/g9-regress/batch-final-local-C.json`) had median 811 ms and 10.8 tok/s decode on the same phone: about 20 % slower on both, the size of the heat effect. Its thermal status was not recorded, so that is not proven.

## Cause

- Measured: the fixed cost is 485 ms (intercept), the 14-token prompt costs 652 ms, and `-ctxcp 0` takes the intercept to 194 ms (354 ms for 14 tokens) with the same slope. So about 290 ms per request comes from the context checkpoint.
- Why (source): Qwen3.5 is a hybrid model with a recurrent state, so the only way the server can re-use a prompt is to restore a saved copy of that state (`server-context.cpp:3708-3721` allows checkpoints for this memory type; with none, `:3609-3614` forces full re-processing, which is what the `-ctxcp 0` repeat did). To make the checkpoint the server stops the prompt 4 tokens before its end, saves the state, then runs the last 4 tokens as a second pass (`:3829-3848`; `create_checkpoint` at `:2513-2576`, called at `:3903`). `prompt_ms` runs from the start of that work to the first sampled token (`server-common.h:431-436`, `server-context.cpp:4201-4206`), so both passes are inside it.
- Measured in the trace (`direct-default.log`, task 0, a 13-token prompt): first pass 9 tokens, then `created context checkpoint 1 of 6 (pos_min = 8, pos_max = 8, n_tokens = 9, size = 50.251 MiB)`, then a second pass of 4 tokens. A 4-token pass from start to the next pass took 398 ms (task 12) against 183 ms and 95 ms for the next two decode passes; in the app route the same 4-token pass costs about 330 ms in total (the repeat rows).
- Measured: the 50 MiB copy itself is small. With no prompt work queued before it, creating a checkpoint took 37, 48 and 13 ms and restoring one 18, 24 and 2 ms (tasks 12, 23, 46). On fresh prompts the log shows 391 ms to 1.7 s for the same call because reading the state waits for the previous prompt pass on the GPU (inferred from source: the decode is asynchronous). So the cost is the extra prefill pass (about 250 to 300 ms of fixed work per pass), not the data.
- Not identified: why one prefill pass of a few tokens costs 250 to 300 ms on this GPU build while a one-token decode pass costs 95 ms. The graph has only 2 splits (`sched_reserve: graph: nodes = 1944, splits = 2`), so it is not a CPU fallback per layer. Inferred only: a per-pass cost in the OpenCL kernels for batches larger than 1.
- Outside `prompt_ms`: before the prompt timer starts, the server copies the other idle slot into the host cache (`--cache-idle-slots`, default on; trace: `prompt_save` to `new prompt` 47 ms). That is the 50 ms gap between `default` and `cram0` in the last-but-one column; it is request time but not promptMs.

## What removes it and what it costs

| lever | removes the 290 ms | keeps reuse | note |
|---|---|---|---|
| `-ctxcp 0` | yes | no | repeat of a 202-token prompt goes from 343 to 1713 ms; A,B,A re-evaluates 209 tokens |
| `-ctxcp 1` | no | yes | measured: 474 ms fixed, repeats still hit. One checkpoint is made per request whatever the cap is (so larger caps do not help either: inferred from `:2513-2576`) |
| `-cram 0` | no | partly | repeats and A,B,A still hit (2 slots), the third distinct prompt no longer does (211 tokens, 2045 ms); saves about 50 ms of non-prompt request time |
| request field `cache_prompt` false | no | no | prompt_ms unchanged; reuse is lost for that call |
| `-cms`, `--no-cache-idle-slots`, `-sps`, `-lv` | not tried | | not on the debug allow-list in `llm/DebugLoad.kt`; no flag changes the hard-coded 4-token offset |

Break-even (from the table, not a new measurement): the checkpoint costs 290 ms on every fresh request and saves about 1370 ms on a repeat of a 210-token prompt (1713 to 343 ms), so it pays when more than one request in five repeats a prompt of that size. For prompts under about 60 tokens the repeat saves only 250 ms (583 to 332) and it never pays. The batch jobs (16 to 29 tokens, all different) get nothing from it.

Recommendation: no change to `LlamaArgs.kt:112-113` (`CHAT_CTX_CHECKPOINTS = 6`, `CHAT_CACHE_RAM_MIB = 1024`). No argument or request field removes the cost and keeps reuse. If the demo's batch path matters more than Choir and Data Union reuse, `CHAT_CTX_CHECKPOINTS = 0` for a batch session is the only lever, worth about 0.29 s on a 0.67 to 0.8 s prompt time, at the loss of all reuse.

## Not measured

- RAM high-water per variant (only the post-load `rssMb` is in the raw JSON), per-variant decode tok/s (4-token answers give no clean figure; batch decode is above).
- Phone B and Phone A, the CPU build, other models, temperature above 0, answers beyond 4 prompts of 32 tokens.
- A `-ctxcp 0` trace with `-lv 5` to show the single pass directly (the one-pass claim rests on the source and on the 194 ms intercept).
- What a prefill pass does for 250 to 300 ms; flags outside the allow-list.
- Thermal status of the earlier 811 ms batch. The variants ended at thermal 1 or 2 (both ended at 2); the heat effect on this table is not separated beyond the bracketing default runs.
- A first attempt with prompts of random word-plus-number tokens ran 3.3 tokens per word (up to 2600 tokens); it was stopped and nothing from it is used.

## Phone state after the runs

Chat reloaded with no extra arguments: READY, GPU, `Qwen3.5-4B-Q4_K_M.gguf`, loadMs 18157. Hand-started servers were stopped (checked before the reload: no `libllama-server-gpu.so` process left; now only the app's own, pid 16650) and the `adb forward` was removed. Nothing was pushed to or deleted from the phone.
