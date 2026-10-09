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

## Sustained 10-minute run

Not run yet: the thermal guard limits sustained testing to one 10-minute run on phone C once the phone is back to Thermal Status 0-1. The runs above already show the answer qualitatively (Thermal Status 3-4 within a few minutes; decode 14.9 tok/s cool vs 10.7 tok/s at status 3 on phone A). Script ready: `native/bench/sustain.sh`.

## Gemma 4 E4B (second family)

File is on the PC and on phone C, but no number was taken: the thermal guard and the other worker's use of phone C blocked a clean run. Open item.

## Commands

```
# phone-side layout: /data/local/tmp/llama/{default,i8mm,sve2,kleidi,opencl}/lib*.so, models/
bash native/bench/bench-sweep.sh <serial> /data/local/tmp/llama/i8mm /data/local/tmp/llama/models/Qwen3.5-4B-Q4_K_M.gguf <label> <reps> 2 4 6 8 2:c0 4:f0 6:3f
PP=256 TG=64 COOL_MC=37000 KEEPAWAKE=0 bash native/bench/bench-sweep.sh ...   # cold-start protocol
```
