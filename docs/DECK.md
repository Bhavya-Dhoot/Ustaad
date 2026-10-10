# Ustaad deck: slide outline for the founder's 6-minute pitch

Updated 2026-10-10 against master 0a71f33 (gates 0 to 5 closed, gate 6 polish open). 12 slides plus 3 backup slides. Every number is followed by the file it comes from and is one that docs/CLAIMS.md or a benchmark file supports. Speaker sentences obey docs/CLAIMS.md and docs/research/07-novelty-and-prior-art.md; the novelty statements are quoted exactly. The live demo is in docs/DEMO.md; slides 3 to 7 double as its fallback if a phone fails. Planned time per slide is below; no rehearsal is recorded, so do not present the total as a measured time. No blank remains on a main slide; what is not measured is named on the slide that touches it. A screenshot check of eight screen fixes is running (docs/design/qa3).

Judging lines: product quality 30, novelty and impact 20, creative phone use 15, technical depth 15, Office Kit 10, demo 10, and the local-model bonus. No slide earns Office Kit: nothing is claimed until mirroring has been tried (docs/CLAIMS.md R20).

Decision on the Knowledge Bank slide. It was a backup slide for one stated reason: its replication was still being measured. The third run measured it (2026-10-10-knowledge-bank.md, "Third run"), so by that rule it is promoted to main slide 5. To stay at 12 slides the old slides 3 (link) and 4 (batch) are merged into one. The designed-for-later list and the limits stay on backup slide B2.

## Screenshot rules

- Real phone screenshots live in docs/design/qa/ (first visual pass), docs/design/qa2/ (second visual pass, phone C, real models, code 1f514b6; docs/design/qa2/REPORT.md) and docs/design/shots/. Crop out the black "HackTracker by" pill at the top: it is the hackathon's telemetry app, not Ustaad (docs/design/qa/REPORT.md).
- Prefer qa2 frames over qa frames, and `fix-` frames over numbered qa frames (those show the old hatched "ONLINE" stamp, defect 1, fixed since). The qa2 frames were taken before eight further screen fixes (source lines, audit lists each file once, card times, marker chips on saved answers, missing-phone line, keyboard and tab bar, memory card layout, embedder name); reshoot the frames you use after the qa3 check, on the demo build (gate 6).
- docs/design/shots/memcloud-ready-30b.png and memcloud-degraded-30b.png are frames from the measured three-phone run (phone C, 09:30 and 09:31, 2026-10-10-memorycloud-inapp.md); use them as they are.
- A real Choir run frame exists: docs/design/qa2/20-choir-result-top.png, 21-choir-result-lanes.png and 22-choir-result-end.png (an `m3` run on three phones with DISAGREE, round 2 and a CONSISTENT tool check). Its digits are one run from a visual pass, not a benchmark row; read the numbers from docs/benchmarks/2026-10-10-e2e2-choir.md, not from the frame.
- Do not use as evidence: docs/design/shots/choir-*.png (the two I opened, choir-scoreboard.png and choir-converged-matrix.png, carry a FAKE stamp, the placeholder model and the old "0 B TO INTERNET" stamp; check the others before any use), docs/benchmarks/choir-fake-A.png, choir-fake-A-result.png, scaffold-A.png, and docs/design/qa/fix-link-swarm-home-scrolled.png (its Swarm load panel shows "no model" and a FAKE stamp). No frame of a swarm batch with a result exists; docs/design/qa2/31-swarm-home-scrolled.png shows the panel idle with real phones. Capture one batch on the demo build.
- The founder's designs in ui-kit/screens/<name>/screen.png are used only where no phone screenshot exists, and are labelled "design" on the slide. They contain invented sample values (ui-kit/README.md); never read a number off them.

## Main slides (planned 6:00)

### 1. One phone cannot run a big model. (0:00 to 0:20)
- Carries: "134 s" with a hand-drawn underline: one phone gave no token in 134 s on the 18.6 GB 30B model and the system killed 23 background apps (2026-10-10-rpc-split-spike.md; size from docs/models.md).
- Picture: none; type only, a row of the three phone tokens A, B, C in marker colours.
- Say: "A 30B model is 18.6 GB. On one phone we got no token in 134 seconds and the system killed 23 other apps. Meanwhile people keep private files on several phones."
- Earns: novelty and impact, technical depth.

### 2. Ustaad makes phones work as one. (0:20 to 0:40)
- Carries: the Swarm screen itself, "3 phones online, 2 links" (frame text; live in the app).
- Picture: docs/design/qa/fix-link-swarm-home.png (Swarm home after the fixes; the peers show Severe and Hot, which is true thermal state).
- Say: "Ustaad is a coordination layer on iQOO phones. It shares work and keeps a log of what it did, with no cloud model in the loop, on top of open-source runtimes."
- Earns: product quality, local-model bonus.

### 3. Linked phones finish a batch 2.57 times faster. (0:40 to 1:10)
- Carries: a drawn bar chart. Single phones B 10.09, C 12.03, A 10.29 tok/s; two phones 18.83 (hatched, "one run"); three phones 30.91, range 28.90 to 31.94; 32 short prompts, GPU on all three, through the access point, debug build (2026-10-10-e2e2-swarm-throughput.md). Under it, one line in mono for the link: Wi-Fi Direct raw 640.7 to 724.9 Mbps and 790 to 920 with 4 to 8 streams against 89.2 to 102.2 through the access point; the group off the station's channel 10.3 against 426 on it, one run each (2026-10-10-swarmlink-throughput.md).
- Picture: the chart; docs/design/qa/05-swarm-link-throughput-panel.png as a small inset only if you caption it "link page" (its figure is one LAN run, 79.9 Mbps; read the chart, not the frame). No frame of a batch result exists (see the screenshot rules).
- Say: "One batch of 32 short prompts: the fastest single phone makes 12 tokens a second, three phones make 31. That is a batch of independent requests, not one answer made faster. The link under it is a Wi-Fi Direct group on the station's own channel: 790 to 920 megabits a second, against about 90 through the access point. Off that channel it fell to 10, so we pinned it, and we measured it."
- Earns: technical depth, product quality. Do not say Wi-Fi 7 and do not say we invented the pinning (two patents describe it).

### 4. Private files stay on their own phone. (1:10 to 1:40)
- Carries: the acceptance pair ran, Phone B's bill photo and Phone A's certificate both cited; first-time questions, n = 9: median 4.2 s to the first word and 6.6 s in total, the 2 s target met by 2 of 9 and the 6 s target by 4 of 9 (2026-10-10-e2e2-data-union.md, gate-4-private-data-union.md). On the audit frame the line "text only" for what was sent; the frame is a QA frame and its byte figures are not in a benchmark file, so do not read them aloud.
- Picture: docs/design/qa/30-data-ask-answer.png (sources with phone tokens and the timing strip) beside docs/design/qa/32-data-audit.png (what was sent, what each phone returned). Two frames side by side, not three. Capture the bill-photo and certificate pair on the demo build when you can.
- Say (novelty statement 3, exactly): "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file."
- Earns: novelty and impact, product quality, creative phone use (on-device OCR, 74 to 110 ms per image on the second run, 2026-10-10-e2e2-data-union.md). Camera scan and dictation are not claimed.

### 5. Saved answers reach other phones within a second. (1:40 to 2:10)
- Carries: "under 0.8 s": on three phones, with no sync call, two shared saves and three revokes by Phone B were each listed on both other phones at the first poll. With the author's phone stopped, the other phone answered from the card in 40 and 47 ms with no model run, showing who saved it, when, and the two files it cited (2026-10-10-knowledge-bank.md, "Third run"). The live search of the same question with the author away took 5090 ms (2026-10-10-e2e2-knowledge-bank.md). Under it in small type, not measured: a phone that is away when the card is saved, the 3-minute timer, more than three phones.
- Picture: docs/design/qa2/13-kb-card-detail-on-c.png (a card received from the swarm, with sources and provenance) beside docs/design/qa2/12-kb-page-saved-and-revoked.png (SAVED and REVOKED rows). Frames from the second visual pass, before the card-time fix; reshoot after the qa3 check.
- Say: "A confirmed answer becomes a card made by a phone that holds the pairing secret. Shared, it reached both other phones in under a second with no sync call, and with the author's phone stopped the other phone answered from it in 40 milliseconds, with who saved it, when, and its sources. A phone that was away when the card was saved, we have not measured."
- Earns: novelty and impact, product quality. Do not say signed by one person, encrypted, or contradiction-checked (the card shows a phone with the pairing secret made it; docs/CLAIMS.md KB6). Never "replicates within a minute" as a first-run result.

### 6. Agreement is not proof. A checker decides. (2:10 to 2:40)
- Carries: three plain bars with their time: one answer 14 of 20 in 11.4 s; Choir on three phones 16 of 20 in 20.1 s; one phone sampling three times 18 of 20 in 32.3 s. Strict scorer, test off, one hand-drawn circle around "20.1 s against 32.3 s" (2026-10-10-e2e2-choir.md, gate-3-agent-choir.md).
- Picture: docs/design/qa2/21-choir-result-lanes.png (an `m3` run on three phones: DISAGREE, round 2, a tool check marked CONSISTENT, the agreement matrix; do not read its digits aloud). Fallback frame: docs/design/qa/fix-shell-choir.png (compose). Design fallback: ui-kit/screens/agent_choir_converged/screen.png, labelled "design".
- Say (novelty statement 2, exactly): "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Add: "Three phones answering in parallel and cross-checking took 20 seconds per problem against 32 for one phone sampling three times. With 20 problems one problem is five points, so we do not claim it is more accurate."
- Earns: novelty and impact, technical depth, demo. Say parallel speed and cross-checking; never "more accurate than one phone".

### 7. Three phones run an 18.6 GB model. (2:40 to 3:15)
- Carries: 5.7 to 6.4 tok/s, first token 1.5 to 2.8 s, load 319 s cold and 65 s warm; Qwen3-30B-A3B across phones C, A and B over a Wi-Fi Direct group, one run (2026-10-10-memorycloud-inapp.md, gate-5-memorycloud.md). The model is a mixture-of-experts model (48 layers, 128 experts, 8 used per token); whole layer ranges sit on the phones, C 0 to 15, A 16 to 31, B 32 to 47. Individual experts are not placed on different phones, and the "remote experts" figures on the ExpertMesh screen are arithmetic estimates. Under it, in small type: one phone gave no token in 134 s; two phones did not load.
- Picture: docs/design/shots/memcloud-ready-30b.png (SPLIT READY, the one-row layer strip with a colour per phone) beside docs/design/shots/memcloud-degraded-30b.png (DEGRADED naming Phone B after it was stopped; the 27 tokens written stay on screen). Two frames, not three.
- Say: "An 18.6 GB mixture-of-experts model runs across three phones, whole layer ranges on each: first token in 1.5 to 2.8 seconds, 5.7 to 6.4 tokens a second. One phone gave no token in 134 seconds and two phones did not load. It is one run, and the cold load took five minutes, so we load it before the demo."
- Earns: technical depth, product quality, novelty and impact. Never say two phones; never say repeated or sustained; never say experts were placed on different phones.

### 8. The model runs on the phone's GPU. (3:15 to 3:35)
- Carries: prefill 201.9 against 71.6 tok/s, first token 2.59 against 7.20 s, server memory 1032 against 5952 MB; one run each on phone C (2026-10-10-inapp-llm.md). Decode was within 5 %: 17.31 against 16.47 tok/s.
- Picture: docs/design/qa/13-models-ready-gpu.png (chat model READY with the GPU chip, memory 814.2 MB, which matches the first-start figure in the file). Do not read its other digits aloud.
- Say: "Qwen3.5-4B, Apache-2.0, runs on the Adreno GPU: prefill is 2.8 times faster and the process is about 5.8 times smaller. Decode is the same. No cloud model is called."
- Earns: local-model bonus, technical depth, creative phone use (on-device AI). EmbeddingGemma-300M is open-weight under the Gemma Terms of Use, not open-source (docs/CLAIMS.md R19).

### 9. What is new is the combination, measured. (3:35 to 4:05)
- Carries: the novelty matrix in two columns drawn from docs/research/07-novelty-and-prior-art.md section 2. Left, "known": channel pinning, QR-started Wi-Fi Direct, Bluetooth hand-over, llama.cpp child processes, role-split agents, model split over phones, hash-chained log. Right, "combination": the VERIFIED and CONSISTENT labels, the equal-compute baseline shown in the product, private-by-default snippets with per-phone citations, lease-bound memory lending, the whole on one consumer app. One hand-drawn circle around "combination".
- Picture: the drawn two-column matrix; no screenshot.
- Say (novelty statement 1, exactly, then its caveat): "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root." Then: "Each part has prior art. This is the combination, the search was limited, the lease is unit-tested only and the 30B result is one run."
- Earns: novelty and impact (20). Never say first, only or invented.

### 10. We show where it slows down. (4:05 to 4:35)
- Carries: a three-point line in Ink: decode 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10 on the GPU, Thermal Status 4 by 590 s (2026-10-10-llama-cpu-baseline.md). Beside it: the freezer killed the app and both servers at 6 min 18 s and 11 min 2 s with the screen off (2026-10-10-inapp-llm.md); a split of a model that fits, 4B, 6.86 and 6.98 against 10.80 and 14.69 tok/s (2026-10-10-memorycloud-inapp.md); a 20-problem Choir run heated all three phones to Thermal Status 2 to 3 in eight minutes (gate-3-agent-choir.md).
- Picture: the drawn line and one hatched caution stamp reading "IN FRONT ONLY". No screenshot.
- Say: "Phones overheat within minutes of sustained work. Android freezes the app when the screen is off, so Ustaad works while it is in front. And splitting a model that fits one phone is slower. We would rather show you than have you find it."
- Earns: technical depth, demo, product quality. This slide is the honesty slide.

### 11. Teams without signal are the next test. (4:35 to 5:10)
- Carries: the five ranked avenues as five short rows, each with its one open item (docs/research/08-applications-and-knowledge-bank.md section 4): disaster response and public safety; field maintenance and inspection; defence, information support only; healthcare camps; legal, audit and finance. Under it, a hand-drawn bracket: "none piloted".
- Picture: type only; connector doodles between the phone tokens as in the design system.
- Say: "We rank five uses by fit, buyer and risk. None is piloted. Defence only as information support: training, maintenance, logistics and medical information, never targeting. A consumer phone still lacks emission control, key management, encryption and accreditation, and we say so."
- Earns: novelty and impact. Slide 5 is the evidence behind the field-maintenance row: a confirmed answer outlives the phone that made it.

### 12. Give us one offline team to pilot. (5:10 to 5:40)
- Carries: the ask, plus three stamped lines: MEASURED (link, GPU, three-phone batch, three-phone Choir, Data Union pair, the 30B model on three phones, Knowledge Bank replication on three phones), PENDING (a repeat of the 30B run, the Knowledge Bank with a phone away at save time and the 3-minute timer, airplane mode, camera pairing, the demo build end to end, the check of eight screen fixes), NOT CLAIMED (Choir more accurate than one phone, experts placed on different phones, fully private, first).
- Picture: the three phone tokens A, B, C in a row; no screenshot. The ask is for the founder to set: an introduction to one team that works without signal, or support for the pending runs. PRD section 10 left the hackathon track unanswered; decide it before this slide.
- Say: "Everything we showed ran on these phones, one run each, and what we have not run is marked pending. We are looking for one offline team to try it with."
- Earns: novelty and impact, demo.

## Backup slides

### B1. Prior art we name ourselves
- Carries: a table from docs/research/07-novelty-and-prior-art.md section 1: LinguaLinked (ACL 2024) and prima.cpp (ICLR 2026) split a model over phones; Voltron (July 2026) and edge-llm-cluster pool devices; exo 2024-25; DRAG (2025) retrieves snippets peer to peer; Microsoft US10271336B2 and Samsung US11223929B2 pin a group to the station's channel; Qualcomm US9100942B2 and Nearby Connections cover QR and Bluetooth hand-over; Schneier and Kelsey (1998) and IETF AIVS cover hash-chained logs.
- Picture: type only.
- Say: "We name the prior art ourselves. We did not read the patent claims; this is not a freedom-to-operate opinion."
- Earns: novelty and impact (credibility).

### B2. Knowledge Bank: what a card is, its limits, and what is designed for later
- Carries: left column "a card": one saved answer (question, answer up to 600 characters, sources by phone, title and date, never file text, author, tier, revoked flag, audit hash, MAC); kept on the phone by default or shared; revoke travels as a tombstone; searched first on every ask; a card with a private source cannot be shared (a save with all four sources of the ask got HTTP 409 because the fourth was a private file) (2026-10-10-knowledge-bank.md). Middle column "limits": authorship is "a phone with the pairing secret", cards are unencrypted, no contradiction check, no export tier; the saves and revokes in the measurement were made through debug routes, and the page was only seen in the second visual pass. Right column "designed for later": per-phone signing keys, encrypted transport and storage, per-author feeds with consistency proofs, capability tiers, contradiction and staleness checks, signed export bundles, crypto-shredding (docs/research/08-applications-and-knowledge-bank.md section 2.5). Not measured: a phone that is away when the card is saved, the 3-minute timer, more than three phones.
- Picture: type only.
- Say: "This is a prototype. It reaches other phones in under a second and answers with the author gone. It is not encrypted and it does not check contradictions."
- Earns: novelty and impact.

### B3. Trust model and what a consumer phone lacks
- Carries: left, today: requests and responses carry an HMAC from the pairing secret, pairing waits for confirmation, a wrong secret, 90 s clock skew and a 2 MiB body are refused (phone C, debug build, 2026-10-10-swarmlink-throughput.md); traffic is not encrypted; the rpc-server is open while it listens, at most 10 s after the driver stops renewing (unit-tested only); the audit log is local and unsigned. Right, for defence use: emission control, key management, encryption, accreditation, platform integrity, hardware, supply chain, fleet management (docs/PITCH.md).
- Picture: type only.
- Say: "Private by design, not fully private. The question and short snippets cross the link signed but not encrypted."
- Earns: technical depth, product quality.

## Styling note (docs/design/DESIGN.md)

- Palette (hex): Whiteboard #F6F7F5 background; Sheet #FCFDFB raised surfaces; Ink #15171A all text, outlines and icons; Graphite #5E636B secondary text; Eraser Smudge #E4E7E3 hairlines and dot grid; Marker Teal #1E9BB0 for Phone A; Marker Tangerine #E08A3C for Phone B; Marker Magenta #C8457E for Phone C. No fourth colour.
- Fonts and roles: Bricolage Grotesque for titles (weights 600 to 800, tight tracking); Geist for body; Geist Mono for every number, file name, model name and stamp; Shantell Sans for handwritten notes, at most two per slide.
- Marker colours mean "which phone" and nothing else: phone tokens are 28 px circles with the letter A, B or C and a 1.5 px Ink outline. Never use them for decoration, buttons or status.
- One hand-drawn Ink underline or circle per slide, on the single most important value.
- Stamps for states: outline for good states (VERIFIED, CHAIN OK), diagonal hatching for cautions (DISAGREE, UNVERIFIED, PENDING, ONE RUN); at most one stamp per card.
- Shapes: 8 px corners, 1.5 px Ink outlines, one hard Ink shadow of 3 px on the primary element only. Left-aligned, offset layouts; never three equal cards in a row.
- Bans: no gradients, glows, glassmorphism, neon, purple or blue "AI" styling, dark terminal look; no emoji; no pure black or pure white; no red, green or yellow status colours; no circular spinners; no em-dashes in visible text; no marketing words (the list is in docs/design/DESIGN.md section 7).
- Every number on a slide is a live measurement or carries its source file in small Geist Mono under it.

## Still pending, for the notes page

The Knowledge Bank with a phone that is away when the card is saved, the 3-minute timer and more than three phones; a repeat of the 30B run (cold load, 5 minutes of generation, Thermal Status 2 or above); the 20-problem Choir run repeated after 08a205f and a Choir run on the demo build; airplane mode and bytes to the internet; camera QR scan, document scan and dictation by a person; the 20-minute screen-off soak; Office Kit mirroring; the check of eight screen fixes (running, docs/design/qa3) and the demo build end to end (gate 6) (docs/CLAIMS.md section 4, M6 to M8).
