# Ustaad deck: slide outline for the founder's 6-minute pitch

Written 2026-10-10 against master e2d41e4 (08:25 IST). 11 slides plus 3 backup slides. Every number is followed by the file it comes from and is one that docs/CLAIMS.md or a benchmark file supports. Speaker sentences obey docs/CLAIMS.md and docs/research/07-novelty-and-prior-art.md; the novelty statements are quoted exactly. The live demo is in docs/DEMO.md; slides 3 to 6 double as its fallback if a phone fails. Planned time per slide is below; no rehearsal is recorded, so do not present the total as a measured time. The second end-to-end run is in progress (second run pending, 2026-10-10-e2e2-*.md); where a slide shows BLANK, put the number in only when its file exists.

Judging lines: product quality 30, novelty and impact 20, creative phone use 15, technical depth 15, Office Kit 10, demo 10, and the local-model bonus. No slide earns Office Kit: nothing is claimed until mirroring has been tried (docs/CLAIMS.md R20).

## Screenshot rules

- Real phone screenshots live in docs/design/qa/ (taken on phone C with real models and the demo corpus; the files named `fix-` were taken after the fixes) and docs/design/shots/. Crop out the black "HackTracker by" pill at the top: it is the hackathon's telemetry app, not Ustaad (docs/design/qa/REPORT.md).
- Frames numbered 01 to 56 were taken at commit 71f2359 and show the old hatched "ONLINE" stamp in the strip (defect 1, fixed since). Prefer a `fix-` frame where one exists, and reshoot the others on the final demo build before the deck is final.
- Do not use as evidence: docs/design/shots/choir-*.png (the two I opened, choir-scoreboard.png and choir-converged-matrix.png, carry a FAKE stamp, the placeholder model and the old "0 B TO INTERNET" stamp; check the others before any use), docs/benchmarks/choir-fake-A.png, choir-fake-A-result.png, scaffold-A.png. No screenshot of a Choir run with the real model exists yet; capture one of `m2` on the demo build.
- The founder's designs in ui-kit/screens/<name>/screen.png are used only where no phone screenshot exists, and are labelled "design" on the slide. They contain invented sample values (ui-kit/README.md); never read a number off them.

## Main slides (planned 6:00)

### 1. One phone cannot run a big model. (0:00 to 0:30)
- Carries: "134 s" with a hand-drawn underline: one phone gave no token in 134 s on the 18.6 GB 30B model and the system killed 23 background apps (2026-10-10-rpc-split-spike.md; size from docs/models.md).
- Picture: none; type only, a row of the three phone tokens A, B, C in marker colours.
- Say: "A 30B model is 18.6 GB. On one phone we got no token in 134 seconds and the system killed 23 other apps. Meanwhile people keep private files on several phones."
- Earns: novelty and impact, technical depth.

### 2. Ustaad makes phones work as one. (0:30 to 0:55)
- Carries: the Swarm screen itself, "3 phones online, 2 links" (frame text; live in the app).
- Picture: docs/design/qa/fix-link-swarm-home.png (Swarm home after the fixes; the peers show Severe and Hot, which is true thermal state).
- Say: "Ustaad is a coordination layer on iQOO phones. It shares work and keeps a log of what it did, with no cloud model in the loop, on top of open-source runtimes."
- Earns: product quality, local-model bonus.

### 3. Phones link directly at up to 920 Mbps. (0:55 to 1:25)
- Carries: a drawn bar chart in Ink. Direct raw 640.7 to 724.9 Mbps, Direct with 4 to 8 streams 790 to 920, access point 89.2 to 102.2; the group off the station's channel 10.3 against 426 on it, one run each (2026-10-10-swarmlink-throughput.md). Solid bars for Direct, hatched for the access point.
- Picture: the chart, with docs/design/qa/05-swarm-link-throughput-panel.png as a small inset (the link page; its figure is one LAN run, 79.9 Mbps, so caption it "link page" and read the chart, not the frame).
- Say: "A Wi-Fi Direct group on the station's own channel moves 790 to 920 megabits a second, against about 90 through the access point. Off that channel it fell to 10 megabits, so we pinned it, and we measured it."
- Earns: technical depth. Do not say Wi-Fi 7 and do not say we invented the pinning (two patents describe it).

### 4. Private files stay on their own phone. (1:25 to 2:00)
- Carries: source cards after 41 to 143 ms (2026-10-10-e2e-data-union.md), and on the audit frame the line "text only" for what was sent. The frame is a QA frame; its byte figures are not in a benchmark file, so do not read them aloud.
- Picture: docs/design/qa/30-data-ask-answer.png (sources with phone tokens and the timing strip) beside docs/design/qa/32-data-audit.png (what was sent, what each phone returned). Two frames side by side, not three.
- Say (novelty statement 3, exactly): "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file."
- Earns: novelty and impact, product quality, creative phone use (on-device OCR, 161 and 165 ms per image, 2026-10-10-e2e-data-union.md). Camera scan and dictation are not claimed.

### 5. Agreement is not proof. A checker decides. (2:00 to 2:35)
- Carries: "15 of 20" and "18 of 20" as two plain bars: one phone alone and one phone sampling three times, strict scorer, no degenerate answers (2026-10-10-choir-answer-quality.md). Leave a third bar blank with the word "pending" for the two-phone Choir arm (BLANK, 2026-10-10-e2e2-choir.md).
- Picture: docs/design/qa/fix-shell-choir.png (Choir compose with the real model on Phone C and Phone B). Replace it with a run of `m2` from the demo build when you have one. Design fallback: ui-kit/screens/agent_choir_converged/screen.png, labelled "design".
- Say (novelty statement 2, exactly): "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Add: "The two-phone score after our fix is still being measured."
- Earns: novelty and impact, technical depth, demo. Do not say Choir is more accurate or faster than one phone.

### 6. A model can be split across phones. (2:35 to 3:10)
- Carries: 6.86 and 6.98 tok/s split against 10.80 and 14.69 on one phone, 4B model, phones C and A (2026-10-10-memorycloud-inapp.md).
- Picture: docs/design/qa/52-memory-plan-4b-fits.png (the plan screen, with the one-row layer strip and the stated warning that splitting a model that fits is slower). Reshoot the plan screen after the Memory fixes.
- Say: "The split works. For a model that fits on one phone it is slower, and the screen says so. It exists for models that do not fit, and our three-phone run of the 30B model is not done yet."
- Earns: technical depth, product quality. BLANK: 30B over three phones, 2026-10-10-memorycloud-inapp.md.

### 7. The model runs on the phone's GPU. (3:10 to 3:35)
- Carries: prefill 201.9 against 71.6 tok/s, first token 2.59 against 7.20 s, server memory 1032 against 5952 MB; one run each on phone C (2026-10-10-inapp-llm.md). Decode was within 5 %: 17.31 against 16.47 tok/s.
- Picture: docs/design/qa/13-models-ready-gpu.png (chat model READY with the GPU chip, memory 814.2 MB, which matches the first-start figure in the file). Do not read its other digits aloud.
- Say: "Qwen3.5-4B, Apache-2.0, runs on the Adreno GPU: prefill is 2.8 times faster and the process is about 5.8 times smaller. Decode is the same. No cloud model is called."
- Earns: local-model bonus, technical depth, creative phone use (on-device AI). EmbeddingGemma-300M is open-weight under the Gemma Terms of Use, not open-source (docs/CLAIMS.md R19).

### 8. What is new is the combination, measured. (3:35 to 4:15)
- Carries: the novelty matrix in two columns drawn from docs/research/07-novelty-and-prior-art.md section 2. Left, "known": channel pinning, QR-started Wi-Fi Direct, Bluetooth hand-over, llama.cpp child processes, role-split agents, model split over phones, hash-chained log. Right, "combination": the VERIFIED and CONSISTENT labels, the equal-compute baseline shown in the product, private-by-default snippets with per-phone citations, lease-bound memory lending, the whole on one consumer app. One hand-drawn circle around "combination".
- Picture: the drawn two-column matrix; no screenshot.
- Say (novelty statement 1, exactly, then its caveat): "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root." Then: "Each part has prior art. This is the combination, the search was limited, the lease is unit-tested only and the 30B run is pending."
- Earns: novelty and impact (20). Never say first, only or invented.

### 9. We show where it slows down. (4:15 to 4:55)
- Carries: a three-point line in Ink: decode 12.3, 10.6, 6.8 tok/s at minutes 1, 5, 10 on the GPU, Thermal Status 4 by 590 s (2026-10-10-llama-cpu-baseline.md). Beside it: the freezer killed the app and both servers at 6 min 18 s and 11 min 2 s with the screen off (2026-10-10-inapp-llm.md); split 4B 6.86 and 6.98 against 10.80 and 14.69 tok/s.
- Picture: the drawn line and one hatched caution stamp reading "IN FRONT ONLY". No screenshot.
- Say: "Phones throttle within minutes. Android freezes the app when the screen is off, so Ustaad works while it is in front. And splitting a model that fits one phone is slower. We would rather show you than have you find it."
- Earns: technical depth, demo, product quality. This slide is the honesty slide.

### 10. Teams without signal are the next test. (4:55 to 5:30)
- Carries: the five ranked avenues as five short rows, each with its one open item (docs/research/08-applications-and-knowledge-bank.md section 4): disaster response and public safety; field maintenance and inspection; defence, information support only; healthcare camps; legal, audit and finance. Under it, a hand-drawn bracket: "none piloted".
- Picture: type only; connector doodles between the phone tokens as in the design system.
- Say: "We rank five uses by fit, buyer and risk. None is piloted. Defence only as information support: training, maintenance, logistics and medical information, never targeting. A consumer phone still lacks emission control, key management, encryption and accreditation, and we say so."
- Earns: novelty and impact. The Knowledge Bank is a backup slide because it has not run on a phone.

### 11. Give us one offline team to pilot. (5:30 to 6:00)
- Carries: the ask, plus three stamped lines: MEASURED (link, GPU, one-phone Choir, 4B split), PENDING (30B on three phones, two-phone Choir, GPU Data Union timing, airplane mode, camera pairing), NOT CLAIMED (faster than one phone, fully private, first).
- Picture: the three phone tokens A, B, C in a row; no screenshot. The ask is for the founder to set: an introduction to one team that works without signal, or support for the pending runs. PRD section 10 left the hackathon track unanswered; decide it before this slide.
- Say: "Everything we showed ran on these phones, and what we have not run is marked pending. We are looking for one offline team to try it with."
- Earns: novelty and impact, demo.

## Backup slides

### B1. Prior art we name ourselves
- Carries: a table from docs/research/07-novelty-and-prior-art.md section 1: LinguaLinked (ACL 2024) and prima.cpp (ICLR 2026) split a model over phones; Voltron (July 2026) and edge-llm-cluster pool devices; exo 2024-25; DRAG (2025) retrieves snippets peer to peer; Microsoft US10271336B2 and Samsung US11223929B2 pin a group to the station's channel; Qualcomm US9100942B2 and Nearby Connections cover QR and Bluetooth hand-over; Schneier and Kelsey (1998) and IETF AIVS cover hash-chained logs.
- Picture: type only.
- Say: "We name the prior art ourselves. We did not read the patent claims; this is not a freedom-to-operate opinion."
- Earns: novelty and impact (credibility).

### B2. Knowledge Bank: built, not yet run on phones
- Carries: left column "built": a card is one saved answer (question, answer up to 600 characters, sources by phone, title and date, never file text, author, tier, revoked flag, audit hash, MAC); kept on the phone by default or shared; replicates when a phone comes online and every 3 minutes; revoke travels as a tombstone; searched first on every ask; 27 unit tests (2026-10-10-knowledge-bank.md). Right column "designed for later": per-phone signing keys, encrypted transport and storage, per-author feeds with consistency proofs, capability tiers, contradiction and staleness checks, signed export bundles, crypto-shredding (docs/research/08-applications-and-knowledge-bank.md section 2.5). Stamp: "NOT RUN ON A PHONE". Limits: authorship is "a phone with the pairing secret", unencrypted, no contradiction check.
- Picture: type only; no screenshot of the page exists yet. Capture the Knowledge bank page after the two-phone procedure runs (second run pending).
- Say: "This is a prototype. A confirmed answer outlives the question, the file and the phone. It has not run on a phone yet."
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
- Stamps for states: outline for good states (VERIFIED, CHAIN OK), diagonal hatching for cautions (DISAGREE, UNVERIFIED, PENDING, NOT RUN ON A PHONE); at most one stamp per card.
- Shapes: 8 px corners, 1.5 px Ink outlines, one hard Ink shadow of 3 px on the primary element only. Left-aligned, offset layouts; never three equal cards in a row.
- Bans: no gradients, glows, glassmorphism, neon, purple or blue "AI" styling, dark terminal look; no emoji; no pure black or pure white; no red, green or yellow status colours; no circular spinners; no em-dashes in visible text; no marketing words (the list is in docs/design/DESIGN.md section 7).
- Every number on a slide is a live measurement or carries its source file in small Geist Mono under it.

## Still pending, for the notes page

30B over three phones (2026-10-10-memorycloud-inapp.md); two-phone Choir arm after the fix, GPU Data Union timing and the PRD pair, aggregate throughput on the GPU and with three phones, Knowledge Bank procedure (all second run pending, 2026-10-10-e2e2-*.md); airplane mode and bytes to the internet; camera QR scan, document scan and dictation by a person; 20-minute screen-off soak; Office Kit mirroring (docs/CLAIMS.md section 4, M1 to M8).
