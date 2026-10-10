# One model slot against two on a phone (2026-10-10, 13:27 to 13:40)

Question from the audit: the chat server is started with two slots (`-np 2`) but the scheduler gives each phone a capacity of 1. Would capacity 2 raise throughput on the GPU by overlapping one job's prompt processing with another's decoding?

Method: Phone C (iQOO 15, 192.168.242.76), debug build of master c2ca589, chat `Qwen3.5-4B-Q4_K_M` on the GPU. `scripts/bench-batch.py` set the scheduler capacity through the debug route `POST /v1/sched/debug/capacity` and ran the built-in 32-prompt batch in local mode (one phone, at most 48 new tokens per job). Order A-B-A-B-A so drift shows up; thermal status read before and after each run. Raw results: `raw/slots-experiment/`.

| Run | Capacity | Wall-clock | Tokens | Aggregate | Decode per sequence | Thermal before, after |
|---|---|---|---|---|---|---|
| cap1-a | 1 | 84.5 s | 896 | 10.61 tok/s | 14.1 tok/s | 0, 0 |
| cap2-a | 2 | 180.6 s | 1128 | 6.24 tok/s | 3.4 tok/s | 0, 0 |
| cap1-b | 1 | 110.7 s | 1262 | 11.40 tok/s | 13.4 tok/s | 0, 1 |
| cap2-b | 2 | 255.0 s | 1391 | 5.45 tok/s | 2.9 tok/s | 1, 1 |
| cap1-c | 1 | 128.2 s | 1332 | 10.39 tok/s | 11.9 tok/s | 1, 2 |

Result: two concurrent sequences ran at 5.5 to 6.2 tokens per second in total against 10.4 to 11.4 with one, about half. Each sequence decoded at about 3 tokens per second instead of 12 to 14. Capacity stays 1.

Notes:
- The total number of tokens differs between runs (896 to 1391) although the prompts and the temperature (0) are the same: answers stopped at different lengths from run to run. The rates above are tokens divided by time, so they are comparable; the wall-clock column is not.
- The single-slot rate fell from 14.1 to 11.9 tokens per second per sequence as the phone went from thermal status 0 to 2 over the 13 minutes. Heat moved the result more than anything else in this table except the slot count.
- This agrees with the earlier native test (11.7 against 12.4 tokens per second, docs/benchmarks/2026-10-10-throughput-tuning.md), which used long identical requests; the open question was short jobs, and they are worse, not better.
