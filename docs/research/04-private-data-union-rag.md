# 04 - Private Data Union: federated on-device search + RAG across 2-3 Android 16 phones

Date: 2026-10-09. Target: Snapdragon 8 Elite Gen 5, 16 GB RAM, Android 16.
Evidence labels: **(V)** = read from a cited page this session; **(unverified)** = from search-snippet secondary sources or prior knowledge, not confirmed against a primary source. Search tooling returned thin Android-specific benchmark data, so most latency numbers are budgets to be measured on the device, not facts.

---

## 0. Executive summary

- **Embedder:** EmbeddingGemma 2 (Oct 6 2026, Apache 2.0, 270M text tower / 740M omni stack) if its LiteRT-LM Kotlin path works on the Snapdragon NPU/GPU; **fallback EmbeddingGemma v1 300M** (308M params, 768d with MRL to 128, 2K ctx, <200 MB RAM quantized). Images: **SigLIP2-B/16 via LiteRT GPU** (community conversion exists) or EmbeddingGemma 2 text+vision if the shared space works well enough.
- **Vector store:** **sqlite-vec** (brute-force, one `.db` per phone, built via NDK, loaded with AndroidX `BundledSQLiteDriver.addExtension`) for the demo corpus (<100k chunks). **ObjectBox vector (HNSW)** is the drop-in if we need ANN or a first-class Kotlin API.
- **Parsers:** MediaStore + Photo Picker/SAF for files, `PdfRenderer` + ML Kit text recognition v2 for scanned PDFs/photos/screenshots, pdfbox-android for text PDFs, DOCX via Apache POI-lite/zip+XML (manual).
- **Protocol:** broadcast **query text (signed, ephemeral)**, not embedding; each phone embeds locally, returns top-k **snippets + normalized scores**; the coordinator merges with RRF + optional cross-encoder rerank; answer cites device-of-origin.

---

## 1. Embedding models (Oct 2026)

### 1.1 Text embedders

| Model | Params | Dim | Ctx | License | Quality evidence | Android runtime |
|---|---|---|---|---|---|---|
| **EmbeddingGemma 2** | 740M omni (270M text / 440M text+vision variants per LiteRT-LM listing; sizes 165/388/485 MB) | 768, MRL down to 128 | 8K | Apache 2.0 (reported) | Reported 78.68 on MTEB(Code), "best in size class" on MIEB-Lite (unverified, secondary) | LiteRT-LM Kotlin (Stable), CPU/GPU/NPU; MediaPipe, llama.cpp etc. |
| **EmbeddingGemma 300M (v1)** | 308M (~100M model + ~200M embedding table) (V) | 768 -> 512/256/128 MRL (V) | 2K (V) | Gemma terms (page does not state; unverified) | Google: top open multilingual text model <500M on MTEB (V); IBM table: 62.5 on 18-lang multilingual retrieval avg (unverified, vendor) | LiteRT, llama.cpp, transformers.js, etc. (V); <200 MB RAM quantized (V); <15 ms/256 tok on EdgeTPU (V, not Snapdragon) |
| Qwen3-Embedding-0.6B | ~596M | up to 1024, MRL | 32K | Apache 2.0 (prior knowledge, unverified) | MTEB multilingual mean-task 64.33, retrieval 61.41 (unverified, aggregator) | llama.cpp / ONNX; ~2x EmbeddingGemma size, heavier on phone |
| Granite Embedding 97M Multilingual R2 | 97M | 384 | 32K | Apache 2.0 | 60.3 multilingual retrieval (IBM claim; another page says 59.6) (unverified) | ONNX Runtime / llama.cpp; smallest strong multilingual option |
| Granite small English R2 | 47M | 384 | - | Apache 2.0 | English only | ONNX |
| bge-m3 | 568M | 1024 | 8K | MIT (prior knowledge, unverified) | Strong multilingual, dense+sparse+colbert | Too heavy; skip |
| jina-embeddings v3/v4, nomic-embed v2, snowflake-arctic-embed-m v2, gte-multilingual | 137M-570M+ | 768-1024 | 8K | jina v3/v4 are CC-BY-NC (prior knowledge, unverified; blocks commercial use); nomic/arctic/gte Apache-2.0 (unverified) | Not re-verified this session | ONNX / llama.cpp |
| MiniLM-L6 / multilingual-MiniLM | 22M-118M | 384 | 256-512 | Apache 2.0 | Weak vs 2025+ models, but trivially fast | ONNX Runtime Mobile, MediaPipe Text Embedder |

Sources: [EmbeddingGemma launch blog](https://developers.google.com/blog/embeddinggemma) (fetched at `developers.googleblog.com/introducing-embeddinggemma/`), [LiteRT-LM overview](https://developers.google.com/edge/litert-lm/overview), [LiteRT-LM embedding docs](https://developers.google.com/edge/litert-lm/embedding_models), [SiliconANGLE on EmbeddingGemma 2](https://siliconangle.com/2026/10/06/google-expands-embeddinggemma-beyond-text-to-images-audio-and-video/), [Granite Embedding Multilingual R2](https://huggingface.co/blog/ibm-granite/granite-embedding-multilingual-r2), [Qwen3-Embedding listing](https://www.datalearner.com/ai-models/pretrained-models/Qwen3-Embedding-0_6B).

**Measured speed data (V, from LiteRT-LM overview):** EmbeddingGemma 2 on Samsung S26 Ultra (Snapdragon-class flagship): text 27.1 ms CPU / 25.9 ms GPU; text+vision 175 ms CPU / 119 ms GPU; Pixel 11 Pro TPU text 8.3 ms. Input length for these rows is not stated. [Source](https://developers.google.com/edge/litert-lm/overview). No Snapdragon 8 Elite Gen 5 row for embeddings was found; budget ~25-40 ms for a short query on GPU.

**Caveat on EmbeddingGemma 2:** I could not retrieve the primary Google blog/model card, so params/ctx/license come from secondary coverage ([X post from Google Gemma account](https://x.com/googlegemma/status/2107502533992464482), [hyper.ai](https://hyper.ai/en/stories/2b394de14f674391a50cdff9ca17b08c)). Reported RAM: ~191 MB text-only, ~567 MB full multimodal on Pixel 11 Pro (unverified).

**Recommendation:** use EmbeddingGemma (v1 300M or v2 text tower) with MRL truncated to **256 dims** for storage/speed (v1 supports this officially (V)), with required task prefixes (document vs query) and whitespace trimming (V, LiteRT-LM docs). Keep Granite 97M Multilingual R2 as the lightweight fallback. All models in one federation MUST be identical (same model + same prefix + same dim), otherwise scores are not comparable.

### 1.2 Image-text embedders

| Model | Size | Dim | License | Android path | Notes |
|---|---|---|---|---|---|
| **SigLIP2 B/16 224** | image tower FP16 file ~185 MB (V) | 768 L2-normalized (V) | Apache 2.0 (prior knowledge, unverified) | Community LiteRT conversion, runs on LiteRT GPU `CompiledModel`, no CPU fallback/Flex ops (V) - [litert-community/SigLIP2-base-patch16-224](https://huggingface.co/litert-community/SigLIP2-base-patch16-224) | Best off-the-shelf Android route; text tower must be run too (precompute labels or run text tower for queries) |
| MobileCLIP2-S2 | image 35.7M (~3.6 ms), text 63.4M (~3.3 ms) (iPhone figures, V) | 512 (unverified) | `apple-amlr` (non-standard, check terms before shipping) (V) | No official Android port found (V) | Third-party benchmark claims 9.74 ms NPU / 39 ms GPU / 495 ms CPU across devices (unverified) - [MobileCLIP2 paper](https://arxiv.org/pdf/2508.20691) |
| EmbeddingGemma 2 text+vision | 440M variant | 768 MRL | Apache 2.0 | LiteRT-LM Kotlin, image input in `InputData` (V) | One model for text AND images in one space; 119 ms GPU per text+image on S26 Ultra (V) |
| Jina CLIP v2 / OpenCLIP | 800M+ | 1024 | CC-BY-NC (jina, unverified) | ONNX | Too heavy / license-restricted |

**Image strategy (pragmatic):** do not rely only on CLIP-style embeddings for "find the warranty bill" - receipts/screenshots are text-heavy. Run **ML Kit OCR first**, embed the OCR text with the text embedder, and ALSO store a SigLIP2 image vector for natural-image queries ("photo of the dog at the beach"). Two vectors per image, two tables.

### 1.3 Rerankers

| Model | Size | Multilingual | Mobile fit |
|---|---|---|---|
| cross-encoder/ms-marco-MiniLM-L-6-v2 (ONNX) | ~80 MB (claim) (unverified) | English | Best small option; "<5 ms on any phone" is an unverified forum claim; Qdrant laptop benchmark p50 ~25 ms/request (MacBook M4 Max) |
| ms-marco-TinyBERT-L-2 | smaller | English | fastest |
| bge-reranker-v2-m3 | 568M / 2.27 GB | 100+ langs | too heavy for phone; skip |
| LLM-as-reranker (the local Gemma) | - | yes | costs seconds; skip unless the budget allows |

Sources: [ms-marco-MiniLM discussion](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2/discussions/19), [bge-reranker-v2 docs](https://bge-model.com/bge/bge_reranker_v2.html). My recommendation: **no reranker in v1 of the demo**; use hybrid (dense + BM25 via SQLite FTS5) and RRF. Add MiniLM cross-encoder on the coordinator only (merge step, ~15-30 candidates) as a stretch.

### 1.4 Runtime choice on Snapdragon 8 Elite Gen 5

- **LiteRT (CompiledModel) + Qualcomm AI Engine Direct (QNN) accelerator** lists SM8850 (8 Elite Gen 5) as supported; Google reports 56 of its benchmark models under 5 ms on the NPU on a Xiaomi 17 Pro Max; 64 of 72 models fully delegate (V, Google's own figures) - [LiteRT Qualcomm guide](https://developers.google.com/edge/litert/next/qualcomm), [Google blog](https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/).
- **ONNX Runtime + QNN EP** also works on Android in principle, but I found only Linux/Python examples (unverified for Kotlin).
- **No published embedding-model-on-Hexagon benchmark found.** NPU int8/int16 accuracy of the embedder must be checked (cosine vs fp32 reference) before relying on it.
- **Practical order:** LiteRT-LM GPU (documented, Kotlin Stable) -> NPU if accuracy holds -> CPU.

---

## 2. On-device vector stores

| Store | Kotlin / Android | ANN | Practical max at phone latency | Persistence | License |
|---|---|---|---|---|---|
| **sqlite-vec** | No official AAR found; build C with NDK, register via AndroidX `BundledSQLiteDriver.addExtension` (V via PowerSync docs), or 3rd-party [room-vec](https://klibs.io/project/hub-bla/room-vec). Pre-v1, "expect breaking changes" (V) | Brute-force `vec0` (float/int8/binary); repo has experimental ivf/diskann/rescore files (V, undocumented) | ~500 ms for 500K x 768d (secondary claim, unverified) -> on 256d int8 well under 100 ms for 100k | SQLite file | MIT / Apache-2.0 (V) |
| **ObjectBox vector** | Official Java/Kotlin (V) | HNSW | Vendor: millions in ms (unverified, vendor); flat search suggested <500 items (V, face-recognition sample) | Own DB file | Java/Android + C core repos Apache-2.0; Sync proprietary; confirm license of bundled native .so (unverified) - [docs](https://docs.objectbox.io/on-device-vector-search) |
| **usearch** | Only 3rd-party KMP binding (usearch-kmp 0.5.1, Apache-2.0) (V via klibs.io); official Java/JNI exists (unverified) | HNSW, f16/i8 quant | Millions, fast | `save/load` file | Apache-2.0 |
| LanceDB | No Android SDK found (V: none) | IVF-PQ | n/a | Lance files | Apache-2.0 |
| Faiss via JNI | No Android build found; DIY NDK | IVF/HNSW | good | file | MIT |
| Room + brute-force (float BLOBs, dot product in Kotlin/NEON) | trivial | none | ~50-100k x 256d in tens of ms (estimate, unverified) | SQLite | n/a |
| Chroma | server/Python, not for Android | - | - | - | Apache-2.0 |

**Decision:** corpus per phone for the demo is ~2k-50k chunks. At that scale brute force wins on simplicity and correctness: **sqlite-vec with 256-dim int8 or float vectors + FTS5 for BM25 in the same DB file** = one transactional store for chunk text, metadata, vector, keyword index. If sqlite-vec's NDK/extension loading eats more than half a day, fall back to **Room + float BLOB + brute-force dot product** (same schema, swap later) or **ObjectBox** (official Kotlin support, HNSW).

---

## 3. Content ingestion on Android 16

### 3.1 Access model

| Source | Mechanism | Constraint |
|---|---|---|
| Photos/videos | `READ_MEDIA_IMAGES`, or Android 14+ partial access `READ_MEDIA_VISUAL_USER_SELECTED` (user picks subset; app must target API 34+ else compat mode) (V) [docs](https://developer.android.com/about/versions/14/changes/partial-photo-video-access); Photo Picker needs no permission | For a standing index you want full `READ_MEDIA_IMAGES` ("Allow all"); Photo Picker is one-shot (no background re-scan). For the demo: ask for full image access. |
| Documents/PDFs/DOCX | SAF `ACTION_OPEN_DOCUMENT_TREE` on a folder (persist URI permission) | Per-folder grant; user must pick Downloads/Documents; Android blocks selecting some root dirs (Downloads root, `Android/data`) (prior knowledge, unverified) |
| All files | `MANAGE_EXTERNAL_STORAGE` | Play policy: "last resort", must be core functionality, requires Permissions Declaration Form; permitted-use list includes **on-device file search**, file managers, backup, document management (V) [Play policy](https://support.google.com/googleplay/android-developer/answer/10467955). For a hackathon APK sideloaded via adb, no Play review applies; for a Play pitch, "on-device file search" is a named legit use. |
| Notes apps | Google Keep/Samsung Notes have no public read APIs; use share/export (`ACTION_SEND` target) or exported files in a folder | No generic read access (prior knowledge, unverified) |
| SMS | `READ_SMS` is Play-restricted (default SMS handler/Dialer only) | Not feasible on Play; sideload only (prior knowledge, unverified) |
| Contacts | `READ_CONTACTS` runtime perm | fine, low volume |
| WhatsApp | No API; user exports chat via "Export chat" -> .zip/.txt, drop into the picked folder | parse `[date, time] Name: msg` lines (unverified format variants per locale) |
| LAN | Android 16: local network restriction is opt-in only; Android 17 (targetSdk 37) makes `ACCESS_LOCAL_NETWORK` mandatory and covers raw sockets, OkHttp, NsdManager, mDNS (V) [docs](https://developer.android.com/privacy-and-security/local-network-permission) | Target SDK 36 for the demo -> no extra permission; declare `NEARBY_WIFI_DEVICES` for forward compatibility. |

### 3.2 Parsers

| Type | Parser |
|---|---|
| Text PDF | pdfbox-android (Apache-2.0, large; ~slow on big PDFs) (prior knowledge, unverified) |
| Scanned PDF | `android.graphics.pdf.PdfRenderer` -> bitmap per page -> ML Kit OCR |
| Images / screenshots / photos of bills | ML Kit **text recognition v2**; Latin bundled `com.google.mlkit:text-recognition` 16.0.1, separate bundled artifacts for Devanagari/Chinese/Japanese/Korean; ~38 MB per script (V) [docs](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) |
| DOCX | unzip + parse `word/document.xml` text runs (30 lines of Kotlin); avoid full POI (heavy) |
| TXT/MD/CSV/exported chats | direct read |
| EXIF / MediaStore metadata | date, location (strip before sharing), album |

### 3.3 Chunking

- Semantic-ish fixed windows: **~200-300 tokens with ~15% overlap**, split on paragraph/sentence boundaries; PDFs chunk per page then within page. EmbeddingGemma ctx is 2K (V) so chunk well under it.
- Prefix each chunk with a title line (filename + date) before embedding (cheap contextual retrieval).
- Store: `chunk_id, doc_id, text, source_uri, mime, mtime, page, embedding, sensitivity_flag`.
- OCR result for a bill image = one doc, 1-3 chunks; keep key fields (vendor, amount, date) in an extracted-metadata column for exact-match boosts.

### 3.4 Background indexing (WorkManager)

- `CoroutineWorker` with `Constraints(requiresCharging=true, requiresDeviceIdle=true, requiresBatteryNotLow=true)` for the full scan; expedited/foreground-service (`dataSync`/`specialUse` type, Android 15+ timeout rules apply, 6 h limit for dataSync (prior knowledge, unverified)) for the "index now" demo button.
- Incremental: store `MediaStore.DATE_MODIFIED` high-water mark + `MediaStore.getGeneration()` (API 30+) to find new files.
- Batch embeddings (e.g., 16-32 chunks/batch), checkpoint every batch so a killed worker resumes.
- Pre-index the demo corpora BEFORE the demo; never index live on stage.

---

## 4. Federated query protocol

### 4.1 Query text vs query embedding

| Option | Privacy | Interop | Verdict |
|---|---|---|---|
| Broadcast **query embedding** | Embedding inversion (Vec2Text-style) can recover short text from dense vectors; defenses (noise) degrade retrieval (V via review, RemoteRAG, FedRepRAG; recovery % figures unverified) [RemoteRAG](https://arxiv.org/html/2412.12775v1), [FedRepRAG](https://arxiv.org/pdf/2609.22162) | Requires identical model on all devices | Not actually more private than text; no real gain |
| Broadcast **query text** | The query reveals intent to peers - but peers are the user's own devices in the same trust circle | Each phone can use its own embedder + BM25 (hybrid) | **Chosen.** Users pair devices explicitly (QR code); query text is encrypted in transit; same-owner trust boundary is the privacy story |

Honest framing for judges: the guarantee is "raw corpus never leaves the device; only the top-k snippets the user's own paired devices return for a question the user typed." Say "same-owner or explicitly-paired devices" - this is a trust circle, not an anonymous federation.

### 4.2 Wire protocol (6 steps)

1. **Discovery/pairing:** mDNS via `NsdManager` (`_pdu._tcp`) on the same Wi-Fi; first-time pairing by QR (exchange public keys); thereafter mutual TLS or Noise-protocol session. (Wi-Fi Direct/Nearby Connections is the fallback if venue Wi-Fi blocks client-to-client mDNS - a real risk at hackathons.)
2. **Query broadcast:** `{qid, query_text, k, embedder_id, deadline_ms, filters}` signed with device key.
3. **Local retrieval on each phone:** embed query with local model -> sqlite-vec top-N (N=30) + FTS5 BM25 top-N -> RRF -> top-k (k=5) -> per-device sensitivity filter (see 4.4).
4. **Response:** `{qid, device_id, hits:[{snippet(<=400 chars), score_raw, score_norm, rank, doc_title, doc_date, mime, local_ref}], latency_ms}`. No file bytes, no full docs, no embeddings.
5. **Merge on the coordinator:** collect until `deadline` (say 1.5 s) or all peers respond; ignore late responses; merge by **reciprocal rank fusion** across (device, rank) lists, with within-device min-max or z-score normalization as tie-breaker. Dedupe near-duplicates (same hash of normalized text) across devices.
6. **Synthesize + cite:** coordinator LLM gets top ~6-8 snippets with `[device:Priya's Pixel | file: warranty_bill.pdf | p2]` tags, answers with citations. UI shows chips for origin device; tapping a citation on the *origin* phone opens the file (local deep-link), never transferring the file.

### 4.3 Score normalization (prior art)

Classic federated-search result merging (CORI, Callan-Lu-Croft 1995; min-max; global-IDF) addresses incomparable scores across collections. Findings: min-max is a robust unsupervised default; CORI helps but needs collection stats (term probabilities, IDF); topically organized collections benefit from global IDF (V via search summaries: [UMass CORI paper](https://maroo.cs.umass.edu/pub/web/getpdf.php?id=215), [Google Patents US7984039](https://patents.google.com/patent/US7984039)). Because all phones run the **same embedding model and a dense cosine scale**, raw cosine is nearly comparable; still apply per-query min-max over the device's top-N plus RRF to avoid one phone with a bigger, noisier corpus dominating. Optionally have the coordinator rerank the merged ~20 snippets with the MiniLM cross-encoder, which sidesteps the score-comparability problem entirely (the reranker sees text only).

### 4.4 What metadata may leave the device

| Leaves | Stays |
|---|---|
| Snippet text (<=400 chars), doc title/filename, doc date, mime, page number, device display name, scores | Raw files/photos, full documents, embeddings, absolute paths, GPS/EXIF location, contact lists, other users' phone numbers |
| Redacted snippets (regex mask of card/Aadhaar/SSN/OTP-like patterns) | Chunks flagged sensitive (health, finance categories) unless the user toggles "share in this session" |

Add a **per-device share policy** screen (folders/categories allowed to answer federated queries) and a visible **audit log** ("Priya's Pixel returned 3 snippets for query 'warranty bill' at 14:02") - the audit log is a strong demo asset.

### 4.5 Timeouts and away phones

- Per-query deadline 1.5-2.0 s for retrieval responses; coordinator streams the LLM answer as soon as the quorum is in and shows "Phone C didn't respond (asleep/away)" chip; late snippets can trigger an "improve answer" re-run.
- Heartbeat via NSD service presence; mark peers offline after 2 missed pings; cache nothing from peers (no stale remote data on coordinator).
- Phones in Doze: foreground service with a high-priority wake path, or the user opens the app (demo: keep all three awake, "stay awake while charging").

### 4.6 Prior art and platform constraints

- **Apple Intelligence:** on-device "semantic index" of personal data, with Private Cloud Compute as fallback; the pitch is that personal context stays on-device unless a request exceeds capacity (secondary sources, 2024) - [Constellation summary](https://www.constellationr.com/insights/news/apples-genai-strategy-device-processing-private-cloud-own-integration-and-abstract). Our novelty vs Apple: *multi-device, multi-owner union with no cloud tier*.
- **Android AICore / Gemini Nano:** system service; requests are isolated and not stored; model distribution handled by AICore; token limits and device hardware limits apply and exact numbers are not on the page (V) [developer.android.com/ai/gemini-nano](https://developer.android.com/ai/gemini-nano). ML Kit Prompt API performs best on Pixel 10 series (nano-v3); on other devices Google suggests prototyping with Gemma 3n (secondary, unverified for non-Pixel). **Do not depend on AICore on non-Pixel hardware** - run Gemma via LiteRT-LM instead.
- **Federated RAG research:** FedRepRAG (2026) shares representations not raw context and shows noise (sigma 0.5-1.0) cutting retrieved-context inversion from 20.4% to 0.6%/0.2% but residual leakage remains (V as reported in search summary) - [arXiv 2609.22162](https://arxiv.org/pdf/2609.22162). RemoteRAG uses distance-DP on the query embedding. Both support our decision to **not** transmit embeddings and to minimize returned text.

---

## 5. Recommended stack and budgets

### 5.1 Stack

| Layer | Choice |
|---|---|
| App | Kotlin, Jetpack Compose, one APK on all phones (role: any phone can be coordinator) |
| Embedder | EmbeddingGemma (v2 text tower or v1 300M), 256-d MRL, LiteRT-LM Kotlin, GPU backend (NPU after accuracy check) |
| Image | ML Kit OCR v2 -> text chunks; SigLIP2-B/16 LiteRT GPU image vectors (optional second table) |
| Store | SQLite: sqlite-vec (`vec0`) + FTS5; fallback Room BLOB brute-force |
| Hybrid retrieval | dense top-30 + BM25 top-30 -> RRF -> top-5 |
| Transport | NsdManager discovery + TLS (QR-pinned keys) over HTTP/WebSocket (Ktor/OkHttp) |
| Synthesizer LLM | Gemma 4 E2B on LiteRT-LM GPU (Apache 2.0, Apr 2026 (V via BetaNews/VentureBeat); S26 Ultra GPU: 3808 tok/s prefill, 52 tok/s decode, TTFT 0.3 s, 676 MB (V from LiteRT-LM table)); E4B for quality (GPU 1293 prefill / 22 decode (V)). Fallback Gemma 3n E2B. |
| Rerank (stretch) | ms-marco-MiniLM-L6 ONNX on coordinator over ~20 merged snippets |

Note: Snapdragon 8 Elite Gen 5 numbers are not published for these LLMs; the above is Samsung S26 Ultra, which is a flagship Snapdragon-class device (S26 SoC variant by region not verified) - re-measure on the demo phones.

### 5.2 Latency budget (warm models, ~1k corpus-chunks per phone ~ 20-50k)

| Step | Budget | Basis |
|---|---|---|
| Embed query (per phone, in parallel) | 30-50 ms | 26-27 ms for EmbeddingGemma 2 text on S26 Ultra (V) + prefix/tokenize |
| Local ANN + BM25 + RRF | 10-60 ms | brute-force sqlite-vec at <=50k x 256d (estimate, unverified) |
| Network hop(s) over Wi-Fi (query out, snippets back) | 20-80 ms typical, 1.5 s hard deadline | LAN RTT estimate (unverified) |
| Merge + dedupe (+ optional rerank) | 5 ms (+ 30-100 ms rerank) | estimate |
| Prompt prefill (~1.5k tokens) | 0.4-0.8 s | at ~2-3.8k tok/s GPU prefill (V figure from S26 Ultra E2B) |
| Time to first token | ~0.3-0.5 s (V: TTFT 0.3 s) | LiteRT-LM table |
| Generation (~120 tokens) | 2.3 s at 52 tok/s (E2B) / 5.5 s at 22 tok/s (E4B) | V table |
| **Time to first visible text** | **~0.8-1.2 s** | sum of retrieval + TTFT |
| **Full answer** | **~3.5-4.5 s (E2B)** | |

Show retrieved cards from each phone *immediately* (streaming UI), then the synthesized answer; perceived latency is dominated by the snippets appearing in <300 ms.

### 5.3 Demo script (judges)

Setup: 3 phones on a travel router (not venue Wi-Fi). Pre-indexed, seeded realistic corpora, indexing done in advance; show the index counts on each phone.

1. **Airplane test** (15 s): turn on a "Data leaves device: 0 bytes" panel - show cellular off / no internet; federation works over LAN only.
2. **"Find the warranty bill"** (core): ask on Phone A: "When does the warranty on my washing machine expire and where's the bill?" Phone B has a photographed receipt (OCR'd), Phone C has an email-exported PDF "Warranty terms". Answer cites **[Phone B: IMG_2231.jpg, 12 Mar 2025]** + **[Phone C: LG_warranty.pdf p2]** and computes the expiry date. Tap a citation -> opens on the owning phone.
3. **Multilingual** (20 s): ask the same in Hindi/Spanish (EmbeddingGemma is 100+ langs (V)); the bill is in English.
4. **Privacy proof** (30 s): open the audit log on Phone B: exactly three snippets, 400 chars each, no files; show a "sensitive" folder (medical reports) that refuses to answer ("Phone C withheld 2 sensitive matches") - policy enforced on the owner's device.
5. **Graceful degradation** (20 s): put Phone C in a bag/turn Wi-Fi off mid-demo; re-ask; the UI shows "Phone C unreachable", answer still arrives from A+B, with a flagged "may be incomplete".
6. **Network-sniff slide** (optional): a laptop running Wireshark shows only encrypted TLS blobs of snippet sizes - no file transfers.

---

## 6. Five risks

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| 1 | **LAN discovery fails** at venue (client isolation blocks mDNS/peer traffic); Android 16/17 local-network rules | High / critical | Bring own router/hotspot; fall back to Wi-Fi Direct/Nearby Connections or manual IP + QR; target SDK 36 to avoid mandatory `ACCESS_LOCAL_NETWORK` (V: mandatory only at SDK 37) |
| 2 | **Runtime integration time sink**: EmbeddingGemma 2 (3 days old, primary docs not retrieved), LiteRT-LM/NPU, sqlite-vec NDK build all pre-v1 or new | Medium-High / high | Day-1 spike per component; fallback matrix: Granite 97M via ONNX CPU, Room brute-force, Gemma 3n/E2B; time-box NPU to optional |
| 3 | **Retrieval quality on messy personal data** (OCR errors on receipts, short screenshot text, cross-device score mismatch) | Medium / high | Hybrid dense + BM25, OCR confidence filter, metadata fields (amount/date) boost, RRF merge, golden query set of 20 questions measured before demo |
| 4 | **Privacy claims overstated** - snippets are real user data, query text is visible to peers, embeddings invertible if sent | Medium / medium-high (judges probe it) | State the trust model plainly (paired same-owner devices), never send embeddings, per-device share policy + redaction + audit log; avoid word "zero-knowledge" |
| 5 | **Thermal/battery/RAM**: embedder + OCR + LLM (E2B 676 MB GPU, 1.7 GB CPU (V)) + indexing on 16 GB phones; throttling during demo; Android kills background workers; Gemini Nano/AICore not available on non-Pixel | Medium / medium | Pre-index; one resident LLM only on the coordinator; keep phones plugged, cool; foreground service; Gemma via LiteRT-LM not AICore |

---

## Source list

- EmbeddingGemma: https://developers.googleblog.com/introducing-embeddinggemma/
- LiteRT-LM overview/benchmarks: https://developers.google.com/edge/litert-lm/overview ; embeddings: https://developers.google.com/edge/litert-lm/embedding_models
- EmbeddingGemma 2 coverage: https://siliconangle.com/2026/10/06/google-expands-embeddinggemma-beyond-text-to-images-audio-and-video/ ; https://hyper.ai/en/stories/2b394de14f674391a50cdff9ca17b08c ; https://x.com/googlegemma/status/2107502533992464482
- Gemma 4 license: https://venturebeat.com/technology/google-releases-gemma-4-under-apache-2-0-and-that-license-change-may-matter ; https://huggingface.co/google/gemma-4-E2B
- Granite R2: https://huggingface.co/blog/ibm-granite/granite-embedding-multilingual-r2
- SigLIP2 LiteRT: https://huggingface.co/litert-community/SigLIP2-base-patch16-224
- MobileCLIP2: https://arxiv.org/pdf/2508.20691
- LiteRT Qualcomm: https://developers.google.com/edge/litert/next/qualcomm ; https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/
- sqlite-vec: https://github.com/asg017/sqlite-vec ; room-vec: https://klibs.io/project/hub-bla/room-vec ; PowerSync extension loading: https://docs.powersync.com/client-sdks/advanced/sqlite-extensions
- ObjectBox: https://docs.objectbox.io/on-device-vector-search (fetch failed; via search results)
- usearch-kmp: https://klibs.io/package/com.zhufucdev.usearch/core
- Android partial photo access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Play all-files policy: https://support.google.com/googleplay/android-developer/answer/10467955
- Local network permission: https://developer.android.com/privacy-and-security/local-network-permission
- ML Kit text recognition v2: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Gemini Nano / AICore: https://developer.android.com/ai/gemini-nano
- Federated search merging: https://maroo.cs.umass.edu/pub/web/getpdf.php?id=215 ; https://patents.google.com/patent/US7984039
- RAG privacy: https://arxiv.org/html/2412.12775v1 ; https://arxiv.org/pdf/2609.22162
- Apple: https://www.constellationr.com/insights/news/apples-genai-strategy-device-processing-private-cloud-own-integration-and-abstract
