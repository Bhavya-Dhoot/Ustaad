# Ustaad pitch

Rewritten 2026-10-10 against master e2d41e4 (08:25 IST). docs/CLAIMS.md is binding: a claim it marks UNPROVEN or FALSE AS STATED appears below only in the "do not claim" list, in a blank, or reworded with its replacement sentence. A number is followed by the file it comes from. BLANK marks a number that does not exist yet, with the file to read when it does. The second end-to-end run is in progress (second run pending, 2026-10-10-e2e2-*.md).

## The problem

One phone has 16 GB of memory, about 15.6 GB usable (docs/PRD.md section 1), and reaches Thermal Status 2 to 3 within 1.5 to 4 minutes of load (2026-10-10-llama-cpu-baseline.md). A 4-bit 30B model is 18.6 GB (docs/models.md): one phone gave no token in 134 s and the system killed 23 background apps (2026-10-10-rpc-split-spike.md). People also hold private files on several phones, and a cloud is what they do not want for those files.

## What Ustaad is

Ustaad turns two or three iQOO phones on one local link into a system that shares work and keeps a log of what it did, with no cloud model in the loop. The product is the coordination layer, Swarm, over existing open-source runtimes (llama.cpp v0.6.0, docs/models.md); it is not a new model or inference engine.

## The modules and the number behind each

| Module | One line | Number | State |
|---|---|---|---|
| Swarm | Phones find each other over Wi-Fi Direct, spread requests and fail over | Direct 640.7 to 724.9 Mbps raw and 790 to 920 with 4 to 8 streams, A and C; TCP round trip 6.3 to 8.3 ms, signed request 20 to 28 ms; 700 of 700 requests executed once across two path failovers (paths silenced with a fault hook, Wi-Fi not switched off); two phones 15.38 against 7.66 tok/s on 32 short prompts, the two swarm runs 2.0 times apart and the one-phone baseline throttled (2026-10-10-swarmlink-throughput.md, 2026-10-10-e2e-swarm-throughput.md) | Measured on two phones; three only at link level |
| Agent Choir | Each phone answers alone, the phones compare, a checker marks VERIFIED or CONSISTENT | One phone alone 15 of 20, one phone with three samples 18 of 20, strict scorer (2026-10-10-choir-answer-quality.md). BLANK: the two-phone Choir arm after the fix, 2026-10-10-e2e2-choir.md (second run pending). Before the fix Choir took 27,762 ms per problem against 14,458 ms for one phone (2026-10-10-e2e-choir.md) | One-phone arms measured |
| Data Union | Each phone searches its own files; the question goes out, short snippets come back | Source cards after 41 to 143 ms; first word 3.2 to 12.4 s on new questions, CPU build (2026-10-10-e2e-data-union.md). BLANK: GPU build and the PRD pair, 2026-10-10-e2e2-data-union.md | Measured on two phones |
| MemoryCloud | A layer split of a model over phones | 4B over two phones 6.86 and 6.98 tok/s against 10.80 and 14.69 on one (2026-10-10-memorycloud-inapp.md). BLANK: 30B over three phones, same file ("Pending" section) | Mechanism proven; capacity not proven |
| ExpertMesh | An explanation screen: why experts are grouped by layer range | None. The figures are estimates of network wait; no mixture-of-experts model ran split (2026-10-10-memorycloud-inapp.md) | Not measured |

On the Adreno GPU the chat model's prefill was 201.9 against 71.6 tok/s on the CPU, first token 2.59 against 7.20 s, server memory 1032 against 5952 MB, one run each on phone C (2026-10-10-inapp-llm.md).

## What is new, and what is not

Say these three, exactly as worded in docs/research/07-novelty-and-prior-art.md, with their caveats.
1. "We searched papers, GitHub, patents and vendor material up to 10 October 2026 and found no other app that puts phone-to-phone model offload, a verified-versus-consistent answer check, private cross-phone search and lease-based memory sharing on one measured link, with no cloud and no root." Caveat: each part has prior art; this is the combination; the search was limited; the lease is unit-tested only (docs/CLAIMS.md PS5) and the 30B run on three phones is pending.
2. "Agent Choir never lets agreement pass as proof: an answer is VERIFIED only if it passed the problem's own test, otherwise at best CONSISTENT, and an equal-compute one-phone baseline sits beside it on screen." Caveat: tool-checked selection, self-consistency and equal-compute comparison are published methods; the claim is the product presentation, and it holds only for problems with a deterministic checker. On built-in problems the stored test is an answer key (docs/CLAIMS.md R6).
3. "Private Data Union keeps every folder private by default, returns only short snippets with per-phone, per-file citations, and a peer can only ask the owner's phone to open a file." Caveat: peer-to-peer snippet retrieval is published (DRAG, 2025); the claim is this attribution and consent behaviour shipped on phones, for which no earlier description was found.

Prior art to name when asked: LinguaLinked (2023) and prima.cpp (ICLR 2026) split a model over phones; Voltron (July 2026), edge-llm-cluster and exo's demos pool devices; Microsoft US10271336B2 and Samsung US11223929B2 cover a group on the station's channel; Qualcomm US9100942B2 and Nearby Connections cover QR-started Wi-Fi Direct and Bluetooth hand-over (docs/research/07-novelty-and-prior-art.md). Our contribution there is a measurement on these phones: the group on the station's channel gave 640 to 920 Mbps, off it 10.3 Mbps against 426, one run each (2026-10-10-swarmlink-throughput.md).

## Limits we state

- A split of a model that fits one phone is slower: 6.86 and 6.98 tok/s against 10.80 and 14.69 (2026-10-10-memorycloud-inapp.md); 3.18 against 14.86 in the earlier test on the access point (2026-10-10-rpc-split-spike.md). A split is for a model that does not fit.
- The phones throttle: decode on the GPU fell from 12.3 to 6.8 tok/s over ten minutes as the phone reached Thermal Status 4 (2026-10-10-llama-cpu-baseline.md). Each demo segment is kept short.
- Ustaad works while it is in front with the screen on. With the screen off Android froze it in every test (22 to 265 missed heartbeats) and killed it twice while it generated, at 6 min 18 s and 11 min 2 s (2026-10-10-swarmlink-g1.md, 2026-10-10-inapp-llm.md). The battery setting is untested.
- No workload was measured on three phones, and no run used the demo build (docs/CLAIMS.md section 0).
- Choir roles differ by prompt and temperature on one model, Qwen3.5-4B (docs/CLAIMS.md R7).
- The only internet use is the model download, which is unit-tested and has not run on a phone (docs/CLAIMS.md R17).

## Trust model today

Requests and responses carry an HMAC from the pairing secret and pairing waits for confirmation; both are unit-tested and the signature checks passed on phone C. Traffic is not encrypted. The rpc-server is open to anyone on the link while it listens, at most 10 s after the driver stops renewing (the lease is unit-tested only). On the app's own Wi-Fi Direct group the group key protects the radio link by design (not measured); on someone else's Wi-Fi a sniffer could read questions and snippets (2026-10-10-swarmlink-throughput.md, gate-4-private-data-union.md; docs/CLAIMS.md R3, R27). Private by design: files and embeddings stay on the phone; the question and short snippets cross the link signed but not encrypted. Each phone's audit log is hash-chained, local and unsigned.

## What we do not claim

First or only; that we invented channel pinning, QR pairing, Bluetooth hand-over or hash-chained logs; "exactly-once delivery" (say "executed once"); a tamper-proof log. Faster than one phone; Choir faster or smarter than one phone. Two model families, a critic or a rotating judge. Wi-Fi 7, 6 GHz or an NPU figure. Wi-Fi plus Wi-Fi Direct adding bandwidth (0.3 to 0.5 times Direct alone). "0 bytes to the internet", "no internet" as a result, airplane-mode operation. "Fully private" or "zero-trust". Background operation, or the battery setting as a fix. Three phones as a workload; a model too big for one phone running split; any ExpertMesh measurement. Camera, document scan or dictation as demonstrated; Office Kit; the memory lease as proven; the Knowledge Bank as used on phones. Full list with row numbers: docs/DEMO.md and docs/CLAIMS.md section 2.

## Where else this goes

Ranked by fit with short bursts of questions, reachability of a buyer, accreditation burden and cost of failure (docs/research/08-applications-and-knowledge-bank.md). None of these has been piloted; each names the one run that would test it, and none of those runs has been done.

1. Disaster response and public safety (NDRF, SDRF, district control rooms, relief NGOs). Offline need is acute, teams already carry phones, no classified-data accreditation; missing today: a message layer and map, encryption, battery behaviour. Test: an airplane-mode drill with a real district plan and 20 team notes spread over three phones, ten field questions, one phone pulled.
2. Field maintenance and inspection (mines, utilities, plants, aviation line maintenance). The Knowledge Bank is the product here: fix histories survive staff changes, and the approved manual stays authoritative; the manual-revision pin is a design, not built. Test: 20 technician questions, bank first against raw files, its latency against the 4.7 to 16.1 s total answer times already measured (2026-10-10-e2e-data-union.md).
3. Defence, strictly as information support: training, maintenance, logistics and medical information, on a handset the customer chooses. Not targeting, weapons employment or ISR exploitation; those are out of scope and a written use policy has to say so. A long, gated track through a partner who holds accreditation. Test: radio-on seconds per sync of 1,000 cards over a Direct group, with a spectrum capture.
4. Healthcare camps and low-connectivity clinics. Information retrieval only, never diagnosis. Missing: encryption, role-based access, consent capture, clinical validation, and an audit log that does not prune. Test: public protocols and synthetic forms, clinician-graded citations, a router capture showing no external traffic.
5. Legal, audit and finance teams. Privacy and the audit trail carry the pitch; the several-phones advantage is weakest (these buyers have laptops). Missing: page-level citations, encryption, large-document performance (10 to 11 chunks per phone were exercised, 2026-10-10-e2e-data-union.md). Test: a 100-page synthetic bundle over two phones with a router capture.

Not ranked: schools, newsrooms and human-rights work (failure falls on the users; do not lead with it), small offices and families (the demo audience, weakest case to pay).

What a consumer phone lacks for the defence market, in the order a security officer would ask:
- Emission control: a Wi-Fi Direct group beacons a fixed-format name (`DIRECT-sw-c6f72247` in the Data Union run, 2026-10-10-e2e-data-union.md), and Bluetooth emits; no low-detection mode, power control or scheduled radio silence.
- Key management: one static pairing secret per swarm, no per-person or per-phone identity, no per-phone revocation.
- Encryption: transport is unencrypted HTTP; stores are plain SQLite.
- Accreditation: none, and no mobile-device profile found in India's scheme.
- Platform integrity: no check of bootloader state, root or app integrity; models arrive by a download screen or adb, with no signed packs.
- Hardware and environment: a consumer handset that throttles within minutes and is frozen by the OS in the background.
- Supply chain: an iQOO handset (a vivo brand) and a Qwen chat model of Alibaba origin; a defence pitch must show the stack on a customer-chosen handset and model.
- Fleet management: no device management, no signed app updates beyond adb installs.
Position: an architecture and a working prototype for unclassified use, with a roadmap to key management, encryption and a radio-silence mode; never described as accredited.

## Knowledge Bank

- Built in the prototype: a card is one saved answer (question, answer up to 600 characters, sources by phone, title and date and never file text, author, tier, revoked flag, audit hash, MAC). It is saved under a finished, cited answer a real model wrote, kept on the phone by default or shared with the swarm; shared cards replicate when a phone comes online and every 3 minutes; a revoke replicates as a tombstone; every Data Union ask searches the cards first; a card with a private source cannot be shared. 27 unit tests; never installed on a phone; the relevance floors (cosine 0.60, query-word coverage 0.50) are stated, not measured (2026-10-10-knowledge-bank.md).
- Limits today: authorship means "a phone that holds the pairing secret", cards travel unencrypted, there is no contradiction check and no export tier.
- Designed for later: per-phone signing keys and countersignatures ("corroborated"), encrypted transport and storage, per-author feeds with consistency proofs, capability-based tiers, a contradiction and staleness engine with document pins, signed export bundles, crypto-shredding for erasure (docs/research/08-applications-and-knowledge-bank.md section 2.5).

## Three likely judge questions

1. "Is it faster than one phone?" For a model that fits, no: split 6.86 and 6.98 tok/s against 10.80 and 14.69 (2026-10-10-memorycloud-inapp.md). Choir is not claimed faster or more accurate than one phone (the two-phone arm is BLANK, above). Where two phones add: independent short requests, 15.38 against 7.66 tok/s, with a throttled one-phone baseline and runs 2.0 times apart (2026-10-10-e2e-swarm-throughput.md). For a model one phone cannot run, the three-phone result is BLANK until 2026-10-10-memorycloud-inapp.md holds it.
2. "What leaves a phone, and is it private?" The question text goes to the other phones; embeddings and files do not; a peer returns short snippets; private folders are withheld and only counted, and the audit page shows what was sent and what each phone returned (2026-10-10-e2e-data-union.md). It is signed but not encrypted. We say private by design, not fully private.
3. "Isn't this done already?" Each part is. Models have been split over phones (LinguaLinked, prima.cpp), devices have been pooled (exo, Voltron), snippets retrieved peer to peer (DRAG). We found no shipped app that combines offload, verified-versus-consistent marking, private cross-phone search and lease-based memory sharing on one measured link, and the search was limited (novelty statement 1). We name the prior art ourselves.
