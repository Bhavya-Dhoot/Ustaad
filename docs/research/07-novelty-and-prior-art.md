# 07 - What is and is not novel about Ustaad (prior-art study)

Research date: 2026-10-10. Method: web search plus page fetches this session; no phone runs. Numbers about Ustaad (640 to 920 Mbps, 10 Mbps, 700 of 700, 6.9 tok/s, about 2x) are quoted from the task brief and were not re-checked in this task. Labels: (V) read on a fetched page, (S) search-result snippet or search summary only, (U) unverified or inferred. Absence of a result is not proof of absence; every "none found" below lists the terms tried (section 5).

Limits that matter:
- Patents: Google Patents was reachable only through a search engine. For the patents named I read bibliographic data and description text, **not the claims**. No Espacenet, IPO India, or CNIPA full-text search was possible. A patent named here is a lead, not a freedom-to-operate finding.
- Chinese-vendor work was searched once in Chinese and once in English at news level. Vendor internals are not public; "nothing found" for Huawei, Xiaomi, vivo is weak evidence.
- This is not legal advice (section 4).
- Existing reports 03 and 06 already cover exo, Petals, Parallax, distributed-llama, prima.cpp, LinguaLinked, Cake, QVAC Fabric; they appear below only where they change a verdict.

## 0. Findings in eight lines

1. Every individual part has close prior art. No part is a clean invention. One mechanism (channel pinning) is also covered by other companies' patents.
2. Closest overall: prima.cpp (ICLR 2026, Android via Termux) and LinguaLinked (ACL 2024) for multi-phone inference; Nearby Connections / Quick Share for the link; DRAG (2025) for peer-to-peer snippet retrieval; Microsoft US10271336B2 and Samsung US11223929B2 for the group-on-station-channel idea.
3. Where a defensible novelty claim exists: (a) the combination in one consumer Android app with no root and no cloud; (b) the VERIFIED / CONSISTENT labels plus an equal-compute baseline shown in the product; (c) lease-bound memory lending tied to the active link for split inference; (d) private-by-default snippets with per-phone, per-file citations and owner-notification open.
4. The measured Android numbers (same-channel 640 to 920 Mbps vs 10 Mbps off-channel; 700 of 700 executed once) are data, not inventions. I found no published Android measurement of the channel effect, but the effect itself is the reason the Microsoft and Samsung patents exist.
5. Hash-chained logging, idempotent retries, leases, QR pairing, majority voting, and tool-checked answers are all old. Say "we use", never "we invented".
6. Vendors (Huawei, Xiaomi, vivo, Samsung, Apple, Google, Qualcomm) were found to ship cross-device continuity and agents, and cloud-plus-device AI. None was found to split one model, or pool memory, across several phones. Weak evidence (section 1.4).
7. Patentability looks weak overall; the best two candidates are in section 4. Public demonstration can destroy novelty in India and at the EPO; the finale runs to tomorrow.
8. Biggest risk to a "first" claim: Voltron (arXiv 2607.07046, July 2026) and the 2026 GitHub projects; their device details are unread (U).

## 1. Prior art, newest first, by part

### 1.1 SwarmLink: Wi-Fi Direct on the station channel, QR pairing, Bluetooth path, retry across paths

| Date | Item (type, platform) | What it does | Overlap with Ustaad | Link |
|---|---|---|---|---|
| 2026-08-02 | arXiv 2608.02341, Broadcast Rate Limits in Wi-Fi for collaborative edge LLM inference | 802.11 broadcast capped near 54 Mbps; UDP-broadcast method 1.4x over NCCL/TCP on a wired 8-node cluster; Wi-Fi side is NS-3 simulation (S) | Same problem family (Wi-Fi as the bottleneck for multi-device LLMs); no phones, no Android | https://arxiv.org/abs/2608.02341 |
| 2026-07-20 | Google, Nearby Connections API change (Android blog) | API stops auto-enabling Wi-Fi and Bluetooth for first- and third-party apps, effective late 2026; names no mediums (V) | Nearby is the vendor-supplied alternative to Ustaad's own link. Its Android implementation is closed and changes without app updates (a reverse-engineering paper says so, S) | https://android-developers.googleblog.com/2026/07/upcoming-changes-nearby-connections-api.html |
| 2025-10-15 | IETF draft-ietf-httpapi-idempotency-key-header-07 (expired Apr 2026) | Idempotency-Key header makes POST/PATCH retry-safe (S) | Same semantics as Ustaad's unique request id plus server deduplication | https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/ |
| 2022-01-11 (priority 2018-10-12) | Samsung US11223929B2, Electronic device for determining P2P operating channel | Group owner picks the Wi-Fi Direct channel from its own and the peer's AP-connection status; sets it to the AP's 5 GHz channel to avoid multi-channel concurrency (V description; claims not read) | Same idea as pinning the group to the station channel. Inputs are connection state and stored preference channels, not throughput measurement | https://patents.google.com/patent/US11223929B2/en |
| 2019-04-23 (priority 2015-10-13) | Microsoft US10271336B2, Smart channel selection for autonomous group initiators | Initiator picks the group channel members most likely use for their WLAN link, so one channel serves both; example criterion is strongest signal measured at the initiator (V description; claims not read; listed active) | Same mechanism, including a measured selection criterion | https://patents.google.com/patent/US10271336B2/en |
| class since API 29; method level not confirmed | Android WifiP2pConfig.Builder setGroupOperatingFrequency / setGroupOperatingBand | Group owner is limited to the given frequency; fails if it cannot form there (S) | The platform API Ustaad needs to pin a channel | https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder |
| 2018 | TU Darmstadt thesis on Nearby Connections 2.0 | Nearby beat Bluetooth/BLE but fell far below plain Wi-Fi Direct (S) | Supports building an own link; old, Play services since changed | https://www.seemoo.tu-darmstadt.de/theses/Combining-WiFi-Bluetooth-and-BLE-Limitations-and-synergy-effects-of-using-Google-Nearby-Connections |
| 2017 on | Google Nearby Connections / Quick Share (library, Android) | Discover over BLE/Bluetooth/Wi-Fi, then peers exchange supported mediums and upgrade to the fastest; README tables list Wi-Fi Direct, hotspot, Wi-Fi LAN, Wi-Fi Aware, WebRTC (V README: "share a list of all supported mediums and attempt to upgrade ... eg. BT -> Wi-Fi") | Rendezvous over a low-bandwidth link then credential hand-over to a fast link: same shape as Ustaad. A third-party reading of the code says the upgrade message carries the network details over the existing link (S) | https://github.com/google/nearby/tree/main/connections , https://android-developers.googleblog.com/2017/07/announcing-nearby-connections-20-fully.html |
| 2015-08-04 (priority 2013-03-14) | Qualcomm US9100942B2, WiFi Direct connection using machine-readable labels | QR code on one device conveys its P2P capability, listen channel, operating channel, GO/client role; peer scans and shortens setup (V description; expired-fee status) | QR-initiated Wi-Fi Direct pairing | https://patents.google.com/patent/US9100942B2/en |
| 2013, 2017, 2020 | Multipath TCP (RFC 8684): iOS since 2013 and all apps from 2017; Linux upstream from 5.6 | Several paths under one TCP connection, with backup subflows (S) | Transport-layer multipath on phones. Ordinary Android apps were not found to get it (U), and it does not cover BLE, so cross-radio fallback must sit in the app | https://mptcp.dev/ , https://www.phoronix.com/news/Linux-5.6-Starts-Multipath-TCP |
| long-standing | Idempotent receiver pattern; client-generated keys; duplicate-in-flight returns "pending" (S) | Receiver stores the first outcome per key | "Executed once" after a retry on another path is this pattern. Exactly-once delivery is not claimed anywhere; at-least-once plus dedup gives effectively-once | https://martinfowler.com/articles/patterns-of-distributed-systems/idempotent-receiver.html |

Overlap in one sentence: channel pinning, QR-started Wi-Fi Direct, low-bandwidth rendezvous with credential hand-over, and idempotent retries are each documented or patented by others; what I did not find is an app that does all four for phone-to-phone model offload with a measured fault-injection result.

### 1.2 Runtime: llama.cpp child processes in the APK, Adreno OpenCL, CPU fallback

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2026 (S) | Off Grid (MIT, Android APK) | Offline LLM app; Android uses OpenCL for LLMs, acceleration optional with automatic CPU fallback; reported speeds are from 8 Gen 2/3 class devices (S) | Same runtime recipe in a consumer app | https://www.mintlify.com/alichherawalla/off-grid-mobile-ai/technical/hardware-acceleration |
| 2024-11 | Qualcomm / llama.cpp OpenCL backend for Adreno | Official backend; verified-device table in mirrors lists Adreno 750 and 830, not Gen 5 (S) | The GPU path Ustaad uses | https://www.qualcomm.com/developer/blog/2024/11/introducing-new-opn-cl-gpu-backend-llama-cpp-for-qualcomm-adreno-gpu |
| 2019 | Briar: package binaries as lib*.so, exec from the read-only native library dir (target SDK 29 forbids exec from app data) (S) | Standard workaround for shipping a native executable in an APK | The "server binaries run as child processes" packaging | https://code.briarproject.org/briar/briar/-/issues/1507 |
| earlier | PocketPal AI, MLC Chat, Layla (see report 06) | Single-device offline chat | Baselines, not rivals | report 06 |

### 1.3 Scheduler and multi-phone inference (parts 3 and 6 share most of this)

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2026-08-02 | arXiv 2608.02341 | see 1.1 | collaborative edge LLM over Wi-Fi | https://arxiv.org/abs/2608.02341 |
| 2026-07-08 | Voltron, arXiv 2607.07046 | Elastically uses several nearby user devices for LLM inference; up to 16.5% higher accuracy than a single-device model under QoS limits (V abstract). Device types, link and partitioning not visible to me (U) | Closest 2026 academic claim to "pool nearby devices"; may include phones | https://arxiv.org/abs/2607.07046 |
| 2026-04 | Tether QVAC SDK | P2P (Holepunch) model distribution and delegated inference from a phone to a desktop; swarm inference described as planned (S, company claims) | Phone offload exists; no multi-phone pooling found | https://letsdatascience.com/news/tether-launches-qvac-mobile-ai-framework-c7bcd2cd |
| 2026 (date U) | edge-llm-cluster (MIT) | Layer shards sized to each device's memory; mDNS/NsdManager discovery; TCP frames; Android worker is "planned" (Phase 4); failure handling planned; targets are goals not measurements; 0 stars (V) | Same goal; not shipped on Android | https://github.com/meliharik/edge-llm-cluster |
| date U | android-llm-cluster (pinale) | llama-server on each Termux phone behind a load balancer on a PC/Pi; wired Ethernet; static IPs; token/s figures are estimates; manual recovery; unauthenticated (V page) | Same as Ustaad's batch runner (data-parallel spreading) but no discovery, no failover, no measurements | https://github.com/pinale/android-llm-cluster |
| date U | termux-llamacpp (PyPI) | Claims RAM pooling across phones over llama.cpp RPC via "AMEVA-Cluster" (S, listing only) | Memory pooling claim; no independent report | https://pypi.org/project/termux-llamacpp/1.4.0/ |
| 2026-03 (ICLR) | prima.cpp, arXiv 2504.08791 | Pipelined ring over heterogeneous home devices, Wi-Fi 320 to 610 Mbps, Android and HarmonyOS via Termux, 30 to 70B | Splits a model that does not fit one device across phones/tablets. Closest prior art for MemoryCloud | https://arxiv.org/html/2504.08791v3 |
| 2025-11 | Parallax (Gradient) | Pipeline-parallel P2P serving, desktops/GPUs (report 06) | Pipeline precedent | https://github.com/gradientHQ/parallax |
| 2024-2025 | exo | README today lists macOS and Linux only, phones not mentioned (V); 2025 coverage listed iOS/Android and a 2026 review says the iOS build was pulled (S) | Closest product concept | https://github.com/exo-explore/exo |
| 2024-08 (arXiv 2023-12) | LinguaLinked, ACL 2024 demos | Splits an LLM into subgraphs across Android phones with a runtime load balancer; 1.11 to 1.61x single-thread, 1.73 to 2.65x multi-thread vs its baseline; tested on high-end to low-end Android (S). Code link not found (U) | The academic "several Android phones, one LLM" result | https://aclanthology.org/2024.acl-demos.16/ |
| 2025-02-27 pub. (priority 2023-08-22) | IBM US20250071069A1 (granted as US12273278B2) | Splits an AI model into input, intermediate, output blocks placed across edge nodes and cloud by resource utilisation; wireless/MEC in dependent clauses (V description; claims not read) | Layer-block placement across devices in a patent; cloud-plus-edge, not phone-to-phone | https://patents.google.com/patent/US20250071069A1/en |
| 2023-2024 | Petals, distributed-llama, llama.cpp RPC | see report 03 | | report 03 |

### 1.4 Vendor cross-device AI

All rows are (S) news or marketing level unless noted; none was found to split one model or pool memory across several phones.

| Vendor | What was found | Link |
|---|---|---|
| Huawei HarmonyOS 7 (2026) | Agent framework, soft bus, "super device" capability pooling; AI described as device-cloud split; cross-device agent execution is a plan reported by a secondary outlet | https://wallstreetcn.com/articles/3774518 , https://www.huaweicentral.com/huawei-ceo-reveals-harmonyos-claw-ai-agent-features/amp/ |
| Xiaomi HyperOS 3 / HyperAI (2026) | HyperConnect device continuity (cross-device camera, apps, clipboard); HyperAI "works across devices"; no distributed-model source found | https://www.techrepublic.com/article/news-xiaomi-hyperos-ai-os/ |
| vivo OriginOS 6 (2025-26) | Office Kit and Connection Center: file transfer, handoff, mirroring; on-device 3B multimodal model per phone; no multi-phone pooling found | https://www.fonearena.com/blog/466406/vivo-originos-6-features-devices.html |
| Samsung Galaxy AI / Knox Matrix | Hybrid on-device and cloud; Knox Matrix is cross-device security with a "Trust Chain" private blockchain, not compute; Samsung says compute-splitting research is within one device | https://news.samsung.com/in/ai-leadership-%e2%91%a0-revolutionizing-everyday-devices-using-on-device-ai |
| Apple (WWDC26) | On-device plus Private Cloud Compute; conversation sync through iCloud; distributed inference exists only as an MLX developer feature for Macs (source page for the MLX remark not retained, U) | https://callstack.com/blog/on-device-ai-after-wwdc-2026-whats-new |
| Google | Nearby / Quick Share (above); Cross Device SDK developer preview 2022 (discovery, secure connection, session handoff; current status unknown, U); Pixel Magic Cue is on-device and not cross-device | https://android-developers.googleblog.com/2022/07/announcing-cross-device-SDK-Developer-Preview-for-Android.html , https://smartprix.com/bytes/magic-cue-googles-new-pixel-10-feature-provides-super-personal-ai-assistance-on-the-go |
| Qualcomm | Snapdragon Seamless (2023) is device discovery/sharing; Summit 2026 pushes agentic AI and 30B+ MoE on one device; no cross-device inference found | https://www.abiresearch.com/blog/snapdragon-summit-2026-qualcomm-aagentic-ai-strategy |

Risk to flag: vivo/iQOO ships the hardware and the OS. A vendor could ship this; nothing found says it has.

### 1.5 Agent Choir: same small model, different roles, exchange answers, tool-checked labels, equal-compute baseline

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2026-07 | "When LLMs Agree, Are They Right?" (alphaXiv 2607.08065) | Argues self-agreement can encode shared bias; vote share is not a calibrated probability (S) | Supports keeping CONSISTENT separate from VERIFIED | https://www.alphaxiv.org/abs/2607.08065.md |
| 2026-05-02 | Wunderlich et al., arXiv 2605.01566 (ACL 2026 SRW) | 34 configurations on MMLU-Pro and BBH; at equal compute debate beats self-consistency by 1.3 points and mixture-of-agents by 2.7 points (V abstract) | Equal-compute comparison is standard in the literature | https://arxiv.org/abs/2605.01566 |
| 2026-01 | "Can Small Agents Collaborate to Beat a Single LLM?" (2601.11327) | Orchestrated small models can match larger single agents on GAIA (S) | Small-model ensembles | https://arxiv.org/html/2601.11327v2 |
| 2025-2026 | Patent US20250117757A1; Amazon WO2026005910A1 | Multi-agent answers with intermediate validation and consensus (S); LM agents exchanging messages, mediator/delegate roles, user device plus system components (V partly) | Role-tagged agents exchanging answers; consensus plus validation | https://patents.google.com/patent/US20250117757A1/en , https://patents.google.com/patent/WO2026005910A1 |
| 2024-12 | Mitra et al., arXiv 2412.21200, Distributed Mixture-of-Agents for Edge Inference | LLMs on individual edge devices exchange prompts by decentralised gossip, queueing analysis, AlpacaEval; no tool verification stated (V abstract; devices unspecified) | Closest to "each device runs a model and they combine" | https://arxiv.org/abs/2412.21200 |
| 2024-12 | PROVE (arXiv 2410.12608) | Executes programs translated from solutions and drops mismatching paths before voting; gains up to 18% over majority vote (S) | Execution-based check before aggregation | https://arxiv.org/pdf/2410.12608 |
| 2024-10 | CAMPHOR (arXiv 2410.09407) | On-device SLM multi-agent framework for personal context; hierarchical delegation, not voting (S) | On-device multi-agent | https://arxiv.org/pdf/2410.09407 |
| 2024-06 | Wang et al., arXiv 2406.06461, Reasoning in Token Economies | Budget-aware evaluation; self-consistency at comparable compute frequently beats published strategies; debate and Reflexion can worsen with more compute (V abstract) | The equal-compute baseline argument itself | https://arxiv.org/abs/2406.06461 |
| 2024 | Smit et al., ICML 2024, Should we be going MAD? | Multi-agent debate does not reliably beat self-consistency or ensembling (S) | Same | https://proceedings.mlr.press/v235/smit24a.html |
| 2024 / ICLR 2025 | Mixture-of-Agents (Wang et al.) | Proposer models plus aggregator; authors note it can be extended with verification (S) | Role/ensemble structure | https://proceedings.iclr.cc/paper_files/paper/2025/file/5434be94e82c54327bb9dcaf7fca52b6-Paper-Conference.pdf |
| 2022-2023 | CodeT (ICLR 2023), AlphaCode (2022) | Candidates executed against tests; agreement between candidates used as a second signal; AlphaCode filters by example tests (S) | Tool-checked selection and agreement as two separate signals | https://arxiv.org/pdf/2207.10397 , https://arxiv.org/pdf/2203.07814 |

What I could not find: a paper or product that names two user-visible tiers, one "checked against the problem's own test" and one "only the model's own arithmetic adds up", or that renders an equal-compute single-device baseline next to the ensemble in the UI (terms in section 5). The underlying distinction (verification vs consensus) is already in the literature, so only the product presentation is open.

### 1.6 Private Data Union: per-phone indexes, text query out, short snippets back, citations, private by default

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2026-08-02 | DEFRAG, arXiv 2608.00922 | Edge devices share compressed knowledge graphs for RAG; testbed hardware and privacy mechanism not stated (V abstract) | Cross-device RAG without a cloud | https://arxiv.org/abs/2608.00922 |
| 2026-05-22 | FD-RAG, arXiv 2605.27432 | Each device distils its corpus into QA memories; anonymised memories are aggregated, raw documents stay local; access control not mentioned (V abstract) | Private cross-device retrieval; shares memories, not snippets | https://arxiv.org/abs/2605.27432 |
| 2026-09 | "Beyond Raw Context Transfer: Representation-based Federated RAG" (2609.22162) | Title only seen (S) | Federated RAG, newest | https://arxiv.org/pdf/2609.22162 |
| 2026-07 | Glyfen (F-Droid) | On-device OCR (PaddleOCR) with keyword search (SQLite FTS5) over chosen folders (S) | On-device OCR index, single phone | https://f-droid.org/en/packages/dev.glyfen.app/ |
| 2025-05-01 | DRAG, arXiv 2505.00443 | Each peer keeps a private store and local LLM; topic-aware walk finds peers; the asker collects "privacy-filtered" excerpts and answers locally; near-centralised accuracy with fewer messages (V abstract; excerpt format from secondary summaries, S) | **Closest prior art for PDU**: peer-to-peer, snippets returned, asker synthesises. Citations and access control not described (U) | https://arxiv.org/abs/2505.00443 |
| 2025-05 | Federated RAG mapping study, arXiv 2505.18906 | Survey of federated RAG (S) | Context | https://arxiv.org/pdf/2505.18906 |
| 2025-26 | Pixel Magic Cue; Google Personal Intelligence | Magic Cue is on-device, recent activity only, not cross-device; Personal Intelligence runs in the cloud on Gmail/Photos (S) | Vendor personal-context search | https://www.techrepublic.com/article/news-google-biggest-announcements-2026/ |

Not searched at source level (U): "request access, owner is notified" flows in document-sharing apps. They are common, so the notification piece should be described as a policy choice, not an invention.

### 1.7 MemoryCloud: lending memory, lease, split inference over wireless

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2023-06 | Memtrade (SIGMETRICS 2023, arXiv 2108.06893) | VMs lease idle memory to remote consumers for a limited time; broker, price, harvesting (S) | Lease-based lending of spare memory, in data centres | https://arxiv.org/abs/2108.06893 |
| 2023-2026 | prima.cpp, LinguaLinked, llama.cpp RPC, distributed-llama (reports 03 and above) | Model split across devices; RPC README: "fragile and insecure", never expose to open networks | The split itself; RPC has no lease | https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md |
| patent | IBM RDMA cache management US9946684 | Time-limited lease lets a remote client read a host's data until expiry (S) | Lease as a time-bounded right over remote memory | https://patents.google.com/patent/US9946684 |
| 1989 | Gray and Cheriton, Leases (SOSP 1989) | Time-based grants; failures cost performance, not correctness | The lease concept itself | https://www.andrew.cmu.edu/course/15-440/assets/READINGS/gray1989.pdf |

Honest-slower result: report 03 already predicted that a pipeline across phones buys capacity, not speed; a 4B split at 6.9 tok/s slower than one phone is consistent with that, not a finding about prior art.

### 1.8 Audit and provenance

| Date | Item | What it does | Overlap | Link |
|---|---|---|---|---|
| 2026-09 | IETF draft-stone-aivs-01 | SHA-256 hash-chained audit log plus Ed25519 signature bundle for AI verification (S) | Same technique, interoperable format | https://www.ietf.org/archive/id/draft-stone-aivs-01.html |
| 2026 | agentproofs, arcaeon-ledger, halo-record | Local hash-chained, signed agent action logs, one JSONL file or daemon (S, licences unchecked) | Same technique for agents | https://socket.dev/npm/package/agentproofs/overview/1.0.0 , https://socket.dev/pypi/package/arcaeon-ledger , https://pypi.org/project/halo-record/0.2.7/ |
| 2026-06 | AuditWeave, arXiv 2607.09682 | Integrity guarantees at tens of microseconds per event; mutations detected (S; date inconsistent in source) | Same | https://arxiv.org/abs/2607.09682 |
| 2026-04-08 | Aegon, arXiv 2604.06693 | Android StrongBox-signed receipts; Merkle-tree ledger inspired by Certificate Transparency (V abstract) | Hardware-attested Android receipts; a stronger trust anchor than a software hash chain | https://arxiv.org/abs/2604.06693 |
| 1998-1999 | Schneier and Kelsey, Secure Audit Logs to Support Computer Forensics (USENIX 1998; ACM TISSEC 1999) | Forward-secure tamper-evident logs on untrusted machines (S) | The foundational prior art for tamper-evident per-machine logs | https://www.schneier.com/paper-auditlogs.html |

A chain proves records were not changed after the fact; it does not prove completeness, and an attacker with write access can rewrite the whole chain unless signed or anchored elsewhere (point made by the project pages above, S). Do not call the log tamper-proof.

### 1.9 The combination

No source found combines more than two of the seven parts in one shipped Android app. Nearest assemblies: prima.cpp (inference over phones/tablets, Termux, no UI, no private data, no verification); edge-llm-cluster (Android worker planned); android-llm-cluster (Termux phones behind a PC, wired); Nearby/Quick Share (transport only); DRAG (retrieval only, hardware unreported, U); exo (Macs). "One consumer APK, no root, no cloud, on a vendor phone" is not a feature of any of them.

## 2. Novelty matrix

Verdicts: NOVEL (no close prior art found) / NOVEL COMBINATION / INCREMENTAL / KNOWN.

| # | Mechanism | Closest prior art | Same | Different | Verdict |
|---|---|---|---|---|---|
| 1 | Wi-Fi Direct group pinned to the station's own channel | Microsoft US10271336B2; Samsung US11223929B2 | Group channel set to the channel of the other (infrastructure) link to avoid multi-channel operation; Microsoft already uses a measured criterion | Ustaad logs an A/B throughput result on iQOO 15 (640 to 920 vs 10 Mbps, per brief). Android phone measurements of this were not found | KNOWN (mechanism). The measurement is a data point, not a claim of invention |
| 2 | QR pairing into a Wi-Fi Direct group | Qualcomm US9100942B2 (2013) | QR conveys P2P parameters to start Wi-Fi Direct | Ustaad's QR may also carry app credentials (U, repo not read) | KNOWN |
| 3 | Bluetooth for rendezvous and credential hand-over | Nearby Connections bandwidth upgrade | Low-bandwidth link carries the details for the fast link | Ustaad builds this without Play services | KNOWN |
| 4 | Bluetooth control path when Wi-Fi is down, plus retry on another path executed once (700 of 700) | Nearby (fallback behaviour unverified, U); idempotent receiver; MPTCP | Request id, server dedup, retry | Cross-radio (Wi-Fi Direct, LAN, Bluetooth) at the app layer for model-offload requests, with authenticated responses and a fault-injection result | INCREMENTAL (engineering result on standard semantics) |
| 5 | llama.cpp servers in the APK as child processes; Adreno OpenCL with automatic CPU fallback | Off Grid; Briar exec workaround | Same recipe | Packaged with the rest of Ustaad | KNOWN |
| 6 | Any phone runs a request on any other; batch runner spreads jobs (about 2x on two phones) | android-llm-cluster; LinguaLinked; llama.cpp RPC | Data-parallel request spreading | Phone-to-phone without a PC orchestrator, with discovery and failover | INCREMENTAL |
| 7 | Same small model, different roles, exchange short answers | Mixture-of-Agents; multi-persona debate; Distributed MoA for edge (2412.21200) | Whole pattern | Run on separate phones in one app | KNOWN |
| 8 | VERIFIED only when checked against the problem's own test; CONSISTENT when only the model's own arithmetic adds up | CodeT, AlphaCode, PROVE; self-consistency; 2607.08065 | Tool-checked selection and agreement are known as separate signals | A user-visible two-tier label with these exact semantics: none found (terms in 5) | NOVEL COMBINATION (presentation of a known distinction) |
| 9 | Equal-compute baseline (one phone sampling three times) shown beside the ensemble in the product | Wang 2024; Wunderlich 2026; Smit 2024 | Equal-compute comparison is the accepted method | Shown live in a product UI: none found (terms in 5) | NOVEL COMBINATION (concept KNOWN, placement in product open) |
| 10 | Per-phone indexes (OCR incl. Hindi), query out as text, short snippets back, asker answers | DRAG 2505.00443; FD-RAG; DEFRAG | Peer-to-peer retrieval with local synthesis | Real phones, OCR, Hindi | INCREMENTAL |
| 11 | Private-by-default folders; per-phone, per-file citations; peer can only raise a notification to open a file | DRAG (privacy-filtered excerpts; citations not described) | Privacy filter at the peer | Citation granularity and owner-notification open: none found in the retrieval literature or patents (terms in 5) | NOVEL (search-limited; low technical weight, closer to policy design) |
| 12 | Split inference over phones to run a model that does not fit one phone (4B at 6.9 tok/s shown; 30B on three phones pending) | prima.cpp; LinguaLinked; distributed-llama 30B on Pis; llama.cpp RPC | Entire technique | Consumer APK, no root | KNOWN |
| 13 | Lease-based memory lending: RPC worker under a lease the driver must renew, bound only to the link in use | Memtrade (leases, data centre); IBM RDMA lease; llama.cpp RPC (no lease) | Lease concept; remote memory; split inference | Lease plus single-interface binding for split LLM inference on phones: none found (terms in 5) | NOVEL COMBINATION |
| 14 | Hash-chained audit log on every phone | Schneier-Kelsey; Certificate Transparency; AIVS; agentproofs; Aegon | Technique | Applied to per-phone distributed AI activity | KNOWN |
| 15 | Live "what this phone is doing" line | not searched at source level | | | INCREMENTAL (U) |
| 16 | One consumer Android app combining 1 to 15, no cloud, no root | prima.cpp + Termux; edge-llm-cluster; Nearby; DRAG | Each piece | The assembly on one measured link on vendor phones | NOVEL COMBINATION (search-limited) |

## 3. Statements

### Say on stage (each is true only with its caveat)

1. "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root." Caveat: each part has prior art; this is the combination, it is search-limited, and the 30B-on-three-phones piece stays "pending" until measured.
2. "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Caveat: tool-checked selection, self-consistency and equal-compute comparisons are published methods; the claim is the product presentation, and it holds only for problems that have a deterministic checker.
3. "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file." Caveat: peer-to-peer snippet retrieval is published (DRAG, 2025); the claim is this attribution and consent behaviour shipped on phones, for which I found no prior description.

### Avoid (with the prior art that contradicts each)

1. "First/only way to run one model across several phones." Contradicted by LinguaLinked (ACL 2024), prima.cpp (ICLR 2026, Android via Termux), Voltron (July 2026), edge-llm-cluster and exo's 2024-25 demos (links in 1.3).
2. "We invented pinning the Wi-Fi Direct group to the station channel / QR pairing / Bluetooth hand-over." Contradicted by Microsoft US10271336B2 (priority 2015), Samsung US11223929B2 (2018), Qualcomm US9100942B2 (2013) and Nearby Connections. Say "we measured it on these phones: 640 to 920 Mbps on the station channel, 10 Mbps off it" (brief numbers).
3. "A new tamper-proof audit log" (and, related, "first private cross-device retrieval"). Contradicted by Schneier and Kelsey 1998/99, AIVS, agentproofs, Aegon; DRAG 2025 and FD-RAG 2026 for retrieval. Also say "executed once", not "exactly-once delivery"; idempotent receivers give the first, and delivery cannot be guaranteed in general.

## 4. Patentability (general information, not legal advice)

Candidates, two lines:
- Lease-bound memory lending for split LLM inference: a worker whose life is tied to a driver-renewed lease and to the single active link. Leases and split inference are old, but no patent or paper combining them on phones was found; it solves a technical problem (no memory served to a peer that has gone), though obviousness is a serious risk.
- Request-id deduplication spanning several phone-to-phone transports with authenticated responses for model offload. There is a measurable technical effect (no duplicate GPU work after a path switch), but idempotency is broad prior art, so this is weak.
- Not patentable-looking: verification labels and the equal-compute display (presentation of a known method), PDU snippet protocol (DRAG), audit hash chain, channel pinning (others' patents).

Freedom to operate: Microsoft US10271336B2 is listed active (adjusted expiry 2035) and Samsung US11223929B2 active (estimated expiry 2039); I did not read their claims. Ustaad's channel pinning should be reviewed against them by a patent attorney before any commercial launch or investor diligence.

Public demonstration and grace periods:
- India: no general grace period. Section 31 of the Patents Act, 1970 gives 12 months only for display at an exhibition the Central Government has notified, descriptions arising from it, and papers before a learned society; since 15 March 2024 the benefit is claimed with Form 31 under Rule 29A. A hackathon demo or pitch is not described in these categories (secondary sources: https://www.intepat.com/blog/grace-period-under-section-31-of-the-patents-act-in-india , https://asiaiplaw.com/section/ip-analysts/when-does-public-display-become-prior-art-anticipation-under-the-patents-act-1970). Authoritative text: the Act, consolidated to 2023, at WIPO Lex https://www.wipo.int/wipolex/en/legislation/details/22960 and the Indian Patent Office acts page https://ipindia.gov.in/acts-patents.htm. I could not extract the statutory wording from the PDF, and the law-firm sources agree with each other but are secondary (U for exact clause text, including section 32 on public working for reasonable trial).
- European Patent Convention Article 55: six months, only for evident abuse or an officially recognised international exhibition (V: https://www.epo.org/en/legal/epc/2020/a55.html). So there is no general grace period at the EPO. China was not checked (U).
- United States: one year for inventor-originated disclosures, 35 U.S.C. 102(b)(1) (https://www.uspto.gov/web/offices/pac/mpep/s2153.html, S).
- Practical reading: if a patent route is wanted, an Indian provisional application filed before further public disclosure (the finale runs to 2026-10-11, per report 06) preserves the options; check what was already made public (public repository, earlier pitches, the hackathon submission).
- India also excludes "computer programme per se" (Section 3(k)); the Indian Patent Office is reported to have replaced its 2017 computer-related-invention guidelines with a 2025 set on 29 July 2025, still tied to technical effect (S: https://www.aippi.org/news/decoding-cri-guidelines-2025/). I did not read the 2025 text (U).

## 5. Search log (terms tried; absence is not proof)

- Multi-phone inference: "distributed LLM inference across multiple Android smartphones over Wi-Fi 2026 arXiv"; "exo labs Android phones support distributed inference 2026"; "GitHub run LLM across multiple Android phones llama.cpp RPC cluster app phones pool RAM"; "Show HN pool phones into one LLM cluster Android app Wi-Fi Direct offline 2026"; "Tether QVAC distributed inference multiple phones pooled model 2026".
- Vendors: HarmonyOS soft bus / super device AI 2026; Xiaomi HyperOS HyperConnect distributed AI; vivo OriginOS 6 cross-device AI; Qualcomm Snapdragon Seamless agents; Samsung Galaxy AI Knox Matrix multiple devices; Apple Intelligence multiple devices; Google Cross Device SDK; a Chinese-language query on multi-device compute sharing for on-device large models.
- Link: Nearby Connections bandwidth upgrade internals; WifiP2pConfig setGroupOperatingFrequency; Wi-Fi Direct SCC/MCC same channel; patent queries on Wi-Fi Direct GO channel equal to STA channel; Idempotency-Key draft; multipath transport smartphone Wi-Fi Bluetooth retry; MPTCP Android status.
- Patents (Google Patents via search, claims not read): distributed LLM inference across mobile terminals over WLAN; memory lending lease neural-network split nearby devices; multi-agent answers tool check consistency label; layer-split with intermediate output to a nearby device; private retrieval across personal devices with owner notification (nothing close found for the last two).
- Agent Choir: multi-agent debate vs self-consistency equal compute; mixture-of-agents on-device consensus verification unit test; "verified by execution" vs "self-consistent" label; CodeT / AlphaCode.
- PDU: federated RAG personal devices snippets; decentralised peer-to-peer RAG each device indexes own documents citations; on-device OCR search vendor features.
- MemoryCloud: Gray and Cheriton leases; memory disaggregation lease; Memtrade.
- Audit: hash-chained tamper-evident audit log AI agent 2026; Schneier Kelsey secure audit logs.
- Runtime: llama.cpp OpenCL Adreno CPU fallback app; native executable in jniLibs and exec restriction.
- Grace periods: India Sections 31 and 32; WIPO grace period compilation (PDF unreadable to my fetcher); Article 55 EPC; MPEP 2153; CRI guidelines.

## 6. Not verified / open

- Claims of every patent named; any patent filed after about April 2025 is not yet public.
- Voltron's devices, link and results (PDF unread); LinguaLinked code availability; edge-llm-cluster and android-llm-cluster dates.
- Whether Nearby Connections falls back to Bluetooth when Wi-Fi paths drop, and whether Play services behaves differently on OriginOS (closed source).
- Exact statutory text of Indian Sections 31 and 32; whether any earlier Ustaad disclosure is already public; grace-period rules outside India, EPO and the US.
- Whether the QR in Ustaad carries more than P2P parameters (repo not read, per brief).
- Vendor roadmaps: nothing found does not mean nothing planned.
