# Throughput tuning on the iQOO phones (2026-10-10)

Model unless stated: `Qwen3.5-4B-Q4_K_M.gguf`. llama.cpp v0.6.0 (`d812350`). CPU = `i8mm` build, GPU = `opencl` build (Adreno 840, `GGML_OPENCL=ON`, Adreno kernels, embedded kernel source). Start state of every row is the phone's `dumpsys thermalservice` Thermal Status (**S0-S4**) and skin temperature (`quiet_therm`). Rows that started at S2 or higher are marked **throttled** and are not headline numbers. Phones were shared with other workers, so any run whose standard deviation is large is flagged as noisy and excluded from conclusions.

## Decisions (read these)

1. **Use the GPU (OpenCL) build for the chat model: `-ngl 99`.** Same decode speed as the CPU, 4x the prefill, a quarter of the process RSS, and all CPU cores stay free for the app (it heats the phone about as fast as the CPU path over ten minutes). Keep the CPU `i8mm` binaries as the fallback if OpenCL initialisation fails.
2. **Partial GPU offload is worse than either extreme.** `-ngl 16` of 33 gave 82 prefill / 7.5 decode tok/s.
3. **Parallel slots do not add throughput.** Aggregate decode stayed at the single-stream rate (about 12 tok/s) with `-np 2` and `-np 4`, on both CPU and GPU. Use `-np 2` only so two clients can queue without blocking the slot; do not expect more tokens per second.
4. **Thread count (CPU path): 8 threads for prefill, 6-8 for decode; do not pin.** On the GPU path threads are irrelevant (`-t 4` equals `-t 8`).
5. **RPC split is a capacity tool, not a speed tool** (details in `2026-10-10-rpc-split-spike.md`): 3.2 tok/s split vs 14.9 on one phone for a 4B model that fits.

## GPU vs CPU vs partial offload (phone C, cool start, `llama-bench -p 256 -n 64 -r 2`)

| Backend / setting | prefill tok/s | decode tok/s | process VmHWM | start -> end state |
|---|---:|---:|---:|---|
| CPU i8mm, `-t 8` | 32.5 +- 0.8 | 13.58 +- 0.71 | 5,385 MB (anon 2,776) | S0 36.8 C -> S0 39.7 C |
| **OpenCL, `-ngl 99`, `-t 8`** | **127.95 +- 2.3** | 13.55 +- 0.12 | **1,177 MB** (anon 660) | S1 40.4 C -> S1 40.0 C |
| OpenCL, `-ngl 99`, `-t 4` | 126.6 +- 1.6 | 13.32 +- 0.18 | 1,128 MB | S1 41.9 C -> S2 42.1 C |
| OpenCL, `-ngl 16`, `-t 8` (about half the layers) | 82.2 +- 2.8 | 7.48 +- 1.88 | 2,239 MB | S1 40.3 C -> S1 41.8 C |

Phone A, one repetition each, cool start (S0 38.9 C): CPU `-t 8` prefill 23.3 (two reps, 14.9 and 31.7; the first includes page-in) and decode **14.86 +- 0.22**; OpenCL `-ngl 99` prefill **125.6**, decode 13.3.

Gemma 4 E4B QAT Q4_0 (4.8 GiB, phone C, cool start S0 38.9 C): **OpenCL `-ngl 99`, `-t 4`: prefill 164.6 +- 2.7, decode 12.82 +- 0.05, VmHWM 1.78 GB, end S0 39.9 C.** The CPU run of the same file (`-t 8`, started S1 40.2 C, ended S2 43.2 C) was noisy (prefill 82 +- 17, decode 6.3 +- 7.1) and is not used.

Reading: decode is memory-bandwidth bound at about 13.5 tok/s x 2.74 GB = 37 GB/s on either engine, so the GPU cannot beat it; prefill is compute bound and the Adreno wins by 4x. The GPU path does not appear in the process RSS because weights live in GPU buffers: with a GPU server loaded on phone A (4B, `-c 8192 -np 2`), `MemAvailable` fell from 9.94 GB to 5.93 GB (about 4.0 GB of system memory including the 2.7 GB model file in page cache, which the kernel can reclaim), process RSS was 0.72 GB.

Production flags (also copied to `docs/llama-server-api-notes.md`):

```
libllama-server.so -m Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -ngl 99 -t 4 -c 8192 -np 2 --reasoning off
```

OpenCL from an app process: `libOpenCL.so` is listed in `/vendor/etc/public.libraries.txt`, so a child process started from the app can load it; declare `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` in the manifest. The prebuilt GPU executables are in `native/prebuilt/arm64-v8a-opencl/` (server 13.4 MB, rpc-server 4.4 MB, bench 8.2 MB, cli 13.5 MB). The first run after install compiles OpenCL kernels (a few seconds); the kernels are embedded, no network.

## Thread count and pinning (CPU i8mm)

Cool-start data points: phone C `-t 8` above. Phone A cool-start sweeps for `-t 6` and `-t 6` pinned to cores 2-7 were taken at S1 but are **noisy** (standard deviations of 3 to 7 tok/s on decode: another worker's processes shared the phone), so no ranking is drawn from them. The earlier screen-off sweep on phone C (throttled / state not recorded, `docs/benchmarks/2026-10-10-llama-cpu-baseline.md`) is the best evidence: decode 5.3 / 8.6 / 11.7 / 12.4 tok/s at 2 / 4 / 6 / 8 threads, prefill 16 / 27 / 39 / 49; pinning to cores 6-7 helped only the 2-thread case. Because OriginOS caps all cores to 1.4-2.7 GHz (the 4.6 GHz prime cores never run at full clock under load), placement matters little.

## Parallel slots (`par-matrix.sh`: N concurrent 128-token chat requests, `ignore_eos`)

Phone A, `-np N -c 2048*N`, thinking off.

GPU (OpenCL, `-ngl 99 -t 4`, each request set started at S1, last two ended S2):

| `-np` / concurrent requests | per-request decode tok/s | wall for all requests | aggregate tok/s |
|---|---|---:|---:|
| 1 / 1 | 13.34 | 10.3 s | 12.4 |
| 2 / 2 | 13.0, 13.4 | 21.8 s | 11.7 |
| 4 / 4 | 13.0, 13.0, 13.1, 11.0 | 43.8 s | 11.7 |

The requests were served one after another even with free slots (wall time doubles and quadruples while each request still decodes at 13 tok/s): the Adreno path gives no batching gain for this hybrid model.

CPU (i8mm, `-t 8`) - **throttled** (S3-S4, skin 45-50 C, caps 1.55-1.8 GHz), shown for completeness:

| `-np` / concurrent | per-request decode tok/s | aggregate tok/s |
|---|---|---:|
| 1 / 1 | 10.7 | 10.1 |
| 1 / 4 (queued) | 7.7, 11.6, 10.9, 6.7 | 8.3 |
| 2 / 1 | 10.7 | 10.0 |
| 2 / 2 | 5.3, 5.3 | 10.0 |
| 4 / 2 | 2.7, 2.7 | 5.2 |
| 4 / 4 | 0.5 x 4 | 2.0 (thermal collapse, skin 49.5 C, S4) |

Best aggregate: **a single stream** (`-np 1`), equal to `-np 2` with one client. More slots cost memory (KV per slot) and, when hot, collapse throughput. For parallelism use more phones, not more slots.

## Sustained load

Heat is the real limit, on both engines. CPU inference reached S3 within 2-4 minutes from a 38-41 C start (skin 46-51 C), decode 14.9 -> 10.7 tok/s. The single 10-minute GPU run on phone C (S1 40.7 C start) went S2 at 1 min, S3 at about 4 min, S4 at 10 min (skin 48.9 C) with decode **12.3 / 10.6 / 6.8 tok/s at minutes 1 / 5 / 10** (`2026-10-10-llama-cpu-baseline.md`). The GPU is faster at prefill and leaves the cores free, but it does not solve thermals.

## RPC knobs

- **TCP_NODELAY**: already set on both ends, unconditionally (`ggml/src/ggml-rpc/transport.cpp`, `set_no_delay()` in `accept()` and `connect()`). Nothing to enable. `native/patches/rpc-env-nagle.patch` (env `GGML_RPC_NAGLE=1`) exists to measure the opposite; that A/B was **not run** (phone time budget), the finding is "already on".
- **rpc-server `-t`**: not varied. The worker's compute is half the model; with 8 threads it was not the bottleneck (the per-token cost is the 1 MB logits return plus two round trips, see the spike note).
- **Per-hop latency**: not measurable directly with this protocol; derived in the spike note: about 250 ms extra per token for one remote hop pair at 8-10 MB/s and RTT 11 ms, of which about 100 ms is the 1 MB logits return.
- **Faster link**: no faster link than the shared 5 GHz hotspot was available between the phones (USB goes to the PC only). Measured on the quiet link in the spike note.

## Commands

```
native/bench/bench-sweep.sh <serial> /data/local/tmp/llama/<i8mm|opencl> <model> <label> 2 <threads>   # PP=256 TG=64, EXTRA="-ngl 99"
native/bench/par-matrix.sh <serial> /data/local/tmp/llama/opencl <model> 4 "1:1 2:2 4:4" "-ngl 99"
native/bench/runrss.sh, parallel.sh, thermal-log.sh (phone-side helpers)
```
