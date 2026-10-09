# Pinned models (GGUF)

Runtime pin: llama.cpp release tag **v0.6.0** (commit `d81235049384534c167caea52b85a694f6103d14`, 2026-10-05), built by `native/build-llama-android.sh`.
All files live in `H:\UStaaf\models\` (gitignored) and are fetched with `native/fetch-models.sh <hf-repo> <file>` (resumable `curl -C -`, size and sha256 checked against the Hugging Face LFS oid). The internet link is metered: each file was downloaded once to the PC and pushed to the phones over ADB.

SHA256 below is of the file as downloaded, verified equal to the Hugging Face LFS oid and (for the 4B model) re-verified on phone C with `sha256sum`.

| Role | HF repo | File | Bytes | SHA256 | License |
|---|---|---|---:|---|---|
| Chat / verifier, 4B class | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-Q4_K_M.gguf` | 2,740,937,888 | `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4` | Apache-2.0 |
| Second family, coordinator | `google/gemma-4-E4B-it-qat-q4_0-gguf` | `gemma-4-E4B_q4_0-it.gguf` | 5,154,941,280 | `676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee` | Apache-2.0 |
| Embeddings (768-d) | `ggml-org/embeddinggemma-300M-GGUF` | `embeddinggemma-300M-Q8_0.gguf` | 333,590,944 | `b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63` | Gemma Terms of Use (google/embeddinggemma-300m is `license:gemma`) |
| Large MoE for the 3-phone split | `unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF` | `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` | 18,556,686,752 | `6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0` | Apache-2.0 |

## Why these

- **Qwen3.5-4B Q4_K_M** (2.54 GiB): newest 4B Qwen with a GGUF (Feb 2026, 1.1 M downloads); Qwen3-4B is older. Thinking is ON by default; turn it off per request (see `docs/llama-server-api-notes.md`). Hybrid linear-attention architecture: small KV cache, but prompt processing is slow on CPU (see the benchmark note).
- **Gemma 4 E4B QAT Q4_0** (4.8 GiB): official Google quantisation-aware Q4_0 (better than post-training Q4 at the same size, and Q4_0 has the best ARM repack/KleidiAI kernels). Different vendor and tokenizer from Qwen, so it can act as an independent verifier. E4B rather than E2B so it is a like-for-like 4B-class comparison; the E2B QAT file (`gemma-4-E2B_q4_0-it.gguf`, 3,349,516,256 bytes, LFS oid `fa401b55b07e...`) was not downloaded to save metered data.
- **EmbeddingGemma-300M Q8_0**: 768-d, L2-normalised, ~10 ms per short text on phone C. Small enough to keep resident next to a chat model.
- **Qwen3-30B-A3B-Instruct-2507 Q4_K_M**: 17.3 GiB, cannot be loaded by one 16 GB phone; 3.3 B active parameters per token; plain transformer MoE (48 layers, 128 experts), the lowest-risk architecture for llama.cpp's RPC backend; non-thinking Instruct variant (no thinking switch to manage). Matches the memory table in `docs/research/03-distributed-inference-memorycloud-expertmesh.md`.

## Candidates checked and not used

| Candidate | Result |
|---|---|
| `ggml-org/embeddinggemma-2-GGUF` `embeddinggemma-2-Q8_0.gguf` (309,855,456 B, sha256 `2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135`, Apache-2.0, published 2026-10-06) | **Does not load on llama.cpp v0.6.0**: `unknown model architecture: 'gemma-embedding2'` (the arch landed after the 2026-10-05 tag). File kept in `models/`; usable after a llama.cpp bump. Research doc 01 names it as preferred embedder, so re-test on the next llama.cpp upgrade. |
| Qwen3.5-35B-A3B / Qwen3.6-35B-A3B Q4_K_M (22.0 / 22.1 GB) | Newer, but just over the 22 GB limit in the brief and hybrid linear-attention (Gated DeltaNet) layers whose behaviour over RPC is unproven. Not downloaded. Note: the 4B Qwen3.5 uses the same hybrid architecture, so the 4B RPC spike result says whether this family can be split. |
| `Qwen/Qwen3-30B-A3B-GGUF` Q4_K_M (18,556,685,824 B) | Original hybrid thinking/non-thinking model, same size. Instruct-2507 is newer and non-thinking only. |
| Qwen3.8 | Only 27B dense (too big for one phone, wrong shape for MoE test) plus third-party distills of 4B; no official 4B. |
| Qwen3-4B, gemma-3-4b-it | Superseded by Qwen3.5-4B and Gemma 4 (a llama.cpp-compatible Gemma 4 GGUF exists). |
| Qwen3-Embedding-0.6B Q8_0 (639,150,592 B, Apache-2.0) | Fallback embedder, not downloaded because EmbeddingGemma-300M loads fine. |

## Large model status

Download complete and verified on the PC (size 18,556,686,752 and sha256 equal to the Hugging Face LFS oid; a PC crash at 00:49 zero-filled the tail, so the file was truncated and resumed with `curl -C -`, then re-hashed). Still to be pushed to phone C (needs the USB cable, wireless ADB is about 3 MB/s, so 18.6 GB would take over an hour). See `docs/benchmarks/2026-10-10-rpc-split-spike.md` for the split test.

Re-fetch command if it is ever lost:

```
cd /h/UStaaf && bash native/fetch-models.sh unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf
```
