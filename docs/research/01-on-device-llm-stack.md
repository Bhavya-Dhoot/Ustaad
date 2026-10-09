# On-device LLM stack for iQOO (SM8850 / 8 Elite Gen 5, 16 GB, Android 16) - research as of 2026-10-09

Scope: pick open-weight models and Android runtimes for a hackathon where 2-3 phones cooperate over local Wi-Fi.

Evidence rules. "(verified)" = read from the cited primary page (HF model card / HF API / GitHub) in this session. "(unverified)" = secondary blog/aggregator, search snippet only, or my own estimate. Numbers marked "(inferred)" are arithmetic on verified numbers. Many aggregator sites (llmgateway, contextstudios, tech blogs) are low-reliability; I only trust them where a HF page confirmed the model exists.

IMPORTANT caveat on all phone numbers: almost all Gen 5 phone benchmarks below are Qualcomm's own cards measured on "Snapdragon 8 Elite Gen 5 For Galaxy" (Galaxy S26-class reference). An iQOO 15 has the same SoC (SM8850) but different cooling/thermal governor/RAM tuning. Expect +/- 20% and sustained-load throttling (see Risks). Nobody has published sustained-LLM numbers for the iQOO 15 (searched, none found).

---------------------------------------------------------------------------

## 1. Models

### 1.0 How to read decode numbers (sanity check)
Decode is memory-bandwidth bound. SM8850 is paired with LPDDR5X (peak ~76.8 GB/s for a 9600 MT/s x 64-bit bus - spec arithmetic, (unverified) for the exact iQOO configuration). Ceiling = bandwidth / bytes read per token. Gemma 4 E4B is a 3.65 GB file -> ceiling ~21 tok/s (inferred); measured 17.7-22.1 tok/s on LiteRT-LM and 12.6-17.6 on GenieX llama.cpp. So measured numbers sit near the physical ceiling and a "7-9B at 20 tok/s" claim should be disbelieved. Prefill is compute bound and is where the NPU/GPU help (1000+ tok/s vs ~200 on CPU).

### 1.1 Verified phone measurements (the numbers you can plan with)

| Model | Runtime / precision | Device | Ctx | Decode tok/s | TTFT / prefill | Source |
|---|---|---|---|---|---|---|
| Gemma 4 E4B (3.65 GB) | LiteRT-LM GPU | S26 Ultra (SM8850) | 2048 (1024 pf / 256 dec) | 22.1 | prefill 1,293 tok/s, TTFT 0.8 s | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm (verified) |
| Gemma 4 E4B | LiteRT-LM CPU (4 thr) | S26 Ultra | 2048 | 17.7 | prefill 195 tok/s, TTFT 5.3 s | same (verified) |
| Gemma 4 E4B | LiteRT-LM GPU + speculative (MTP) | S26 Ultra | - | 36.7-49.4 (baseline 21.9) | - | same (verified); CPU spec: 21.1-29.5 vs 17.0 |
| Gemma 4 E4B | GenieX llama.cpp q4_0 | 8 Elite Gen 5 (Galaxy) | 512 / 4096 | 16.9-17.6 / 12.6-16.1 | TTFT 0.12-5.47 s (512 ctx), up to 68 s at 4096-token prompt on slowest row | https://huggingface.co/qualcomm/Gemma-4-E4B-it (verified) |
| Gemma 4 E4B | GenieX QAIRT w4a16 (NPU) | 8 Elite Gen 5 | 4096 | 10.07 | TTFT 0.21-6.81 s | same (verified) |
| Gemma 4 E2B | GenieX llama.cpp q4_0 | 8 Elite Gen 5 | 512 / 4096 | 31.7-33.7 / 22.3-32.8 | TTFT 0.07-2.11 s (512); 0.08-41 s (4096) | https://huggingface.co/qualcomm/Gemma-4-E2B-it (verified) |
| Qwen3.5-2B | GenieX llama.cpp q4_0 | 8 Elite Gen 5 | 512 / 4096 | 30.4-40.2 / 24.4-31.1 | TTFT 0.14-1.56 s (512); 0.18-22 s (4096) | https://huggingface.co/qualcomm/Qwen3.5-2B (verified) |
| Phi-4-Mini 3.8B | GenieX llama.cpp q4_0 | 8 Elite Gen 5 | 512 / 4096 | 21.4-23.2 / 7.2-13.1 | TTFT 0.09-4.78 s; up to 80 s at 4096 | https://huggingface.co/qualcomm/Phi-4-Mini-Instruct (verified) |
| Qwen3-VL-4B | GenieX llama.cpp q4_0 / QAIRT w4a16 | 8 Elite Gen 5 | 512 / 4096 | ~20-21 / 6-14 (llama.cpp); ~15-16 flat (QAIRT NPU) | QAIRT TTFT 0.10-3.31 s at 4096 | https://huggingface.co/qualcomm/Qwen3-VL-4B-Instruct (verified) |
| Qwen3-VL-2B | GenieX llama.cpp q4_0 | 8 Elite Gen 5 | 512 | ~44-50 | TTFT 0.04-1.2 s | https://huggingface.co/qualcomm/Qwen3-VL-2B-Instruct (verified) |
| Llama-3.2-1B | Genie QAIRT w4 / w4a16 (NPU) | 8 Elite Gen 5 | 4096 | 36.3 / 65.2 | TTFT 0.03-2.0 s | https://huggingface.co/qualcomm/Llama-v3.2-1B-Instruct (verified) |
| Llama-3-8B-Instruct | Genie QAIRT w4a16 (NPU) | 8 Elite Gen 5 | 4096 | ~16.4 | TTFT 0.10-3.18 s | https://aihub.qualcomm.com/models/llama_v3_8b_instruct (search snippet; unverified for exact value) |
| Falcon3-7B | Genie w4a16 | 8 Elite Gen 5 | 4096 | ~15.8 | - | https://huggingface.co/qualcomm/Falcon3-7B-Instruct (snippet; unverified) |
| Qwen3-4B | llama.cpp Hexagon (HTP) Q4_0 vs Qualcomm engine | 8 Elite (gen 4, not 5) | - | 12 vs 26 | not reported | https://github.com/ggml-org/llama.cpp/discussions/21702 (verified) |
| Llama-3.2-1B | llama.cpp Hexagon, llama-bench Q4_0 | unnamed v79 device | - | tg64 51.5 | pp128 169 tok/s | https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md (verified, example log, not controlled) |
| Gemma-2-2b | llama.cpp Hexagon Q4_0 | unnamed | 1024 pf | 47.6 | prompt eval 3,200 tok/s | same (verified, example log) |
| GPT-OSS-20B Q4_0 | llama.cpp Hexagon, 4 virtual sessions | unnamed | - | 18.4 | prompt eval 51 tok/s | same (verified, example log) |
| FastVLM-0.5B | LiteRT NPU | 8 Elite Gen 5 | - | >100 | prefill >11,000 tok/s, TTFT 0.12 s (1024px image) | https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/ (verified) |
| Gemma 4 E2B | LiteRT-LM NPU | Dragonwing IQ-8275 (not a phone) | 4096 | 31.7 | prefill 3,747 tok/s, TTFT 0.3 s | https://huggingface.co/huggingworld/gemma-4-E2B-it-litert-lm (community card, unverified methodology) |

Notes on the Qualcomm cards: each context length has three unlabeled rows (I could not determine what distinguishes them - likely different thread/backend configs, (unverified)). TTFT is a range: lower bound = <=128-token prompt, upper bound = prompt filling the whole context. Implied prefill on the best row: E2B 4096 tok / 2.41 s ~ 1,700 tok/s; E4B 4096 / 4.46 s ~ 920 tok/s (inferred). On the worst row prefill collapses to ~60-100 tok/s (CPU-like). Decode quoted is "after first token".

No verified llama.cpp-on-Gen-5 Hexagon number exists publicly. The only Qualcomm-published llama.cpp-class number is via GenieX (above).

### 1.2 Candidates by size class (newest first, Oct 2026)

Existence/dates below are confirmed from the Hugging Face API (https://huggingface.co/api/models?author=...) unless noted.

#### ~1-2B
| Model | Params / ctx | License | Tool calling / notes | Best quant | Phone speed |
|---|---|---|---|---|---|
| LFM2.5-1.2B-Instruct (+ DSpark draft, Aug 2026) - https://huggingface.co/LiquidAI | 1.2B | LFM 1.0 (free below a revenue cap; (unverified) threshold) | Pythonic/JSON function calling documented on LFM2.5 family | Q4_0 / Q4_K_M GGUF | no phone number found (unverified) |
| MiniCPM5-2B (Sep 2026) - https://huggingface.co/openbmb/MiniCPM5-2B | 2.52B dense, 131K ctx (aggregator; unverified) | Apache-2.0 (aggregator; unverified) | claimed agentic tool-calling (unverified); GGUF + DSpark draft exist | Q4 GGUF ~2.4 GB | none found |
| Qwen3.5-2B (Feb 2026) - https://huggingface.co/Qwen/Qwen3.5-2B | ~2B dense hybrid Gated-DeltaNet, 262K ctx | Apache-2.0 | thinking on by default (disable with enable_thinking=false); qwen3_coder tool parser | Q4_0 (Qualcomm GGUF) | decode 30-40 tok/s @512 ctx, 24-31 @4096 (verified, GenieX) |
| Qwen3.5-0.8B | 0.8B | Apache-2.0 | one directory warns against agentic use (unverified) | Q8_0/Q4 | Qualcomm card exists; value not fetched |
| Llama-3.2-1B | 1.2B | Llama community | weak tools | w4a16 (QAIRT) | 65 tok/s NPU (verified) |
| SmolLM2-1.7B | 1.7B | Apache-2.0 | basic | - | Qualcomm card exists (Sep 23) |

Top 3 for roles: Qwen3.5-2B (best verified speed+quality), LFM2.5-1.2B (fast CPU, tool-calling oriented), MiniCPM5-2B (newest; unverified on-phone).

#### ~3-4B
| Model | Params / ctx | License | Tool calling | Best quant | Phone speed |
|---|---|---|---|---|---|
| Gemma 4 E2B (Apr 2026; QAT mobile checkpoints Jun 2026) - https://huggingface.co/google/gemma-4-E2B-it-qat-mobile-ct | 2.3B effective (5.1B with embeddings), 128K | Apache-2.0 | native function calling; audio + image input | QAT mobile / q4_0 | 22-34 tok/s GenieX; NPU 31.7 on IQ-8275 (see 1.1) |
| Gemma 4 E4B - https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm | 4.5B effective (8B with embeddings), 128K (page says up to 32K usable in LiteRT bundle) | Apache-2.0 | native function calling | LiteRT-LM int4 (3.65 GB) or q4_0 GGUF | 22 tok/s GPU, 1,293 prefill; 17 CPU; 36-49 with MTP (verified) |
| Qwen3.5-4B - https://huggingface.co/Qwen/Qwen3.5-4B | ~4B, 262K | Apache-2.0 | BFCL-V4 50.3, IFEval 89.8, TAU2 79.9 (self-reported, verified on card) | Q4_0 / Q4_K_M ~2.5 GB | no Gen 5 number found; Qwen3-4B on 8 Elite: 12 tok/s llama.cpp HTP, 26 Qualcomm engine (verified) |
| Granite 4.2 3B (Aug 25 2026) - https://huggingface.co/ibm-granite/granite-4.2-3b | 3B dense (not a Mamba hybrid per card), 128K | Apache-2.0 | BFCL v4 52.41; native reasoning; 12 languages | GGUF repo ibm-granite/granite-4.2-3b-GGUF | none found (unverified) |
| Phi-4-Mini 3.8B | 3.8B, 128K | MIT (unverified) | decent function calling (unverified) | q4_0 | 21-23 @512, 7-13 @4096 (verified) |
| Ministral-3-3B-Instruct-2512 | 3B | Apache-2.0 (unverified) | - | - | Qualcomm card exists, not fetched |
| SmolLM3-3B, LFM2.5-VL-3B (Aug 2026), Qwen3-VL-4B | - | - | - | - | VL-4B 15-21 tok/s (verified) |

Top 3: Gemma 4 E4B, Qwen3.5-4B, Granite 4.2 3B (Phi-4-Mini is the speed-verified fallback).

#### ~7-9B
| Model | Params / ctx | License | Tool calling | Quant | Phone speed |
|---|---|---|---|---|---|
| Qwen3.5-9B (Feb 2026) - https://huggingface.co/Qwen/Qwen3.5-9B | 9B, 262K | Apache-2.0 | BFCL-V4 66.1, TAU2 79.1 (aggregator; unverified) | Q4_0 ~5.5 GB (estimate) | none found. Bandwidth ceiling ~12 tok/s (inferred); expect 6-9 realistic (estimate) |
| Granite 4.2 8B - https://huggingface.co/ibm-granite/granite-4.2-8b-fp8 (family) | 8B | Apache-2.0 | - | Q4 | none found |
| LFM2.5-8B-A1B (MoE, 8.3B total / 1.5B active, 128K) - https://huggingface.co/LiquidAI/LFM2.5-8B-A1B | MoE | LFM 1.0 | BFCLv3 64.36, IFEval 91.84, Tau2 Telecom 88.07 (verified on card); function calling documented | Q4 ~5 GB | none found; active 1.5B => decode could be 25-40 tok/s (estimate; MoE routing overhead on mobile unproven) |
| Llama-3-8B / Falcon3-7B | 7-8B | Llama / Falcon | weak | w4a16 | ~16 tok/s on NPU (snippets, unverified) |

Top 3: LFM2.5-8B-A1B (fast MoE, needs a phone test), Qwen3.5-9B (strongest tools, slowest), Granite 4.2 8B.

#### 12-30B dense (16 GB phone: only the low end fits)
| Model | Size | Notes |
|---|---|---|
| Gemma 4 12B (Jun 2026, "Unified", encoder-free, text+image+audio) - https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf | 11.95B, Q4_0 QAT GGUF = 6.98 GB (verified), 256K ctx, Apache-2.0 | self-reported MMLU-Pro 77.2, Tau2 69.0, native tool use (verified). No phone number found. Decode ceiling ~11 tok/s (inferred), expect 5-8 (estimate). Realistic as the single "big brain" on one phone, 7 GB weights + KV. |
| Qwen3.8-27B (Aug 2026, Apache-2.0 per aggregator) | ~19 GB at 4-bit | does not fit 16 GB. |
| Granite 4.2 30B, Gemma 4 31B | >16 GB | do not fit; only possible split across 2 phones via RPC (slow). |
| Mistral Small 24B-class | ~14 GB Q4 | does not fit with OS overhead (estimate). |

#### MoE with small active params
| Model | Total / active | Fits 16 GB? | Notes |
|---|---|---|---|
| LFM2.5-8B-A1B | 8.3B / 1.5B | yes (~5 GB) | see above. Best fit for phone MoE. |
| gpt-oss-20b (Apache-2.0) | 21B / 3.6B (my prior knowledge; unverified this session), MXFP4 ~13 GB | borderline | Android Authority: Ollama-in-Debian on 24 GB ROG Phone 9 Pro (8 Elite) gave ~4-5 tok/s CPU and needed 24 GB (https://www.androidauthority.com/i-installed-gpt-oss-on-my-phone-3584439/ ; snippet, unverified). llama.cpp Hexagon doc log shows 18.4 tok/s decode on an unnamed device with 4 virtual sessions. Not recommended on 16 GB. |
| Gemma 4 26B-A4B | 25.2B / 3.8B | no (~15 GB Q4) | fits only if split over two phones. |
| Qwen3.5-35B-A3B | 35B / 3B | no (~20 GB) | same. |
| Qwen3.8-Flash-Next etc. | 125B+ | no | |

Not found as small models in Oct 2026: Llama 4 Scout (109B, no), Phi-5 (none found), DeepSeek-distill small (superseded by Qwen3.5/3.8), Qwen4 (only a preview-architecture 125B Flash, per aggregator).

Other relevant new item: Liquid "d1-3B" (Oct 5 2026) is a decision model on LFM2.5-VL-3B returning typed answers in one forward pass, no text output, 32K ctx, LFM 1.0 (verified: https://huggingface.co/LiquidAI/d1-3B). Interesting as a cheap verifier/router, but unproven on Android.

### 1.3 Structured output / tool calling guidance
- Gemma 4 and Qwen3.5 both claim native tool calling; LiteRT-LM advertises function calling (https://github.com/google-ai-edge/LiteRT-LM (verified)). llama.cpp supports GBNF/JSON-schema grammars and `--jinja` tool templates (my prior knowledge, (unverified) for Oct 2026 specifics). For a hackathon, use grammar/JSON-schema constrained decoding in llama.cpp for deterministic structured output regardless of model quality.
- Qwen3.5 thinking mode is on by default: costs many tokens at 20-30 tok/s; set `enable_thinking=false` for worker/verifier calls.

---------------------------------------------------------------------------

## 2. Runtimes on Android

| Runtime | Maturity on SD 8 Elite Gen 5 | NPU | Kotlin/JNI path | Distributed | Build complexity |
|---|---|---|---|---|---|
| **LiteRT-LM** (Google, Apache-2.0) - https://github.com/google-ai-edge/LiteRT-LM | High for CPU/GPU. v0.18.0. Gemma 4 + Qwen/Llama/Phi bundles (.litertlm). Speculative decoding/MTP up to ~2x decode (S26 Ultra GPU 21.9 -> 36.7-49.4). | Qualcomm NPU for LLMs exists but fragile: open issues of garbage output on SM8550 bundles and lost prefill chunks for Gemma 3 on Qualcomm NPU (Gemma 4 reported fine) https://github.com/google-ai-edge/LiteRT-LM/issues/3508 (verified via search). NPU needs AOT compile per SoC; SM8850 is a documented target. | Kotlin API marked Stable (Android/JVM). Also function calling. Easiest path. | None built in (you build your own) | Low (Gradle dependency + download .litertlm) |
| **GenieX** (Qualcomm, BSD-3, developer preview, 8.4k stars) - https://github.com/qualcomm/geniex , docs https://geniex.aihub.qualcomm.com | Fresh (documented targets: Snapdragon 8 Elite and 8 Elite Gen 5 Android). Backends: `llama_cpp` (any GGUF; CPU/Adreno OpenCL/Hexagon NPU, Q4_0 recommended for NPU) and `qairt` (pre-compiled AI Hub bins, NPU only, fastest). | Yes, both | Kotlin SDK on Maven Central (sample pins 0.4.0): `LlmWrapper`, `VlmWrapper`, `generateStreamFlow`, chat templates, image inputs; also OpenAI-compatible server (CLI). No documented tool-calling/embedding API (not found; unverified). | None | Low-medium; needs Qualcomm AI Hub model pulls with chipset "SM8850"; HTP-library signing for non-Qualcomm-branded phones not documented in what I read (RISK). |
| **llama.cpp** (CPU) - https://github.com/ggml-org/llama.cpp (b11533 on 2026-10-09) | Very mature; i8mm/dotprod CPU kernels. Gemma 4/Qwen3.5/Granite/LFM GGUF supported (inferred from Qualcomm shipping GGUFs). CPU Gemma4-E4B: 17 tok/s, 195 prefill (LiteRT, comparable). | - | `examples/llama.android` Gradle module + JNI; community Kotlin binding https://github.com/ljcamargo/kotlinllamacpp (unverified quality) | **RPC backend**: `ggml-rpc-server` + `--rpc host:port`; README calls it "proof-of-concept, fragile and insecure"; weights+KV split in proportion to memory; local tensor cache (verified) | Low (NDK cmake) |
| llama.cpp **OpenCL (Adreno)** | Adreno 830 verified; 840 not listed in docs I read (unverified). Q4_0 is the optimized quant (`--pure` quantize); Q6_K/Q8_0/MXFP4 supported. Qwen3.5 hybrid-attention GPU support questionable (MNN Chat reported OpenCL rejected for Qwen3.5 on a different SoC). No Gen 5 benchmark found. | - | same as above (-DGGML_OPENCL=ON) | works with RPC? untested | Medium |
| llama.cpp **Hexagon/HTP** | "Experimental"; builds v73/v75/v79/v81 libs (v81 = Gen 5's NPU, per docs); Q4_0 weights; official Docker build `scripts/snapdragon/build.py`. Gap vs Qualcomm engine: 12 vs 26 tok/s on Qwen3-4B (8 Elite). Issue #18139: can't reach QNN performance. | Yes (experimental) | adb-shell binary; apps via GenieX wrapper | RPC untested | High (Docker toolchain, HTP signing/unsigned PD) |
| **MLC-LLM** - https://github.com/mlc-ai/mlc-llm | Alive but slower-moving (last push ~Apr 2026 per aggregator, unverified); OpenCL Adreno GPU path; claims of Hexagon use are from a blog (unverified). | No (verified only OpenCL) | Official Android app + Kotlin/Java SDK | "multi-device" is tensor-parallel on GPUs in one host, not phones over Wi-Fi (my knowledge; unverified) | High (TVM compile per model; Qwen3.5 hybrid-attention support uncertain) |
| **ExecuTorch + QNN** - https://docs.pytorch.org/executorch | Works (Llama 3 8B path in docs, `--qnn --pt2e_quantize qnn_16a4w`), but docs pin v73/v75 skel libs; no Gen 5 benchmark published | Yes (QNN) | Java/Kotlin AAR + `llama_main` runner | None | Very high (export + calibration, QNN SDK, per-SoC AOT) |
| **MediaPipe LLM Inference** | In maintenance only, Google recommends migrating to LiteRT-LM | GPU/CPU | Kotlin | - | do not start new work here (verified via Google guide snippet) |
| **MNN / MNN-LLM** (Alibaba) - https://github.com/alibaba/MNN | Strong CPU/OpenCL: paper reports up to 8.6x CPU prefill vs llama.cpp (Snapdragon 8 Gen 3, Qwen2/Llama3, https://arxiv.org/pdf/2506.10443). MNN Chat on a low-end SD 7s Gen 3: Qwen3.5-0.8B 162 prefill/21 decode, 2B 57/6.2 (reddit snapshot; unverified). No Gen 5 numbers found. | partial (QNN path; unverified) | C++ + Java demo app | None | Medium |
| **PowerServe** | No current status found (searched twice) (unverified). Skip. | Hexagon (historic) | - | - | - |
| **Qualcomm AI Hub / Genie SDK** (precursor to GenieX) | Prebuilt, benchmarked bundles for Gen 5 (table 1.1). Fastest NPU decode for supported models (Llama 3.2 1B 65 tok/s). Model list: Gemma-4-E2B/E4B, Qwen3-0.6/1.7/4B/8B, Qwen3.5-0.8B/2B, Qwen3-VL-2/4/8B, Phi-4-Mini, Granite-4.0-Micro, Ministral-3-3B, SmolLM2 (verified, HF qualcomm org). | Yes | via GenieX Kotlin SDK | None | Medium |
| **llamafile** | Android status contradictory; avoid (unverified). | - | - | - | - |
| **Ollama on Android** | Only Termux/Debian-chroot, CPU only, no NPU/GPU; not an app-embeddable runtime. Good for debugging only. | No | HTTP | none | Low but off-path |

Distributed / multi-phone facts:
- llama.cpp RPC is the only mainstream "multi-device over network" mechanism; it is unauthenticated plain TCP (keep it on a private hotspot), splits layers proportionally to memory, and every token crosses the network. On Wi-Fi it makes bigger models possible, it will not make them faster (expectation; no phone-cluster benchmark found, (unverified)).
- Nothing else in the table supports phone-to-phone tensor/pipeline parallelism out of the box.
- Therefore the practical design is task-level distribution: each phone runs its own full small model and exposes a local HTTP/gRPC endpoint; the coordinator routes jobs. This avoids Wi-Fi latency on the token path entirely.

Benchmark sources recap: Qualcomm HF cards (table 1.1), LiteRT-LM HF card, llama.cpp discussion #21702, llama.cpp snapdragon README, MNN-LLM paper.

---------------------------------------------------------------------------

## 3. Embeddings and rerankers (on-device RAG)

| Model | Params | Dim / MRL | Ctx | Langs | Quality | License | Android runtime |
|---|---|---|---|---|---|---|---|
| **EmbeddingGemma 2** (Sep 14 2026) - https://huggingface.co/google/embeddinggemma-2 | 740M total incl. vision+audio encoders (text backbone ~270M) | 768, MRL 512/256/128 | 8,192 | 100+ (trained on 140+) | MTEB multilingual v2 mean 61.36, English v2 68.46, code 78.68 (verified) | Apache-2.0 | LiteRT-LM lists EmbeddingGemma 2 (verified on repo page); card gives no ONNX/LiteRT details |
| **Qwen3-Embedding-0.6B** - https://huggingface.co/Qwen/Qwen3-Embedding-0.6B | 0.6B | up to 1024, MRL 32-1024 | 32K | 100+ | MTEB multilingual 64.33 (verified) | Apache-2.0 | llama.cpp embeddings (`--embedding`, GGUF; widely available (unverified for this exact repo)); ONNX export possible |
| **Qwen3-Reranker-0.6B** | 0.6B | - | 32K | 100+ | instruction-aware cross-encoder (verified via embedding card) | Apache-2.0 | llama.cpp (rerank endpoint) or ONNX Runtime (unverified) |
| bge-m3 / multilingual-e5-small | 568M / 118M | 1024 / 384 | - | multi | - | MIT | ONNX Runtime Mobile (my knowledge; unverified) |
| Qualcomm MiniLM-v2 (HF qualcomm) | 22M | 384 | short | English | weak | - | QNN (verified existence) |

Recommendation: EmbeddingGemma 2 at 256 dims (MRL) via LiteRT-LM if its Kotlin embedding API is usable in the first hour; otherwise Qwen3-Embedding-0.6B GGUF through the same llama.cpp runtime used for LLMs (zero extra runtime). Reranker: Qwen3-Reranker-0.6B only if recall@k is poor; skip otherwise.

---------------------------------------------------------------------------

## 4. Vision and speech

VLM (one pick): **Qwen3-VL-2B-Instruct** q4_0 GGUF via GenieX llama.cpp: ~44-50 tok/s decode at 512 ctx, TTFT 0.04-1.2 s on Gen 5 (verified: https://huggingface.co/qualcomm/Qwen3-VL-2B-Instruct), Apache-2.0. Alternative: Gemma 4 E2B/E4B already multimodal (image+audio), so if you standardize on Gemma 4 you get VLM + native audio understanding with one model in memory (saves RAM; recommended for hackathon simplicity). Tiny fallback: FastVLM-0.5B on LiteRT NPU (>100 tok/s, prefill >11k tok/s; verified).

STT (one pick): two realistic options.
- **Whisper-Large-V3-Turbo-Quantized** (Qualcomm, w8a16, NPU): encoder ~121-125 ms and decoder ~3.3 ms/token on Gen 5 (verified: https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized), so ~30 s chunk transcribes in well under a second plus tokens. Apache-2.0. Needs QNN/AI Hub integration (medium effort). Not streaming.
- **sherpa-onnx** (Android APKs/JNI, CPU) with Moonshine v2, Parakeet-TDT-0.6B-v2 int8 (English only) or Whisper: easiest integration, supports streaming and VAD (https://github.com/k2-fsa/sherpa-onnx ; verified via search snippets; no Snapdragon benchmarks found).
- Qwen3-ASR-0.6B (Jun 2026, 30 languages + 22 Chinese dialects, streaming, Apache-2.0, LS-clean WER 1.69) is attractive but I found no Android runtime for it (https://huggingface.co/Qwen/Qwen3-ASR-0.6B-hf).
Pick: sherpa-onnx + Moonshine/Whisper-small for day-one; upgrade to Qualcomm Whisper turbo NPU only if time.

---------------------------------------------------------------------------

## 5. Recommended stack

Constraint: correctness and integration risk dominate (hackathon, ~days), memory is 16 GB per phone and decode is bandwidth-bound at ~15-35 tok/s, so the pipeline must favor small models, short outputs, and task-level (not tensor-level) distribution.
Failure modes: (a) NPU path produces garbage or fails to load on an iQOO -> every role has a CPU/GPU fallback in the same app; (b) thermal throttling halves speed after ~10 min -> cap generation length, keep per-call prompts short; (c) Wi-Fi drops -> workers are stateless and the coordinator retries or runs locally.
Ledger: use prebuilt SDKs rather than building runtimes (build vs buy: buy); no custom kernels.

| Role | Model | Runtime | Why |
|---|---|---|---|
| Coordinator (plan, route, tool calls, final answer) | **Gemma 4 E4B** (Apache-2.0, native function calling, 128K) | **LiteRT-LM** Kotlin, GPU backend, MTP/speculative decoding on | Only option with a stable Kotlin API, verified S26-Ultra (same SoC) numbers: 1,293 tok/s prefill, 22 decode, 36-49 with speculation; 710 MB GPU memory |
| Worker (parallel sub-tasks, extraction, summaries) on phones 2 and 3 | **Gemma 4 E2B** (or Qwen3.5-2B, non-thinking) | LiteRT-LM GPU; fallback GenieX llama.cpp q4_0 (31-34 / 30-40 tok/s) | Fast, cheap, same runtime so one integration |
| Verifier (checks worker output, JSON/schema validity, groundedness) | **Qwen3.5-4B** non-thinking (different family than the generator to avoid correlated errors) or Granite 4.2 3B | GenieX `llama_cpp` runtime (GGUF Q4_0) or llama.cpp CPU/OpenCL; hard-validate JSON with a grammar/schema in code, not only by LLM | Different vendor reduces shared blind spots; BFCL-V4 50.3 / IFEval 89.8 (self-reported) |
| Embedder | **EmbeddingGemma 2** 256-d (LiteRT-LM) fallback **Qwen3-Embedding-0.6B** GGUF | as noted | Newest, multilingual, small, Apache-2.0 |
| (Optional) VLM / STT | Gemma 4 E2B image+audio, or Qwen3-VL-2B; sherpa-onnx Moonshine / Whisper | - | see section 4 |

Phone cooperation: a small Kotlin service (Ktor/gRPC) on each phone exposing `/generate`, `/embed`, `/verify`; discovery via NSD/mDNS; coordinator chooses the least-loaded worker. Use llama.cpp RPC only as a stretch demo to run Gemma 4 12B (6.98 GB) split across two phones; keep it on a private hotspot because RPC is unauthenticated.

Fallback ladder:
1. LiteRT-LM NPU/GPU fails -> LiteRT-LM CPU (17 tok/s E4B) -> GenieX llama.cpp q4_0.
2. LiteRT-LM unusable -> everything on GenieX (`qairt` for Qwen3-4B/Gemma 4 prebuilt NPU bundles where available; `llama_cpp` for the rest).
3. GenieX not working on iQOO (signing) -> plain llama.cpp `llama.android` CPU (Q4_0/Q4_K_M), Qwen3.5-4B ~ 10-15 tok/s (estimate).
4. Want more reasoning quality on one node: Gemma 4 12B QAT Q4_0 (7 GB) on a single phone, accept ~5-8 tok/s (estimate).

### Five risks
1. Qualcomm numbers are from Galaxy reference devices; an iQOO may behave differently (thermals, vendor memory manager killing background apps, Hexagon library signing/permissions on non-Samsung firmware). No iQOO LLM benchmark exists. Mitigation: run a 10-minute sustained benchmark on day one.
2. Thermal throttling: reviews show the iQOO 15 keeps ~52% of peak under a sustained CPU stress test (91mobiles) and begins throttling ~10 minutes in (Techlusive) - reported in https://www.91mobiles.com/reviews/iqoo-15-review/ (unverified for LLM load). Plan for 50-70% of the quoted decode speeds during demos.
3. NPU LLM paths are immature: llama.cpp Hexagon is "experimental" and 2x slower than Qualcomm's engine on Qwen3-4B; LiteRT-LM Qualcomm NPU has open bugs (garbage output / dropped prefill chunks); GenieX is "developer preview". Keep GPU/CPU fallbacks and test each model-runtime pair before the demo.
4. RAM: 16 GB total, Android keeps a large share (usable for apps likely ~8-10 GB, (unverified)). Gemma 4 E4B in LiteRT used 3.3 GB (CPU) or 0.7 GB (GPU mapping) per the card; 12B/MoE 20B models are borderline. Do not load coordinator+embedder+VLM+STT at once without measuring; Qwen3.5 hybrid architectures and long contexts increase KV/state memory.
5. Distribution over Wi-Fi: llama.cpp RPC is insecure/proof-of-concept and latency-bound per token; tensor-splitting phones will be slower than single-phone small models. Also hackathon Wi-Fi is often congested or client-isolated; use a dedicated phone hotspot or Wi-Fi Direct. Related: Qwen3.5 thinking mode burns tokens at ~25 tok/s, so disable it for worker/verifier; and model/license risk: LFM models use the LFM 1.0 license (check revenue cap), Qwen3.8 licensing varies by model (aggregator says some "Qwen Community").

### Open questions to check in the first hours
- Does GenieX `llama_cpp`/`qairt` initialize on the iQOO (chipset "SM8850")?
- Does LiteRT-LM GPU for Gemma 4 E4B match ~22 tok/s on the iQOO, and is MTP drafter download supported on-device?
- Is a Qwen3.5-4B GGUF fast on Adreno OpenCL, or only CPU (hybrid DeltaNet kernels)?
- Real throughput of LFM2.5-8B-A1B on-device (promising, unmeasured).
