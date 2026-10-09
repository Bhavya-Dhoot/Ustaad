# 06 - Hackathon context and competitive landscape

Research date: 2026-10-09. Sources are web pages fetched or returned by search this session; confidence labels: (V) verified on a fetched page, (S) search-result snippet only, (U) unverified/inferred.

## 1. iQOO Hackathon 2026

Found. Organizer: iQOO x Reskilll, "phone-first, hybrid development model" (V: https://reskilll.com/blogs/iqoo-hackathon-2026-india-phone-first-ai-hackathon-iqoo-reskilll/). Official registration site: https://iqoo.reskilll.com/ (fetch returned only title/tagline; details below come from the Reskilll blog). Other coverage: https://community.iqoo.com/in/thread/167067 , https://www.itvoice.in/iqoo-announces-indias-phone-first-hackathon-series-challenging-young-innovators-to-build-ai-powered-solutions-for-real-world-problems-on-mobile , https://techgenyz.com/iqoo-hackathon-2026-city-battles/

| Item | Finding |
|---|---|
| Prize pool | Rs 40,00,000. City battles Rs 6L each (x4 = Rs 24L); finale = remainder (Rs 16L per the iQOO community post; the Reskilll blog says "remaining pool", amount not given). |
| Schedule | Bengaluru Aug 29-30, Pune Sep 5-6, Chennai Sep 12-13, Hyderabad Sep 26-27 (30 h each). Grand Finale Bengaluru Oct 9-11, 2026, 48 h. The finale starts today. |
| Finale entry | Top 6 per city (3 student + 3 professional) = 24 teams, plus direct registrations. |
| Team | Solo or up to 3. Students and working professionals are separate buckets; no mixing. Free entry. India only (students from any Indian college). |
| Tracks (7) | FinTech and Commerce; Smart Education; HealthTech; Productivity; Smart Living (IoT/connected devices); Developer Tools; Open Innovation ("anything with a local or open-source model at the core"). |
| Judging | End product quality 30% (jury); Novelty and impact 20% (jury); Creative phone use (camera, voice, on-device AI) 15% (HackTracker device data); Technical depth 15% (jury); Office Kit usage 10% (HackTracker device data); Demo and presentation 10% (jury). So 25% of the score is measured from device telemetry. "A well-built simple product beats a broken complex one." |
| Bonus | A local or open-source model at the core earns extra points in every track. Cloud-only APIs are discouraged. |
| Build rules | 55% "Red Light" (phone only, no laptop), 45% "Green Light" (phone + laptop). The demo must run on the iQOO phone; web-only apps that ignore phone features lose points. |
| Device | Participants get an iQOO flagship phone as a loaner at Saturday check-in (V on Reskilll blog; the iQOO community post is vague). The blog names iQOO 15 and its Snapdragon NPU. |
| Tech named | Snapdragon NPU on-device AI (examples: Phi-3, Gemma 2B, Whisper Tiny), Office Kit (phone-laptop bridge: screen mirror, clipboard, file transfer, remote control; laptop client from pc.vivoglobal.com), free inference credits for the weekend. |
| Frameworks / Android-native | Not stated. The page does not say Android-native is required, nor forbid any framework. (U) Practical reading: anything that runs on the phone is acceptable; confirm with organizers. |
| Required vivo SDKs | None found. Office Kit is a scored item, not a documented mandatory SDK. No BlueLM/OriginOS API requirement found. |
| Submission format | Not specified. Process: register, pick city, submit idea for screening, get shortlisted, on-site demo and presentation. No repo/video/deck format documented. |
| Support | 10-15 mentors, 5-person industry jury, meals on event days. |
| Conflicts | Registration deadlines differ between listings (30 Aug vs 27 Sept); direct finale registration status must be checked on the official site. |

Not found: the HackTracker tool's definition (what it measures, whether it is an installed app/agent), finale-specific prize split, special awards, a formal rulebook. Searches run: "iQOO hackathon 2026" (extended). Did not separately search vivo/iQOO Quest or Z/Neo variants because the first query returned the event.

Judging-relevant implication (inference): 25% of the score is phone-telemetry-driven and 10% is specifically Office Kit usage; a laptop-only or cloud-heavy design is penalized. Verify with organizers how HackTracker detects "creative phone use" and "Office Kit usage" before the demo.

## 2. The phone

- iQOO I2501 is the iQOO 15 (V: GSMArena lists model numbers I2501, V2505A: https://www.gsmarena.com/vivo_iqoo_15_5g-14198.php ). I2501 is described as the global/India variant by a retailer (S). PD2505F_EX appears as the firmware family name (an article cites build PD2505F_EX_A_16.0.18.4.W30, S: https://reads.alibaba.com/your-new-iqoo-15-just-got-an-update-better-battery-faster-games/ ); no source explains the code, so treat PD2505F_EX as the iQOO 15 firmware/build ID, (U) not an independently confirmed retail name.
- Chipset: Snapdragon 8 Elite Gen 5, SM8850-AC, 3 nm, 2x4.6 GHz + 6x3.62 GHz Oryon, Adreno 840 (V, GSMArena). iQOO adds its own Q3 gaming chip (S, same Alibaba page / iQOO).
- RAM 12 or 16 GB LPDDR5X; UFS 4.1; Android 16 with OriginOS 6 (V, GSMArena). Note: the India variant is sold with OriginOS 6; "Funtouch OS 16" is the older/other skin name and was not verified for this unit.
- Wi-Fi: listed "Wi-Fi 802.11 a/b/g/n/ac/6/7, dual-band, Wi-Fi Direct" (V, GSMArena); 91mobiles lists Wi-Fi 7 (802.11be) (S: https://www.91mobiles.com/iqoo-15-price-in-india?ty=specs ). Wi-Fi Direct is listed, which matters for local peer links. Bluetooth version conflicts between sources (5.4 vs 6.0).
- Cooling: vapor chamber (S: https://nanoreview.net/en/phone-compare/vivo-iqoo-15-vs-motorola-edge-70 ; iQOO marketing claim of a large single-layer vapor chamber at https://community.iqoo.com/in/thread/124548 ). No active cooling. A 2026 paper reports thermal throttling as the primary constraint for sustained mobile LLM inference (S: https://arxiv.org/html/2603.23640v1 ).
- NPU: Qualcomm Hexagon NPU; no TOPS figure found in any source. Do not quote a TOPS number.
- BlueLM: vivo's own LLM family (BlueLM-Nano on-device, BlueLM-RealTime, cloud models); 2023 conference released a 7B open model, a ~1B on-device model and BlueKit toolkit (S: https://www.laoyaoba.com/n/883066 ). I found no current official third-party SDK for on-device BlueLM on OriginOS 6. (U) assume not available to us.
- Background policy: no official vivo developer documentation found for OriginOS 6 / Funtouch OS 16 foreground services. Community guidance: enable Settings > Battery > High background power usage, allow Autostart, add app to the "Background Running Apps" whitelist in iManager (S: https://dontkillmyapp.com/vivo , https://www.mintlify.com/ivangonzalezg/react-native-background-guardian/concepts/oem-restrictions ). dontkillmyapp says vivo restrictions are not fully mapped. Plan: foreground service with persistent notification, wake lock, battery-optimization exemption prompt, and test on the real device. Android 16 foreground-service rules apply in general (U, not checked against docs this session).
- Office Kit: built into OriginOS 6; screen mirroring, remote PC, file transfer, clipboard sync; Windows 10+ and macOS (S: https://community.iqoo.com/in/thread/126712 , https://vivonewsroom.in/?p=15612 ). Official pc.vivoglobal.com not confirmed.

## 3. Competitors and prior art (one line each)

| Name | What / platform / status | Direct competitor? |
|---|---|---|
| exo (exo labs) | Open-source (Apache-2.0) project that auto-discovers devices and shards models across them; GitHub README currently lists macOS (GPU) and Linux (CPU), RDMA over Thunderbolt 5, ChatGPT-compatible API, and says nothing about phones (V: https://github.com/exo-explore/exo ). A 2024 demo and coverage claimed iPhone/Android support (S: https://www.tomshardware.com/tech-industry/artificial-intelligence/new-software-lets-you-run-a-private-ai-cluster-at-home-with-networked-smartphones-tablets-and-computers-exo-software-runs-llama-and-other-models ). | Closest conceptual rival ("pool your devices"); not a phone-native product today. Third-party review says distributed inference adds capacity, not single-request speed (S: https://www.runaihome.com/blog/exo-framework-distributed-vram-local-ai-2026/ ). |
| Petals | Internet-scale collaborative inference by chaining volunteer GPUs (Hivemind/DHT), NeurIPS 2023 (S: https://proceedings.neurips.cc/paper_files/paper/2023/hash/28bf1419b9a1f908c15f6195f58cb865-Abstract.html ). Last repo push reported Sept 2024; health in 2026 unclear (S). | No: WAN, desktop GPUs, public swarm, not phones, privacy-different. Prior art only. |
| Parallax (Gradient) | Pipeline-parallel P2P serving across laptops/desktops/GPUs, launched Nov 2025 (S: https://github.com/gradientHQ/parallax ). | No on phones; relevant as "sovereign AI OS" framing and pipeline-parallel precedent. |
| distributed-llama (b4rtaz) | Tensor-parallel LLM over home devices (Linux/macOS/Windows, ARM/x86); Raspberry Pi 5 cluster benchmark 2.26 -> 4.56 t/s on 1 -> 4 Pis over Ethernet (S: https://cdn.jsdelivr.net/gh/b4rtaz/distributed-llama@main/README.md ). Android not listed. | Technical precedent; shows network-bound scaling. Not a phone product. |
| prima.cpp | Research: 30-70B inference on heterogeneous low-resource home clusters (S: https://arxiv.org/pdf/2504.08791 ). | Academic prior art, no phone product. |
| LinguaLinked | Academic (2023): distributed LLM inference across several Android phones (S: https://arxiv.org/pdf/2312.00388 ). | Closest academic demo of phone clustering; no productized app found. |
| Tether QVAC Fabric | Edge-first LLM runtime and LoRA fine-tuning across phones, laptops, servers, Dec 2025 (S: https://tether.io/news/tether-data-introduces-qvac-fabric-llm-the-edge-first-llm-inference-runtime-and-generalized-llm-lora-fine-tuning-framework-for-modern-ai-models-on-heterogeneous-gpus-smartphones-laptops-and-server/ ). | Partial: runs on phones; no evidence it pools several phones. |
| PocketPal AI | Open-source (MIT) Android/iOS app running GGUF models offline (V-ish: https://github.com/a-ghorbani/pocketpal-ai ). | No: single-device chat. Could be a component/baseline. |
| MLC Chat | MLC LLM demo app, Android/iOS, offline, Snapdragon-optimized (S: https://www.digit.in/features/general/how-to-run-offline-ai-llm-model-on-android-using-mlc-chat.html ). | No: single device; runtime alternative. |
| Layla | Paid offline assistant app (LLM + Stable Diffusion, agents), 8 GB+ RAM flagship (S: https://mwm.ai/apps/layla/6456886656 ). | No: single device consumer app. |
| Google AI Edge Gallery | Google's experimental on-device GenAI app on LiteRT, Gemma models, Android and iOS (S: https://www.techtarget.com/whatis/feature/Unlock-the-power-of-local-AI-with-Google-AI-Edge-Gallery ). | No: showcase for single-device inference; likely our runtime path (LiteRT). |
| Gemini Nano / AICore | System on-device model for third-party apps via ML Kit GenAI; limited device list (Pixel, Galaxy S24+) (S: https://developer.android.com/ml/aicore ). Not shown to be available on iQOO. | No; and likely unavailable on this phone (U). |
| Apple Private Cloud Compute | Apple Intelligence overflow to Apple-run secure servers (S: https://getcoai.com/news/apples-ai-privacy-strategy-and-how-it-stacks-up-against-android ). | No: phone-to-vendor-cloud, the opposite of device-to-device. Useful as the contrast/foil. |
| Samsung Galaxy AI / Quick Share | Hybrid on-device plus cloud; Quick Share is file transfer. No evidence of cross-device AI compute (S: https://www.samsung.com/au/members/whats-new/how-to-share-files-with-multiple-friends-at-once ). | No. |
| vivo Office Kit | Phone-PC bridge on OriginOS; scored in this hackathon (S above). | Complement, not competitor; use for the 10% score. |

Not found: any 2025-2026 startup shipping an Android app that clusters multiple phones into one LLM (searched "phones as cluster", "edge mesh AI", "Android pooled LLM"; one extended search). Absence is limited to what those searches returned; it is not proof none exists.

## 4. Judging-oriented positioning for SwarmOS (device-coordination layer + 4 modules)

Five framing bullets:
1. Lead with the demo, not the architecture: "N iQOO phones, no cloud, no internet, one task finished faster/with a bigger model than one phone can do," with a live visible mesh. Judges reward a working, simple product (30% product quality, 10% demo).
2. Map each feature to a scored line: local/open-source model at the core (bonus), on-device NPU/camera/voice use (15% telemetry), Office Kit as the operator console (10% telemetry), technical depth through scheduling, discovery and fault-tolerance (15%).
3. Pick one track and one concrete user story (e.g., Developer Tools, Productivity, or Open Innovation) and show the swarm solving it; do not claim all seven tracks.
4. Position as a coordination layer that sits above existing runtimes (LiteRT/llama.cpp/MLC-style), not a new model or inference engine; distinguishes us from exo/Petals (desktop, WAN) and from single-device apps (PocketPal, Layla).
5. Be candid about trade-offs on stage: pooling phones helps capacity, parallelism of many independent requests, resilience and privacy; it will not necessarily beat one strong phone on single-request latency. Showing a measured benchmark of both builds credibility and survives jury questions.

Three claims we must NOT make:
1. Any NPU TOPS figure or "NPU-accelerated" speedup for the iQOO 15 unless we measure it ourselves; no source gave a TOPS number, and tokens/s claims must come from our own logged runs.
2. That we beat a single phone (or cloud) on latency/throughput, or that exo/Petals/others "can't do phones" or are "the first" phone cluster; LinguaLinked (2023) and exo (2024 demo) are prior art, and no benchmark of ours supports a speed claim until we run one.
3. That we use vivo BlueLM, vivo AI SDKs, Gemini Nano/AICore or official OriginOS background-service APIs, or that the swarm "works in the background without battery-optimization setup"; none of these were verified available/documented, and vivo background limits are known to require manual whitelisting. Also avoid "fully private/zero-trust" unless the transport is actually encrypted and authenticated.

## Open questions to confirm with organizers
- How HackTracker measures phone/Office Kit usage; whether the loaner is an iQOO 15 (I2501) in every case; whether extra APKs/sideloading and peer-to-peer local networking (Wi-Fi Direct/hotspot) are allowed in the venue; whether more than one phone per team is supplied or allowed (a swarm demo needs several devices - verify).
- Submission format and deadline.
