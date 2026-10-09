# llama.cpp CPU baseline on the iQOO phones (2026-10-10)

Runtime: llama.cpp v0.6.0 (`d812350`), NDK 28.2, Release, static, `GGML_RPC=ON`, built by `native/build-llama-android.sh`. Model: `Qwen3.5-4B-Q4_K_M.gguf` (2,740,937,888 B, sha256 `00fe7986...ef11a4`), `llama-bench`, KV f16, flash-attn auto. Phones: iQOO I2501, Snapdragon 8 Elite Gen 5, 16 GB, Android 16, cores 0-5 = 3.63 GHz class, 6-7 = 4.6 GHz prime class.
Harnesses: `native/bench/bench-sweep.sh`, `runrss.sh`, `par-matrix.sh`, `parallel.sh`, `thermal-log.sh` (all print thermal state around each run).

## Read this first: the phones throttle hard, and numbers depend on thermal state

1. **Frequency caps are far below the hardware maximum.** `cpuinfo_max_freq` says 3.63 / 4.6 GHz, but `scaling_max_freq` (set by OriginOS) was **1.9 GHz (cores 0-5) and 1.38 GHz (cores 6-7)** with the screen off, **2.75 / 2.67 GHz** with the screen on (`KEYCODE_WAKEUP`), and fell to **1.55-1.79 / 1.38-1.63 GHz** once Thermal Status reached 3-4. The two "prime" cores are never faster than the others under load in this state, so thread pinning to them gains little.
2. **Heat arrives in minutes.** From a cool start (Thermal Status 0, skin 35-39 C) a single bench run moved the skin sensor `quiet_therm` to 42-46 C and Thermal Status to 2-3 within 1.5-4 minutes, and 3-4 sustained (skin 46-51 C). Cool-down is about 0.3 C per minute with the screen on, so a phone stays throttled for tens of minutes after a run. Phone C was on battery (`AC powered: false`) at several points.
3. **Marking.** Numbers below are tagged `cool` (run started at Thermal Status 0-1) or `throttled` (started at status >= 2, or the status at the start was not recorded). Headline numbers were re-taken from a cool start; see the cool-start table.
4. Other workers were running llama-server benchmarks on phones B and C at the same time (590% CPU on B, 430% on C), and each of their 4B servers keeps about 6 GB resident. Phone C numbers from 01:20 onward are contaminated (one config measured 1.0 tok/s while the other worker's Gemma server held the CPU and RAM); they are excluded. Free RAM was about 6.5 GB per phone during the later runs.

## CPU instruction-set variant (Qwen3.5-4B Q4_K_M, phone C, screen off, throttled / state not recorded)

| Variant (`-march`) | threads | prefill pp512 tok/s | decode tg128 tok/s |
|---|---:|---:|---:|
| default (armv8-a, upstream default) | 6 | 10.4 | 8.1 |
| **i8mm** (armv8.6-a+dotprod+fp16+i8mm) | 6 | **38.2** | **11.8** |
| sve2 (armv9-a+...+sve2) | 6 | 31.1 | 11.8 |
| i8mm + KleidiAI | 8 | 47.4 | 12.4 |
| i8mm, no KleidiAI | 8 | 48.9 | 12.4 |

Winner: **i8mm** (shipped in `native/prebuilt/arm64-v8a/`). The default Android build is 3.7x slower on prefill because it has no int8 matrix-multiply. SVE2 is no faster for decode and slower for prefill. KleidiAI adds nothing for a Q4_K_M file (its kernels target Q4_0/Q8_0); it may help a Q4_0 model such as the Gemma 4 QAT file, untested.

## Thread count and pinning (i8mm, phone C, screen off, 3 reps, throttled / state not recorded)

The sweep ended with Thermal Status 3, so the later rows ran hotter than the first.

| threads / pinning | pp512 tok/s | tg128 tok/s |
|---|---:|---:|
| 2 | 16.1 | 5.3 |
| 3 | 19.3 | 6.6 |
| 4 | 27.0 | 8.6 |
| 6 | 38.7 | 11.7 |
| **8** | **48.9** | **12.4** |
| 2 pinned to cores 6-7 (`taskset c0`) | 19.4 | 6.5 |
| 4 pinned to cores 4-7 (`f0`) | 31.6 | 9.6 |
| 6 pinned to cores 0-5 (`3f`) | 35.4 | 10.2 |

Decode saturates at 6-8 threads: 12.4 tok/s x 2.74 GB of weights is 34 GB/s. All 8 threads is best for prefill (+26% over 6); decode is within 6%. Pinning to the prime cores helps only at 2 threads (+23%) and loses at 6 (pinned `3f` 10.2 vs unpinned 11.7). Recommendation: 8 threads for a dedicated worker, 6 if the app UI must stay smooth. The cool-start re-take is in the next section.

## Cool-start headline (phone C, Thermal Status 0, skin 36.8 C, pp256 / tg64, 2 reps)

| Test | prefill tok/s | decode tok/s | VmHWM | end state |
|---|---:|---:|---:|---|
| **CPU i8mm, 8 threads** | **32.5 +- 0.8** | **13.58 +- 0.71** | 5,385 MB (anon 2,776) | S0, 39.7 C |
| OpenCL (Adreno 840) `-ngl 99` | 127.9 +- 2.3 | 13.55 +- 0.12 | 1,177 MB | S1, 40.0 C |

## Cool-start re-take (phone A, started at Thermal Status 0, skin 38.9 C, screen off)

| Test | prefill tok/s | decode tok/s | thermal at end |
|---|---:|---:|---|
| CPU i8mm, 8 threads, pp256 / tg64, 2 reps | 23.3 +- 11.9 (two reps of about 14.9 and 31.7, derived from mean and stddev; first rep includes page-in) | **14.86 +- 0.22** | status 1, skin 41.8 C |
| **OpenCL (Adreno 840), `-ngl 99`**, pp256 / tg64, 1 rep | **125.6** | 13.3 | status 2, skin 42.1 C |

GPU prefill is 3-5x the CPU's; decode is about the same (both are bandwidth bound), so for this model the GPU is the better prefill engine and costs almost no CPU. Full CPU/GPU/partial-offload comparison is in `2026-10-10-throughput-tuning.md`.

## Peak memory (llama-bench, i8mm, 8 threads)

VmHWM **5.4 GB**, anonymous (RssAnon) **2.8 GB**. The i8mm build repacks Q4_K weights into an anonymous buffer (2.8 GB) while the mmapped file pages (2.7 GB) stay mapped and count in RSS until the kernel evicts them. The default (non-i8mm) build keeps VmHWM 2.8 GB / anon 0.2 GB because it does not repack. A llama-server with 2 slots, context 4096, adds about 0.1 GB.

## llama-server decode rates seen through the API (phone C, throttled / state not recorded)

12.8-13.0 tok/s decode, 17-36 tok/s prefill on 20-token prompts (first request after load: 3 s page-in penalty). With thinking on Qwen3.5 spends 217 tokens (17 s) to answer a 5-token question; use `--reasoning off`.

## Sustained 10-minute run (phone C, GPU build, the single permitted run)

`llama-server` OpenCL build `-ngl 99 -t 4 -c 2048 -np 1 --reasoning off`, 128-token generations back to back through `/completion` (`ignore_eos`, fresh slot each time) for 10 minutes, phone idle otherwise, started at **Thermal Status 1, skin 40.7 C**, thermal sampled every 30 s (`native/bench/sustain.sh`, raw log kept in `third_party/logs/sustain-c-gpu.log`):

| Minute | decode tok/s (mean of requests) | Thermal Status | skin |
|---|---:|---|---|
| 1 | **12.3** (6 requests, 10.5-13.0) | S1 -> S2 at 63 s | 40.7 -> 43.3 C |
| 5 | **10.6** (4 requests, 10.58-10.62) | S3 from 220 s | about 45.7 C |
| 10 | **6.8** (3 requests, 4.1-8.2) | S3 -> S4 at 590 s | 48.9 C |

Decode fell 45% over ten minutes while the SoC clock cap dropped (cpu0 cap 1.8 GHz, prime cores 0.88 GHz sampled). The run reached Thermal Status 4 (critical) at the end; the script aborts at S4, and the phone was left idle afterwards. **Plan demos for 3-4 minutes of continuous generation per phone, or rotate which phone is generating.** The GPU path is not exempt: it heats about as fast as the CPU path in this chassis (the 60-second single bench looked flat; ten minutes did not).

## Gemma 4 E4B QAT Q4_0 (second family), phone C

Cool start (S0, 38.9 C), `llama-bench -p 256 -n 64 -r 2`: **OpenCL `-ngl 99`: prefill 164.6 +- 2.7 tok/s, decode 12.82 +- 0.05 tok/s**, VmHWM 1.78 GB, ended S0 39.9 C. The CPU i8mm run of the same file started at S1 (40.2 C) and ended S2 (43.2 C) and was noisy (prefill 82 +- 17, decode 6.3 +- 7.1); not usable. Gemma 4 E4B (4.8 GiB, larger than Qwen3.5-4B) decodes about as fast as the 4B Qwen on the GPU and prefills 29% faster.

## Commands

```
# phone-side layout: /data/local/tmp/llama/{default,i8mm,sve2,kleidi,opencl}/lib*.so, models/
bash native/bench/bench-sweep.sh <serial> /data/local/tmp/llama/i8mm /data/local/tmp/llama/models/Qwen3.5-4B-Q4_K_M.gguf <label> <reps> 2 4 6 8 2:c0 4:f0 6:3f
PP=256 TG=64 COOL_MC=37000 KEEPAWAKE=0 bash native/bench/bench-sweep.sh ...   # cold-start protocol
```
