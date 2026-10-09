# Ustaad pitch (one page)

Status 2026-10-10, master b661cdf. Every number is followed by the file it comes from. A blank is marked BLANK with the file to read when the number exists. Nothing here is rounded in our favour.

## The problem

One phone has 16 GB of memory, about 15.6 GB usable (docs/PRD.md section 1), and it reaches Thermal Status 2 to 3 within 1.5 to 4 minutes of load (2026-10-10-llama-cpu-baseline.md). A 4-bit 30B model is 18.6 GB (docs/models.md), so it does not fit; when we tried, the phone produced no token in 134 s and the system killed 23 background apps (2026-10-10-rpc-split-spike.md). People also own several phones whose private files sit on different devices, and the usual answer, a cloud, is the thing they do not want for those files.

## What Ustaad is

Ustaad turns two or three iQOO phones on one local link into one system that shares work and keeps a log of what it did, with no cloud model in the loop. The product is the coordination layer, Swarm, over existing open-source runtimes (llama.cpp v0.6.0, docs/models.md); it is not a new model and not a new inference engine.

## The four modules, and the number that proves each

| Module | One line | Measured number | State |
|---|---|---|---|
| Swarm (link and coordination) | Phones find each other, hold a Wi-Fi Direct link and mark a lost phone away | 305 to 503 Mbps and 12 to 20 ms average RTT between two quiet phones; peer marked away 2.1 to 2.2 s after a force-stop (2026-10-10-swarmlink-g1.md) | Measured |
| Agent Choir | Each phone answers alone, the phones check each other, a Kotlin checker decides when it can | BLANK: accuracy and mean seconds of one phone, three samples, Choir, Choir plus tool check on the 20 built-in problems. Read the Choir note in docs/benchmarks/ (not written yet; the app stores `choir-eval-<ts>.json`) | Built, not measured |
| Data Union | Each phone searches its own files; only the question goes out and short snippets come back | BLANK: time to first visible text and full answer on the Direct link (target 2 s and 6 s, docs/PRD.md R4.4). Read the Data Union note in docs/benchmarks/ (not written yet). Embedder separation is measured: cosine 0.776 related against 0.404 unrelated (2026-10-10-inapp-llm.md) | Built, not measured |
| MemoryCloud | A model too big for one phone runs split by layer range over several | BLANK: 30B across three phones, tokens per second and memory per phone. Read 2026-10-10-memorycloud-inapp.md (not present). Measured so far: 4B split over two phones 3.18 tok/s against 14.86 on one (2026-10-10-rpc-split-spike.md) | Spike measured, app module not on master |
| ExpertMesh | The same split applied to a mixture-of-experts model, explained, not a second system | BLANK: no number; stretch item (docs/PRD.md section 5.6) | Not measured |

## What is honest about the limits

- Splitting a model that fits on one phone is slower. The 4B model ran at 3.18 tok/s split across two phones and 14.86 tok/s on one (2026-10-10-rpc-split-spike.md), because each token returns about 1 MB of logits over a link that moves 8 to 10 MB/s (same file). A split is for models that do not fit.
- The phones throttle. Decode was 14.86 tok/s cool, 10.7 at Thermal Status 3 (2026-10-10-llama-cpu-baseline.md) and 4.88 then 6.13 tok/s at CRITICAL (2026-10-10-inapp-llm.md). Thermal Status 2 to 3 arrives within 1.5 to 4 minutes of load (2026-10-10-llama-cpu-baseline.md). We keep each demo segment short and show the thermal state.
- The OS freezes the app. With the screen off, peers counted 22 to 265 missed heartbeats (2026-10-10-swarmlink-g1.md) and `pem_freezer` killed the app and its servers at 6 min 18 s and 11 min 2 s (2026-10-10-inapp-llm.md). The battery exemption is untested. We keep screens on with Ustaad in front.
- The link missed its latency target: 12 to 26 ms average against 8 ms (gate-1-swarmlink.md).
- Choir roles differ by prompt and temperature on one model, Qwen3.5-4B, not by model family (choir/ChoirPrompts.kt). On built-in problems the tool check uses the problem's stored test, so that score uses an answer key (choir/ChoirScreen.kt).
- The only internet use is the model download screen, and it says so (llm/ModelsScreen.kt).

## Trust model today

Requests between phones are HTTP with an HMAC signature from the pairing secret (docs/CONTRACTS.md section 1); they are not encrypted. On master responses are not authenticated, pairing needs no confirmation, and the MemoryCloud rpc-server has no authentication (docs/review/2026-10-10-merged-app-review.md, sections 2 and 3). Fixes are on branches (docs/SETUP.md section 10). In Data Union the query text goes to each peer; embeddings and files do not; a peer returns at most five snippets of 400 characters; files in a folder named Private are only counted (docs/PRD.md R4.2; pdu/Ranking.kt).

## Claims we do not make

- No "Wi-Fi 7". The Wi-Fi standard and speed of the Direct interface cannot be read without root (2026-10-10-swarmlink-g1.md). We quote measured Mbps only.
- No "first". LinguaLinked (2023) and exo (2024 demo) are prior art (docs/research/06-hackathon-context-and-landscape.md).
- No "faster than one phone" without a table. The only comparison we hold, for a model that fits, says slower (above). For a model that does not fit there is nothing to be faster than: one phone produced no token.
- No "fully private" or "zero-trust". See the trust model above.
- No "0 bytes to the internet" as a measurement. The on-screen stamp only counts the model downloader (ui/StatsBar.kt, review item 4). Bundled ML Kit upload code was never measured and is removed on a branch (review section 3).
- No NPU speedup or TOPS figure. The app ships the CPU build (2026-10-10-inapp-llm.md); the GPU prefill result was one repetition (2026-10-10-llama-cpu-baseline.md).
- No vivo BlueLM, Gemini Nano or official OriginOS background API, and no "works in the background without battery setup" (docs/research/06).
- No "two model families cross-check each other" (see limits).
- No Office Kit result until mirroring is tried (docs/DEMO.md).

## Three likely judge questions

1. "Is it faster than one phone?" For a model that fits, no: 3.18 against 14.86 tok/s (2026-10-10-rpc-split-spike.md). What the split buys is capacity: a 18.6 GB model that one phone cannot run (no token in 134 s, same file). Whether three phones run it, and at what speed, is BLANK until 2026-10-10-memorycloud-inapp.md exists. Aggregate throughput across phones is also BLANK (no benchmark yet).
2. "What leaves a phone, and is it private?" The question text goes to the other phones; embeddings and whole files do not; each returns at most five snippets of 400 characters; private folders are only counted, and the audit page shows what was sent and what each phone returned (docs/PRD.md R4.2; pdu/PduAudit.kt). It is not encrypted and not authenticated both ways on master; see the trust model. We say private by design, not fully private.
3. "What happens when a phone sleeps, overheats or drops?" A dropped or frozen phone is marked away in 2.1 to 2.2 s and is back 1.0 s after the app restarts (2026-10-10-swarmlink-g1.md); Choir drops a phone that has not answered within its round budget (hard deadline 60 s) and Data Union flags the answer Incomplete (choir/ChoirCoordinator.kt, pdu/PduAsk.kt). Overheating cut decode from 14.86 to 4.88 and 6.13 tok/s in the run that reached CRITICAL (2026-10-10-llama-cpu-baseline.md, 2026-10-10-inapp-llm.md), so the demo is short and shows thermal state. A long background run is not supported: the OS kills it.
