# Model-server settings on the phone's GPU: a sweep that found nothing worth changing (2026-10-10, 13:43 to 13:53)

Question from the inference audit: would any of these raise speed on the Adreno GPU without touching answer quality: a larger micro-batch (`-ub 1024`), the int8 prefill kernel (`GGML_OPENCL_Q4K_DENSE_DP4A=1`), the split decode kernel (`GGML_OPENCL_Q4K_GEMV_SPLITK=1`), flash attention forced on or off?

Method: Phone C (iQOO 15, 192.168.242.76). `scripts/gpu-bench-sweep.py` pushed the repository's own `libllama-bench.so` (OpenCL build) to the phone, stopped the app's model servers for the duration, and ran `llama-bench -ngl 99 -t 4` on `Qwen3.5-4B-Q4_K_M.gguf`, three repetitions per figure, in the order below, with a wait for Thermal Status 0 or 1 before each variant. Raw output: `raw/gpu-settings-sweep/`.

| Variant | Prefill, 512-token prompt | Prefill, 900-token prompt | Decode, 64 tokens | Thermal before, after |
|---|---|---|---|---|
| baseline, run 1 | 126.8 tok/s | | 13.6 tok/s | |
| `-ub 1024` | 127.0 | 123.6 | | |
| `-ub 512` (the default) | | 121.6 | | |
| int8 prefill kernel, run 1 | 124.8 | | 13.5 | |
| baseline, run 2 | 126.3 | | 13.5 | |
| split decode kernel | 126.5 | | 13.5 | |
| flash attention on | 126.2 | | 13.5 | |
| flash attention off | 128.8 | | 13.5 | |
| int8 prefill kernel, run 2 | 125.1 | | 12.7 | |
| baseline, run 3 | 103.8 | | 10.9 | |

The thermal columns are blank: the script's own output was lost to a buffering mistake of mine and only the per-variant result files were kept; the phone read Thermal Status 1 when the sweep ended.

Result: no variant differs from the baseline by more than 2 %, which is inside the spread of the baseline itself. The largest effect in the table is not a setting at all: the third baseline, ten minutes into the sweep, was 18 % slower on prefill and 20 % slower on decode than the first, with nothing changed but the phone's temperature. None of these settings becomes a default.

What this leaves as the real levers, in the order the audits ranked them: give the model fewer tokens to read (fewer and shorter sources in a Data Union ask), run fewer rounds (Agent Choir), start the GPU server faster (the OpenCL program cache), and keep the phones cool (heat-aware routing). Each of those is being built behind a switch or measured separately in gate 9.

Not tested here because they need a download over the internet: a Q4_0 file of the same model, and the model's multi-token-prediction head for speculative decoding. N-gram speculation needs the server rather than the bench tool and is still to be tried.
