# llama.cpp RPC layer split across iQOO phones (G5 feasibility spike), 2026-10-10

Runtime: llama.cpp v0.6.0 (`d812350`), RPC protocol server `v7.0.0`, CPU i8mm build from `native/prebuilt/arm64-v8a/` (binary names there; on the phones the same files sit in `/data/local/tmp/llama/i8mm/`). Link: one 5 GHz hotspot shared with the PC, phone-to-phone TCP 50-61 Mbps, RTT about 11 ms (`docs/benchmarks/2026-10-09-swarmlink-lan-baseline.md`).
Thermal guard: every timed run started at Thermal Status 0-1 (`dumpsys thermalservice`), skin and status are printed beside each number; nothing was left running between tests.

## Verdict so far

1. **RPC works on Android.** `librpc-server.so` on one phone, `libllama-bench.so` / `libllama-cli.so` / `libllama-server.so` with `--rpc host:50052` on another, layers split as expected, output coherent. No bind/permission problem: `-H 0.0.0.0` on the worker is enough.
2. **Performance with the 4B model is bad: 3.2 tok/s split vs 14.9 tok/s on one phone (cool start), and this is structural, not a tuning accident.** A model that fits one phone should never be split. The split only makes sense for a model that does not fit.
3. **Per-token cost is dominated by the logits.** With the output layer on the remote device the worker returns the full-vocabulary logits every token (`get_tensor size 993280` = 248,320 x 4 B for Qwen3.5; 608 KB for Qwen3-30B-A3B), about 1 MB per token over a link that moves 8-10 MB/s. The forward direction is tiny (three `set_tensor` of 8, 512 and 4 bytes, then `graph_recompute`). For models with an untied `output.weight` (the 30B) `-ot "^output=CPU"` keeps the lm_head on the driver and removes that return traffic; for tied-embedding models (Qwen3.5-4B) it does nothing (tried: 2.7 vs 3.2 tok/s, no effect on bytes).
4. **The `-c` rpc-server cache works**: first load 153 s, second load 41 s, bytes moved over Wi-Fi fell from 1.57 GB to 187 MB.
5. **The 30B model cannot run on one phone** (measured below): no token after 134 s, system thrashing, 23 background apps killed.

## Setup facts (verified)

- Layer placement rule at this tag: layers `i >= n_layer + 1 - ngl` go to the offload devices, **last layers first, and the output layer always goes with them** (log: `offloading output layer to GPU`, `offloaded 16/33 layers`, `layer 17..32 assigned to device RPC0`). The driver keeps embeddings and layers `0 .. n_layer-ngl`. KV cache and recurrent state follow their layers.
- With several `--rpc` servers the offload set is divided in `--rpc` order by `-ts`. In `llama-bench` a **comma in `-ts` starts a new test; use `/`** (`-ts 1/1`). My first three-phone attempt used `-ts 1,1`, which ran two single-device tests (see below).
- `GGML_RPC_DEBUG=1` on the worker prints every command. TCP_NODELAY is set unconditionally on both ends (`ggml/src/ggml-rpc/transport.cpp`, `set_no_delay`), so there is nothing to switch on. `native/patches/rpc-env-nagle.patch` adds `GGML_RPC_NAGLE=1` to turn it off for an A/B (binary in `/data/local/tmp/llama/i8mm-nagle/`).
- Android has no `$HOME`: set `LLAMA_CACHE=/data/local/tmp/llama/cache` or `-c` fails to find a cache directory.
- Worker memory: `VmHWM` 1.56 GB for 16 of 33 layers of the 4B model (about 1.5 GB of weights); the weights are not anonymous memory (RssAnon 4 MB).

## Two phones, Qwen3.5-4B Q4_K_M, driver A, worker B (measured 02:43-02:53, before B was reserved)

Caveat: during these runs the PC was pushing the 30B model to phone C at 7-8 MB/s over the same access point, so Wi-Fi capacity was shared; the numbers are pessimistic. They are repeated with C and A in the next section when the link is quiet.

```
# worker (phone B), PC-side background job:
adb -s 192.168.242.23:5555 shell 'cd /data/local/tmp/llama; LLAMA_CACHE=/data/local/tmp/llama/cache exec ./i8mm/librpc-server.so -H 0.0.0.0 -p 50052 -t 8 -c'
# driver (phone A), all through native/bench/rpc-run.sh:
LOADONLY=1 native/bench/rpc-run.sh rpc2-load-cold 192.168.242.89:5555 /data/local/tmp/llama/models/Qwen3.5-4B-Q4_K_M.gguf 192.168.242.23:50052 192.168.242.23:5555 16 ""
native/bench/rpc-run.sh rpc2-ngl16 ... 16 ""                    # llama-bench -p 256 -n 64 -r 2 -t 8 -rpc 192.168.242.23:50052 -ngl 16
native/bench/rpc-run.sh rpc2-ngl16-outCPU ... 16 "-ot ^output=CPU"
```

| Test | Result | Start state | End state |
|---|---|---|---|
| Load, rpc cache cold (`-p 8 -n 1`) | **153 s** wall; A sent 1.57 GB, B received 1.61 GB; driver VmHWM 4.4 GB (anon 1.74 GB); worker VmHWM 1.56 GB | A status 1, 40.4 C; B status 1, 40.4 C | A status 1, 41.6 C |
| Load, rpc cache warm | **41 s** wall; A sent 187 MB | status 1, 41.2 / 39.8 C | status 1 |
| Split, `-ngl 16` (layers 17-32 + output on B) | **pp256 15.0 +- 0.5 tok/s, tg64 3.18 +- 0.13 tok/s**; A sent 211 MB, B returned 133 MB over the whole run | A status 1, 41.0 C; B status 1, 39.2 C | A status 1, 41.6 C; B status 0 |
| Split, `-ngl 16 -ot ^output=CPU` | pp256 10.3 +- 0.4, tg64 2.68 +- 0.35 (no benefit: tied embeddings) | status 1, 41.2 C | status 1, 41.9 C |
| Debug trace, tg32, no warmup | 1.51 tok/s; per token: 3 small `set_tensor`, `graph_recompute`, `get_tensor` of 993,280 B | | |
| Reference: same 4B model on one phone, cool start | A: tg64 **14.86 +- 0.22**, pp256 23.3; C: pp256 **32.5 +- 0.8**, tg64 **13.58 +- 0.71** (CPU, 8 threads) | status 0, 36.8-38.9 C | status 0-1 |

Per-token latency: 1/3.18 = 314 ms split vs 1/14.86 = 67 ms local, i.e. about 250 ms of overhead per token for one remote hop pair. About 100 ms of that is the 1 MB logits return at 8-10 MB/s; the rest is two RTTs (about 11 ms each), serialisation and the remote half running on a cold-cache worker. Derived, not separately measured.

Correctness: a llama-cli run with `--rpc` (loopback worker on one phone, `-ngl 16`, temperature 0, seed 1) answered coherently ("The capital of France is Paris ... Eiffel Tower"). A two-phone text comparison against the single-phone output was not repeated after B was reserved (pending).

## Three phones (A driver, B and C workers): what was measured and what is pending

Measured (02:55-03:02, A status 1 at start):
- Both workers started, the 4B model loaded, and `llama-bench` produced numbers, but with `-ts 1,1` (two separate tests, each placing every offloaded layer on the first worker; the second worker stayed unused). Results: pp256 6.70 +- 0.02 / 8.80 +- 1.34 tok/s and tg64 3.00 +- 0.31 / 2.16 +- 0.48 tok/s, 376 s wall. **These are not a valid three-way split** and also shared the Wi-Fi with the 30B push.
- The 30B model was **never loaded across three phones**: no tok/s exists.

**Pending, phone B unavailable** (exact commands; run when all three phones are at Thermal Status 0-1):

```
# workers B and C
adb -s 192.168.242.23:5555 shell 'cd /data/local/tmp/llama; LLAMA_CACHE=/data/local/tmp/llama/cache exec ./i8mm/librpc-server.so -H 0.0.0.0 -p 50052 -t 8 -c'
adb -s 192.168.242.76:5555 shell 'cd /data/local/tmp/llama; LLAMA_CACHE=/data/local/tmp/llama/cache exec ./i8mm/librpc-server.so -H 0.0.0.0 -p 50052 -t 8 -c'
# 4B, thirds: ngl 22 puts layers 11-31 + output on the two workers, 11 items each
adb -s 192.168.242.89:5555 shell 'cd /data/local/tmp/llama; ./i8mm/libllama-bench.so -m models/Qwen3.5-4B-Q4_K_M.gguf -rpc 192.168.242.23:50052,192.168.242.76:50052 -ngl 22 -ts 1/1 -t 8 -p 256 -n 64 -r 2'
# 30B, driver C (model file already on C), thirds: ngl 32 -> layers 17-47 + output over A and B, 16 items each, lm_head on the driver
adb -s 192.168.242.76:5555 shell 'cd /data/local/tmp/llama; ./i8mm/libllama-bench.so -m models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf -rpc 192.168.242.89:50052,192.168.242.23:50052 -ngl 32 -ts 1/1 -ot "^output\.weight=CPU" -t 8 -p 64 -n 32 -r 1 --no-warmup'
# through llama-server (what the app will use):
./i8mm/libllama-server.so -m models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --rpc 192.168.242.89:50052,192.168.242.23:50052 -ngl 32 -ts 1,1 -ot '^output\.weight=CPU' -c 2048 -np 1 --reasoning off --host 127.0.0.1 --port 8080
```

## 30B model on one phone (phone C), measured 03:16-03:20

`libllama-bench.so -m Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf -p 16 -n 8 -r 1 --no-warmup -t 8`, started at status 1 (39.7 C), memory sampled every 2 s by `native/bench/guarded-run.sh`:
- 0-60 s: mmap read, file-backed RSS grows to 7.9 GB, anonymous 52 MB.
- 60-110 s: the i8mm repack allocates anonymous memory (peak 3.0 GB) while Android swaps out other apps: `SwapFree` fell from 14.7 GB to 4.1 GB, `MemAvailable` from 10.2 GB to 0.9 GB.
- 03:18:19-21: **lmkd killed 23 cached/background apps** (`am_kill ... low memory`: Chrome, Google search, Play Store, Vivo services, Microsoft link apps; no `com.swarmos.*`).
- 120-134 s: the benchmark's own pages were swapped out (anon RSS 0.6 MB), no token produced. I killed it at 134 s to stop the damage; memory recovered (MemAvailable 11.0 GB, SwapFree 15.3 GB).

So the one-phone baseline for the 30B model is: **does not load usefully; thrashes; no token in 134 s; kills background apps.** That is the honest "cannot run" statement for the demo.
