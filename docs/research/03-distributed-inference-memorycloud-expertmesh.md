# Distributed LLM inference across 2-3 Android phones: MemoryCloud, ExpertMesh, SwarmOS

Date: 2026-10-09. Target: 2-3x Snapdragon 8 Elite Gen 5 phones (16 GB, Adreno 840, Hexagon NPU), local Wi-Fi 100-800 Mbps, 2-10 ms RTT.
Convention: "(unverified)" = not confirmed by a primary source this session. "(assumption)" = my modelling input. All derived numbers come from the formulas in section 2 and are reproducible by hand.

## 0. Verdicts

| Module | Verdict | One-line reason |
|---|---|---|
| SwarmOS pipeline parallel (layer ranges per phone) | **Feasible, build this** | One activation vector (4-10 KB) per hop per token; latency (RTT), not bandwidth, is the cost. Proven on Wi-Fi by prima.cpp. |
| SwarmOS tensor parallel | **Not viable over Wi-Fi** | 2 all-reduces per layer = 48-96 sync points per token; 200-500 ms/token from latency alone. |
| MemoryCloud as live weight/layer paging | **Not viable** | Dense: 120-1200 s/token over Wi-Fi. MoE experts: 18-146 s/token uncached. |
| MemoryCloud as shard distribution + cache (one-time push, then pipeline) | **Feasible, useful** | 13-19 GB model loads in ~3-14 min at 300-800 Mbps effective; cache on flash (Cake and llama.cpp RPC `-c` both do this). |
| MemoryCloud KV sharing / prefix transfer | **Mostly unnecessary** | In pipeline parallel each phone already owns the KV of its own layers. Moving a 4k-token prefix costs 3-100 s; only pays at >=800 Mbps. |
| ExpertMesh, experts spread across phones *within a layer* | **Not viable for decode** | Every MoE layer needs a network round trip: 24-48 RTTs per token -> 4-8 tok/s hard ceiling before compute. |
| ExpertMesh as layer-range grouping (all experts of layers i..j on one phone) | **Feasible (it is pipeline parallel)** | One hop per stage boundary. |
| Speculative decoding across phones (draft on A, verify on B) | **Feasible, best stretch** | One RTT per round amortised over ~3 tokens; prima.cpp measured 1.5x on 70B. |

## 1. Prior art (newest first)

Speeds are as reported by the authors on their own hardware (not comparable across rows).

| System (date) | Parallelism | Hardware tested | Reported speed | Android? | License | Link |
|---|---|---|---|---|---|---|
| **BigMoMo** (arXiv 2609.14643, Sep 2026) | Single-device MoE expert offload from flash + speculative decoding | "two mobile platforms" (unnamed in abstract) | 4.83x vs on-demand offloading, 1.82x vs best spec-MoE baseline; MoE up to 80B; no absolute tok/s seen | mobile research runtime; code availability unverified | unknown | https://arxiv.org/abs/2609.14643 |
| **EStream** (2609.06551, Sep 2026) | MoE *prefill* on mobile NPU, experts streamed from UFS flash through a fixed arena | mobile NPU (details unverified) | (unverified) | mobile | unknown | https://arxiv.org/abs/2609.06551 |
| **SeqMoE** (2609.12978) | predictive MoE offloading | (unverified) | (unverified) | - | - | https://arxiv.org/pdf/2609.12978 |
| **OrderMoE** (2607.17154, Jul 2026) | expert-similarity-driven *distributed edge MoE* | PDF unreadable by my fetcher; details unverified | (unverified) | - | - | https://arxiv.org/pdf/2607.17154 |
| **Lever** (2605.16786, May 2026) | single phone, speculative decoding with flash-resident weights | smartphone | (unverified) | n/a | - | https://arxiv.org/abs/2605.16786 |
| **MobileMoE** (2605.27358) | sub-1B MoE for phones; 1.8-3.8x prefill, 2.2-3.4x decode vs dense MobileLLM-Pro at equal INT4 memory | phones | see abstract | n/a | - | https://arxiv.org/abs/2605.27358 |
| **distributed-llama v0.16** (Sep 2025) | **Tensor parallel**; nodes = 2^n, max = #KV heads; Ethernet | 4x Raspberry Pi 5 8 GB over Gigabit switch: **Qwen3-30B-A3B Q40 = 13.04 tok/s** (a fork claims 15.1, unreproduced) | not mentioned on README | **MIT** | https://github.com/b4rtaz/distributed-llama , https://github.com/b4rtaz/distributed-llama/discussions/255 |
| **prima.cpp** (arXiv 2504.08791 v3, ICLR 2026) | **Pipelined-ring** + mmap/prefetch + Halda scheduler + spec decoding | Mac M1, RTX3070 laptop, 2080Ti desktop, Mate 40 Pro (HarmonyOS), Honor Pad (Android), **Wi-Fi 320-610 Mbps, 3-7 ms** | 70B: 674 ms/tok (~1.5 tok/s), 442 ms with spec decoding; 32B ~26 tok/s with spec dec; llama.cpp 10120 ms, exo and dllama OOM at 70B | **Yes via Termux** (Android + HarmonyOS); low porting effort | MIT badge on a fork (original repo license unverified) | https://arxiv.org/html/2504.08791v3 , https://github.com/tdh-archive/prima.cpp |
| **exo** (exo-explore, main) | Topology-aware auto parallel; tensor parallel (claims 1.8x on 2, 3.2x on 4 devices) and pipeline sharding; MLX distributed; RDMA over Thunderbolt 5 | 4x M3 Ultra Mac Studio (Qwen3-235B, DeepSeek V3.1, Kimi K2) | tok/s only in images | **No** (macOS GPU, Linux CPU only); porting effort large | Apache-2.0 | https://github.com/exo-explore/exo |
| **llama.cpp RPC** (`rpc-server`) | Weights + KV split across local/remote ggml devices over TCP; `-c` caches tensors on the server | any ggml build | proof of concept, "fragile and insecure" | not documented; plain C++/TCP so an NDK build should work (inference, unverified) | MIT (llama.cpp) | https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md |
| **Cake** (evilsocket) | Layer sharding; master streams zstd weights to workers (workers need no model files); mDNS discovery | CUDA/Metal/Vulkan/CPU | no benchmarks on page | **Claimed yes** (iOS/Android listed; details unverified) | FAIR License: non-commercial free, business use needs signed agreement | https://github.com/evilsocket/cake |
| **Jupiter** (2504.08242), **CoFormer** (2508.20375) | edge collaborative generative-LLM inference | edge boards | (unverified) | - | - | https://arxiv.org/pdf/2504.08242 |
| **MDI-LLM** (2505.18164) | recurrent pipeline parallel across low-power edge nodes | edge nodes | (unverified) | - | - | https://arxiv.org/pdf/2505.18164 |
| **HeteroLLM / HeteroInfer** (2501.14794) | *Single-phone* GPU+NPU tensor partition; Snapdragon 8 Gen 3 (GPU ~1 TFLOPS, NPU ~10 TFLOPS) | 1 phone | up to 9.99x prefill vs MLC (secondary source) | yes (phone) | - | https://arxiv.org/html/2501.14794v1 |
| **TPI-LLM** (2410.00531) | **Tensor parallel** with star all-reduce; found link latency, not bandwidth, is the issue; sliding-window weight scheduler (3.1 GB peak for 70B) | emulated + real edge testbed | >80% lower TTFT/latency than Accelerate | no | - | https://arxiv.org/html/2410.00531v1 |
| **Petals** | Layer pipeline over an internet swarm | GPUs | up to ~6 steps/s Llama-2-70B (units inconsistent across mirrors) | no | not confirmed (unverified) | https://github.com/bigscience-workshop/petals |
| **Galaxy** (2405.17245) | Hybrid tensor + sequence parallel, tile-level comm/compute overlap | Jetson-class edge | up to 2.5x lower latency | no | - | https://arxiv.org/abs/2405.17245 |
| **LinguaLinked** (ACL 2024 demo, 2312.00388) | Model segmentation across *Android phones* with a runtime load balancer | real phones, small models | 1.11-1.61x (1 thread), 1.73-2.65x (multi-thread) vs its baseline | **Yes (phones)**; code availability unverified | - | https://arxiv.org/abs/2312.00388 |
| **WDMoE** | Gate + attention at base station, experts on mobile devices over wireless | simulation | no latency numbers seen | - | - | survey https://arxiv.org/pdf/2504.19660 |
| **EdgeMoE** (2308.14352) | Single device: non-expert weights in RAM, experts fetched from storage, mixed precision | edge boards, 1.6-3.5 GB budgets | ~2.77x (baseline unspecified) | - | - | https://arxiv.org/abs/2308.14352 |
| **Fiddler** (ICLR'25) | CPU-GPU orchestration: ship *activations* to CPU-resident experts, not weights | 24 GB GPU + CPU | Mixtral-8x7B >3 tok/s | no | - | https://github.com/efeslab/fiddler |
| **MoE-Lightning, MoE-Infinity, Hermes, Cornserve** | GPU-server MoE offload / serving. The "Hermes" hit was unrelated (telecom). No mobile or Wi-Fi results found; HeteroLLM-style numbers for Cornserve not found. | datacenter | - | no | - | MoE-Lightning https://arxiv.org/pdf/2411.11217 (others unverified) |

Takeaways:
1. The only prior art verified to have run on Android over Wi-Fi at 3-7 ms is **prima.cpp** (pipelined ring, Termux).
2. Every system built for slow links chose pipeline/ring; TPI-LLM itself says latency dominates.
3. Mobile MoE work (BigMoMo, EStream, EdgeMoE) is about *flash* offload on one device, not cross-phone experts.
4. I found **no published 2026 paper demonstrating multi-phone expert-parallel decoding over Wi-Fi** (OrderMoE is the nearest, unreadable to me). ExpertMesh would be novel, and section 2.4 shows why it is hard.

## 2. Bandwidth and latency math

### 2.1 Model parameters (hidden/layers/heads verified from HF config.json this session)

| Model | hidden H | layers L | KV heads | head_dim | Experts (total / top-k) | Params total / active (model cards, approx, unverified) |
|---|---|---|---|---|---|---|
| Qwen3-8B | 4096 | 36 | 8 | 128 | dense | 8.2 B |
| Qwen3-14B | 5120 | 40 | 8 | 128 | dense | 14.8 B |
| Gemma-3-12B | 3840 | 48 | 8 | 256 | dense; 5:1 local (1024 window) : global | 12.2 B incl. vision |
| GPT-OSS-20B | 2880 | 24 | 8 | 64 | 32 / 4 | 21 B / 3.6 B; alternating 128-window and full attention |
| Qwen3-30B-A3B | 2048 | 48 | 4 | 128 | 128 / 8 (moe_inter 768) | 30.5 B / 3.3 B |
| Llama-4-Scout | 5120 | 48 | 8 | 128 | 16 / 1 (+shared) | 109 B / 17 B; 8192-chunked attention in 3 of 4 layers |

### 2.2 Pipeline parallel

Formulas, p phones in a ring, activations fp16 (2 B):
- bytes per hop per decoded token: `B = H x 2` (int8 activations halve it; quality impact unverified)
- per-hop time: `t_hop = RTT/2 + 8B / BW`
- network time per token: `t_net = p x t_hop` (p-1 forward hops + 1 return of the sampled token to stage 0)
- compute per token (assumption): bandwidth-bound decode, `t_cmp = active_weight_bytes / 40 GB/s`. 40 GB/s is my assumed *effective* LPDDR5X rate for the 8 Elite Gen 5; I found no measured Gen 5 llama.cpp numbers and did not verify the spec peak.
- Pipeline adds no compute parallelism to a single stream (stages run in sequence): `t_token = t_net + t_cmp_total`. It buys *capacity*, not speed.
- Q4 sizes (approx, unverified): Qwen3-8B 5.0 GB, 14B 9.0, Gemma-3-12B 7.3, GPT-OSS-20B 12.8 (MXFP4), Qwen3-30B-A3B 18.6, Scout ~62.

RTT 5 ms. "net-only" = 1/t_net; "total" = 1/(t_net + t_cmp). Columns are 100 / 300 / 800 Mbps.

| Model | B (fp16) | t_cmp | phones | net-only tok/s | total tok/s |
|---|---|---|---|---|---|
| Qwen3-8B | 8,192 B | 125 ms | 2 | 158 / 184 / 194 | 7.6 / 7.7 / 7.7 |
| | | | 3 | 106 / 123 / 129 | 7.4 / 7.5 / 7.5 |
| Qwen3-14B | 10,240 B | 225 ms | 2 | 151 / 180 / 192 | 4.3 / 4.3 / 4.3 |
| | | | 3 | 100 / 120 / 128 | 4.3 / 4.3 / 4.3 |
| Gemma-3-12B | 7,680 B | 182 ms | 2 | 161 / 185 / 194 | 5.3 / 5.3 / 5.3 |
| | | | 3 | 107 / 123 / 129 | 5.2 / 5.2 / 5.3 |
| GPT-OSS-20B | 5,760 B | 55 ms | 2 | 169 / 188 / 195 | 16.5 / 16.6 / 16.7 |
| | | | 3 | 113 / 126 / 130 | 15.7 / 15.9 / 16.0 |
| Qwen3-30B-A3B | 4,096 B | 50 ms | 2 | 177 / 192 / 197 | 17.9 / 18.0 / 18.1 |
| | | | 3 | 118 / 128 / 131 | 17.0 / 17.2 / 17.3 |
| Llama-4-Scout | 10,240 B | 242 ms | 2 / 3 | 100-192 | ~4.0 (does not fit, see section 4) |

Reading:
- At decode the activation is 4-10 KB, i.e. 0.3-0.8 ms of serialization even at 100 Mbps. **Bandwidth barely matters; RTT/2 per hop (2.5 ms) dominates the network term**, and compute dominates the total.
- Real Wi-Fi adds jitter (tail latency of tens of ms is common, unverified), so expect measured results around 50-70% of "total" (assumption).
- prima.cpp measured on real Wi-Fi gets 674 ms/token for 70B, where disk/mmap and slow devices dominate: consistent with "network is not the bottleneck".
- Prefill is where bandwidth bites: `n x H x 2` bytes per hop. 1000 tokens: Qwen3-14B 10.2 MB (1.4 / 0.46 / 0.17 s at 100/300/800 Mbps, 60% link efficiency); Qwen3-30B-A3B 4.1 MB (0.55 / 0.18 / 0.07 s). Chunked, overlapped prefill hides most of it.

### 2.3 Tensor parallel: why it fails

Per layer: 2 all-reduces (after attention, after MLP), each `H x 2` bytes. Time per token `= L x 2 x (t_lat + 8B/BW)`.
- One-way latency 2.5 ms: Qwen3-8B 186-227 ms (4.4-5.4 tok/s); Qwen3-14B 208-266 ms (3.8-4.8 tok/s); Gemma-3-12B 247-299 ms (3.3-4.0 tok/s).
- If each blocking all-reduce costs a full RTT (5 ms): Qwen3-14B 408-466 ms = **2.1-2.4 tok/s**; Gemma-3-12B 1.9-2.1 tok/s.
- Raising bandwidth from 100 to 800 Mbps barely helps; the cap is `1 / (2 L t_lat)` and sits on top of compute. One phone alone does Qwen3-14B at about 4-5 tok/s (assumption), so TP over Wi-Fi would be slower than one phone while burning 2-3 phones, and 72-96 sync points per token multiply jitter exposure.
- This agrees with TPI-LLM (latency is the problem), with exo showing TP gains over Thunderbolt-5 RDMA rather than Wi-Fi, and with distributed-llama's TP numbers coming from Gigabit-switch Pis.

### 2.4 Expert parallel (ExpertMesh)

Per MoE layer the hidden state (`H x 2` B) goes to each remote phone holding a selected expert and the weighted partial sum returns. Traffic per layer per remote phone: `2 x H x 2` B (Qwen3-30B-A3B 8 KB; GPT-OSS-20B 11.5 KB). Tiny. Latency per layer = 1 RTT (5 ms) + 2x serialization.

Probability that all top-k experts sit on one given phone (experts evenly split): `P = C(E/p, k) / C(E, k)`.

| Model | MoE layers | P(all local), 2 phones | 3 phones | added latency per token (L x RTT + tx) @100/300/800 Mbps | ceiling tok/s |
|---|---|---|---|---|---|
| Qwen3-30B-A3B (top-8 of 128) | 48 | 0.31% | 0.01% | 271 / 250 / 244 ms | **3.7 / 4.0 / 4.1** |
| GPT-OSS-20B (top-4 of 32) | 24 | 5.1% | 0.6% | 142 / 127 / 123 ms | **7.0 / 7.9 / 8.1** |

With near-random routing almost every layer needs a remote call, so you pay about 1 RTT per layer: 24-48 round trips per token versus 2-3 hops for layer-range pipeline. Compute (50-55 ms) comes on top, though local experts can overlap with the remote wait (this hides compute, not latency). Speculative expert prefetch cannot hide the RTT, because layer i+1's router input exists only after layer i finishes; any guess accuracy is unverified.

**Conclusion:** remote experts per layer are not viable for single-stream decode over Wi-Fi (3.7-8 tok/s ceiling before jitter, versus ~17 tok/s with layer ranges). They become viable only when many tokens share a round trip: batched serving, or speculative decoding verifying k tokens per round. Prefill cannot use it either: 512 tokens x 4 KB = 2 MB per layer per phone, about 53 ms/layer at 300 Mbps (computed). **Implement ExpertMesh as grouping by layer range per device (= pipeline), with expert-aware placement (e.g. give the memory-richest phone the heaviest layers).**

## 3. KV cache, speculation, MemoryCloud streaming

### 3.1 KV size per token (fp16, `2 x L x KV_heads x head_dim x 2 B`)

| Model | KB/token | MB at 4k context | Notes |
|---|---|---|---|
| Qwen3-8B | 144 | 604 | |
| Qwen3-14B | 160 | 671 | |
| Gemma-3-12B | 384 (all layers full) | 1611 | real use much smaller: 40 of 48 layers use a 1024 window (~320 MiB cap) plus 64 KiB/token for the 8 global layers |
| GPT-OSS-20B | 48 | 201 | half the layers use a 128 window, so real use is ~100 MB at 4k |
| Qwen3-30B-A3B | 96 | 403 | |
| Llama-4-Scout | 192 | 805 | 3 of 4 layers chunked at 8192 |

q8_0 KV halves these (quality impact unverified).

### 3.2 KV / prefix sharing across devices
- In pipeline parallel each phone holds KV only for its layers (14B on 2 phones = ~335 MB each at 4k). **No KV transfer is needed**; that is the correct form of "sharing".
- Moving a cached prefix between phones (shared system prompt, failover): 4k tokens of Qwen3-14B = 671 MB -> 89 / 30 / 11 s at 100 / 300 / 800 Mbps (60% efficiency, assumption). Re-prefill at ~100-300 tok/s (assumption, unmeasured) is 14-40 s. Transfer beats recompute only at >=800 Mbps and for small-KV models (GPT-OSS-20B 201 MB: 3 s at 800 Mbps; Qwen3-30B-A3B 403 MB: 7 s). A prefix cache is a nice-to-have, not a headline.

### 3.3 Speculative decoding across phones
- Round: a draft model (Qwen3-0.6B or 1.7B, ~0.5-1.2 GB Q4, unverified) on phone A proposes k tokens; the target (pipelined across the phones) verifies k+1 tokens in one pass. Payload per hop: k x H x 2 B (k=5 on Qwen3-14B = 51 KB). Decode is bandwidth-bound, so verifying 5-6 tokens costs about 1.15x one token (assumption).
- Expected tokens per round `E = (1 - a^(k+1)) / (1 - a)`; a=0.7, k=5 gives 2.94.
- Qwen3-14B on 2 phones at 300 Mbps: plain 4.3 tok/s; speculative `2.94 / (225 ms x 1.15 + 8.3 ms + 2.5 ms)` = ~11 tok/s (model estimate; acceptance rate assumed). Measured precedent is smaller: prima.cpp 70B 674 -> 442 ms/token (1.5x) and ~26 tok/s on 32B (https://arxiv.org/html/2504.08791v3). Plan on 1.5-2x, not 2.5x.
- This is the only technique that amortises the RTT, and it composes with MoE (BigMoMo uses the verify window to batch expert loads, https://arxiv.org/abs/2609.14643).

### 3.4 MemoryCloud as a streaming shard cache
- Dense layer streaming: every token reads all weights. Qwen3-14B Q4 (9.0 GB) at 60% link efficiency: 1200 / 400 / 150 s per token (100/300/800 Mbps); Gemma-3-12B 973 / 324 / 122 s. Even 1 tok/s would need ~72 Gbps. **Impossible.**
- MoE expert streaming (Qwen3-30B-A3B): expert = 3 x 2048 x 768 = 4.72 M params, ~2.86 MB at Q4; 8 experts x 48 layers = 1.1 GB/token if nothing is cached -> 146 / 49 / 18 s per token. At a 70% cache hit it is still 5-44 s. On-phone UFS flash (~3-4 GB/s sequential, unverified) would take ~0.3-0.4 s for the same bytes, which is why BigMoMo and EStream stream from flash, not Wi-Fi.
- One-time shard distribution at 60% link efficiency: GPT-OSS-20B 12.8 GB = 28 / 9.5 / 3.6 min; Qwen3-30B-A3B 18.6 GB = 41 / 14 / 5.2 min; Qwen3-14B 20 / 6.7 / 2.5 min. Pre-stage over USB/adb or push only each phone's own layer range.
- So MemoryCloud = **shard store + pre-positioning + warm cache + failover re-shard**, not live paging. Anything beyond that would be a false claim.

## 4. Memory fit (16 GB phones)

Practical budget (assumption, must be measured on device): Android, system services and GPU/NPU drivers leave roughly 9-11 GB for a foreground app on a 16 GB phone. Android's per-app limit tracks "allocated anonymous memory" (heap + non-file native buffers), derived from total RAM with an unpublished formula and no developer override (secondary source: https://stora.sh/blog/2026-04-25-android-17-memory-limits-guide). `largeHeap` raises only the Java heap and is irrelevant for llama.cpp-style native weights. Strategy: `mmap` the GGUF (file-backed, evictable, and by that definition not anonymous - my inference, verify on device); keep KV and compute buffers (anonymous) small; budget 1.5-2.5 GB for them plus runtime; run as a foreground service. prima.cpp relies on mmap and keeps memory pressure low, while exo and dllama pin weights and OOM at 70B (https://arxiv.org/html/2504.08791v3). Budget used below: **8.5 GB of weights per phone (conservative), 10 GB (aggressive)**.

| Model | Q4 weights | KV at 4k fp16 | Fits 1 phone (8.5 GB)? | Phones needed | Weights per phone |
|---|---|---|---|---|---|
| Qwen3-8B (Q4_K_M) | ~5.0 GB | 0.6 GB | Yes | 1 | 5.0 |
| Gemma-3-12B | ~7.3 GB (+0.85 mmproj) | ~0.4 GB real | Yes, tight | 1 (2 comfortable) | 3.7 on 2 |
| Qwen3-14B | ~9.0 GB | 0.67 GB | Borderline, no at 8.5 | **2** | 4.5 |
| GPT-OSS-20B (MXFP4) | ~12.8 GB | ~0.1-0.2 GB real | **No** (only with heavy mmap paging) | **2** | 6.4 |
| Qwen3-30B-A3B | ~18.6 GB | 0.4 GB | No | **3 comfortable; 2 only at the 10 GB budget** | 6.2 on 3 / 9.3 on 2 |
| Qwen3-32B dense (Q4_K_M) | ~19.8 GB | 0.5-1 GB | No | 3 | 6.6 |
| Llama-4-Scout 109B | ~62 GB Q4 (~34 GB at 1.78-bit, unverified) | 0.8 GB | No | **Does not fit even 3 phones (3 x 9 = 27 GB)** | - |

Embedding and lm_head (Qwen3-30B-A3B: 151,936 x 2048 each, ~0.19 GB at Q4) land on the first and last stage; give those phones fewer layers.

## 5. Recommended hackathon demo (honest)

**Model: Qwen3-30B-A3B at Q4, 3 phones, pipeline by layer range.**
- Why: (a) 18.6 GB cannot run on one 16 GB phone, so the gain is "a model that otherwise cannot run at all", not a faked speed-up; (b) 3.3 B active parameters means decode reads ~2 GB/token, so speed stays usable; (c) hidden=2048 gives the smallest activation (4 KB/hop); (d) the same model reaching 13 tok/s on 4 Raspberry Pi 5 over Gigabit is an independent plausibility anchor (https://github.com/b4rtaz/distributed-llama/discussions/255).
- Split (48 layers): phone A layers 0-15 + embedding, phone B layers 16-31, phone C layers 32-47 + final norm and lm_head. ~6.2 GB weights each, ~135 MB KV each at 4k. A 2-phone variant is L0-23 / L24-47 at ~9.3 GB each; attempt it only after measuring the real app memory ceiling.
- Expected speed (model estimate, not measured): network ~8.5 ms + compute ~50 ms (40 GB/s assumption) -> ~17 tok/s theoretical. **Plan for 6-10 tok/s measured** on a llama.cpp-class Android runtime (jitter, scheduling, thermals, imperfect kernels). If you see more than ~12 tok/s, re-check the bandwidth assumption.
- **One-phone baseline** to show on stage: the same model cannot be loaded; with pure mmap paging from flash it gets killed or crawls (prima.cpp's llama.cpp baseline drops to 10.1 s/token when the model exceeds RAM, https://arxiv.org/html/2504.08791v3; expect seconds per token, measure it). Fair secondary baseline: **Qwen3-8B Q4 on one phone, ~7-9 tok/s (estimate)**, to show that the swarm delivers a 30B-class model at similar speed. Claim capacity, not a speed-up over a model that already fits.
- Software path (lowest risk): fork **prima.cpp** (Termux, Wi-Fi ring, MIT badge on a fork; verify original) or **llama.cpp + rpc-server** under Termux/NDK (MIT, PoC status). Avoid Cake (FAIR license is not freely commercial) and exo (no Android). llama.cpp RPC is a general graph offload that may send many small messages per token, so measure RTT sensitivity (unverified). The Adreno OpenCL backend exists in llama.cpp, documented through 8 Elite (Gen 5 untested by me: https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu); fall back to CPU if kernels misbehave.
- UI honesty: show per-hop latency, per-phone RAM, and live tokens/s; label estimated numbers as estimates.

**Stretch options (pick one):**
1. Speculative decoding: Qwen3-1.7B or 0.6B draft on phone A + the 3-phone 30B target. Expect 1.5-2x (prima.cpp precedent), not more.
2. Dense Qwen3-32B Q4 on 3 phones (~19.8 GB): ~2 tok/s (0.44 s compute + ~8 ms network); proves scaling, slow. With speculation ~3-4 tok/s (estimate).
3. Heterogeneous phones: weight layer ranges by measured throughput (prima.cpp's Halda scheduler is the prior art).
4. "ExpertMesh" research slide: expert-parallel on GPT-OSS-20B, 2 phones, to measure and display the 7-8 tok/s ceiling next to pipeline's ~16 tok/s. An honest negative result.

## 6. Five risks

1. **Memory ceiling unknown.** The Android per-app anonymous-memory cap is unpublished. If a foreground app gets <8 GB, three phones x 6.2 GB still works but two phones do not. Measure on the real devices first. Mitigation: mmap weights, small KV, 3-phone split.
2. **Wi-Fi jitter and power save.** The math assumes 5 ms RTT; Doze/Wi-Fi power-save, AP contention, or Wi-Fi Direct can give 20-100 ms tails, and each token has 3-4 serial hops. Mitigation: dedicated 5/6 GHz AP, wake lock + high-performance Wi-Fi lock, foreground service, measure p99 RTT, fall back to 2 phones.
3. **Thermal throttling and background kills.** Sustained decode on a phone SoC likely throttles within minutes (unverified for Gen 5); a throttled middle stage stalls the whole pipeline. Mitigation: cooling, short fixed demo runs, per-stage throughput monitoring and layer re-balancing.
4. **Runtime immaturity on Android.** exo has no Android; RPC is flagged "fragile and insecure" (LAN-only trust); prima.cpp's Android support is Termux-based and was tested on a tablet and a Kirin phone, not Gen 5; Adreno OpenCL / Hexagon NPU kernels for MoE and MXFP4 are uncertain. Mitigation: CPU fallback, budget a day for the build, check licenses (Cake FAIR license; prima.cpp original repo).
5. **Overclaiming.** Pipeline gives capacity, not speed; expert-parallel and tensor-parallel are slower than layer ranges over Wi-Fi; MemoryCloud cannot page weights live; all tok/s here are model estimates built on an assumed 40 GB/s effective bandwidth with no measured Gen 5 data. Mitigation: ship measured numbers, label estimates, present MemoryCloud as shard distribution + cache and ExpertMesh as layer-range expert placement.

## 7. Open items to measure before the demo
- Effective decode GB/s of the Gen 5 with the chosen runtime (replaces the 40 GB/s assumption) and the real LPDDR5X spec.
- Per-app usable RAM and mmap behaviour on the actual phones.
- Wi-Fi RTT distribution (p50/p99) with 3 phones active; int8 vs fp16 activations.
- Original prima.cpp repo license text, Petals license, and OrderMoE / BigMoMo / EStream full-text details (PDFs not readable through my fetch tool).

## Sources
exo https://github.com/exo-explore/exo ; prima.cpp paper https://arxiv.org/html/2504.08791v3 and fork https://github.com/tdh-archive/prima.cpp ; distributed-llama https://github.com/b4rtaz/distributed-llama and https://github.com/b4rtaz/distributed-llama/discussions/255 ; llama.cpp RPC https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md ; Cake https://github.com/evilsocket/cake ; TPI-LLM https://arxiv.org/html/2410.00531v1 ; LinguaLinked https://arxiv.org/abs/2312.00388 ; Galaxy https://arxiv.org/abs/2405.17245 ; HeteroLLM https://arxiv.org/html/2501.14794v1 ; EdgeMoE https://arxiv.org/abs/2308.14352 ; Fiddler https://github.com/efeslab/fiddler ; BigMoMo https://arxiv.org/abs/2609.14643 ; EStream https://arxiv.org/abs/2609.06551 ; Android memory https://stora.sh/blog/2026-04-25-android-17-memory-limits-guide ; HF configs https://huggingface.co/Qwen/Qwen3-30B-A3B , https://huggingface.co/openai/gpt-oss-20b , https://huggingface.co/Qwen/Qwen3-14B , https://huggingface.co/unsloth/gemma-3-12b-it , https://huggingface.co/unsloth/Llama-4-Scout-17B-16E-Instruct .
