# Plan: a shared closed cloud (2026-10-13)

The founder's goal of 2026-10-13, in their words: on install check the capability of the device and do allocation and swarm actions accordingly; a shared closed cloud with easy device registration; storage allocated from device storage; security with mesh isolation, graph querying and index updating; each part by a specialised subagent; other platforms if that is easy, otherwise the easy ones; a web app served from a phone with access configuration as easy as possible; memory pooling only locally; a device that disconnects mid-way is removed and its tasks are reallocated automatically, with work able to continue over the internet; LRU for cache removal; all of it end to end.

This reopens the scope lock in CLAUDE.md, which was set for the hackathon of 2026-10-09 to 2026-10-11. The founder reopened it by setting this goal.

## Decisions by the founder

- 2026-10-13: "over the internet" means the owner reaching their own home cluster and its models from their phone while away. No hosted model anywhere. The model is not exposed. Memory pooling and distributed computing happen only on the local network. It must be robust.
- 2026-10-13: the lowest Android version to support is 8.0 (API 26).
- 2026-10-13: push to the remote once all the packages are merged.

## What exists today (surveys of 2026-10-13, read-only, file references in the survey returns)

### Registration, isolation, storage, index, graph

- Registration: hosting a swarm makes a new random 32-byte secret every time (link/LinkRuntime.kt:244) and shows a QR code; joining is a scan and one tap; the joining phone overwrites its own secret with the host's. The other members are not told. The peer list is in memory only; disk holds the secret (DataStore, not encrypted) and the Wi-Fi Direct credentials.
- Removal: there is no way to remove one phone. "Reset" changes this phone's secret only; hosting again drops every phone that does not scan again. `PeerLinks.forget` and `NodeRegistry.remove` exist with no caller in the UI.
- Isolation: one shared secret per group, no group id, no per-device key, no allow list of node ids. A phone is in one group. The signature covers method, path, time and body within 30 s; it does not cover the sender's node id (read unverified, NodeServer.kt:135) or the request id, so anyone who holds the secret can claim any node id, and a captured request could be replayed with a new request id inside 30 s (inferred by the survey). Transport is plain HTTP.
- Without the secret, on the same Wi-Fi: `GET /v1/hello` answers with node id, name, serial, IP, app version and model names; NSD broadcasts name and node id; the BLE advert carries 4 bytes of node id; every response carries X-Swarm-Node. Two groups on one Wi-Fi find each other by NSD, fail the signed health check, list each other as rejected and keep probing every 60 s (inferred). All debug builds share one fixed development secret.
- MemoryCloud loan: rpc-server has no authentication; it is bound to one Wi-Fi or Wi-Fi Direct address for a 10 s lease that the driver renews (memcloud/RpcLender.kt:71-85,141-146).
- Storage between phones: none. No route moves a user file. The node API is JSON with a 1 MiB request cap. The 100 MB transfer of gate 1 is a throughput probe whose bytes are discarded. No free-space or quota field in NodeInfo or NodeHealth.
- Index updates: manual only (buttons; a debug route). No run at app start, no timer, no file or content observer. A run is incremental (a file is skipped when its id and lastModified are stored; a changed file has its chunks replaced; files missing from a complete listing are removed, except that an empty listing never removes anything: the known rule C21). Peers are not told that an index changed; every question queries every peer live.
- Store: one sqlite file `pdu.db` with chunks, an FTS5 table, docs, folders, meta, and the Knowledge Bank's cards and tombstones; vectors are 256 float32 held in memory, brute-force cosine, capped at 60,000 chunks.
- Graph: none. The Knowledge Bank is saved answers (question, answer, up to 8 source titles), searched by word coverage plus cosine.
- Federated question: the asker sends the query text to every peer (`POST /v1/pdu/query`, 1.5 s deadline); each peer searches its own index and returns at most 10 snippets of 400 characters with titles and scores; private folders are filtered on the serving phone (SharePolicy); no file bytes move. Sharing is per folder for all paired phones, with no per-peer rule.

### Device capability and allocation

- At first run the app asks for permissions and nothing else: no reading of total memory, free storage, cores, chip, GPU or OS version (shell/SetupScreen.kt:231-237). There is no stored device profile or tier.
- Read continuously while the service runs (every 2 s): thermal status, battery, charging, free memory, Wi-Fi standard and speed. Decode speed is kept in memory only and is lost at a restart, so after every restart each phone is the scheduler's 5 tok/s guess again (sched/Scheduler.kt:10).
- Read at each model load: free memory against 1.2 times the file plus 1.5 GB; the heat gate; the allowed cores. A download needs free storage plus 1 GB.
- GPU or CPU: the GPU build is used when the switch is on and the binary is in the APK. There is no probe; a GPU that does not work is found by trying for up to 120 s and is then remembered for that install.
- The default model is fixed by the catalog (Qwen3.5-4B, EmbeddingGemma-300M), not chosen by device. The catalog has no rule of model against memory, only the refusal at load.
- What a phone tells the others (Caps): model name, capacity, running, queued, last tok/s, thermal, battery, charging, free memory. Not told: total memory, cores, chip, backend, context size, storage. The scheduler does not read the free memory it is sent.
- Fixed for the iQOO 15 (16 GB, SM8850) and wrong on a weaker device: 6 chat threads and 2 embedding threads, 4 GPU threads and 99 GPU layers, context 8192 with 2 slots, capacity 1, the 1536 MB overhead of the split planner, the 1.2 x + 1.5 GB load rule, the budget of 200 ms per token (a phone under 5 tok/s would overrun it), arm64 only, minSdk 31.

### Other platforms

- One Gradle module, the Android app. No pure-JVM or multiplatform module. Unit tests (123 files) already run on the JVM.
- 186 Kotlin files, 71 with an Android import. In the ten non-UI packages 97 of 159 files (61 %) use neither Android nor the app's Context singleton: the agent endpoint, scheduler, keep busy, Choir logic, MemoryCloud logic, most of llm/.
- Tied to Android: starting llama-server from the app's native library folder (the flags are upstream llama.cpp flags), the foreground service and the Context root object, MediaStore and the folder picker for the Data Union, ML Kit for OCR and QR, discovery through NsdManager, Wi-Fi Direct and Bluetooth, thermal and battery readings, the Compose UI (43 files, about 9,000 lines).
- Portable as used: Ktor 3.6 server and client on the CIO engine, javax.crypto for the signatures, androidx.sqlite with the bundled driver (it has JVM targets, inferred).
- PC side today: scripts that sign and call a phone (scripts/swarm-call.sh, the Python checks). None is a node: nothing on a PC serves hello, health, caps or chat.
- Easiest first, by the survey: other arm64 Android phones (no port, only the constants); a headless JVM node on Linux (the /proc code already fits, upstream llama-server takes the same flags); Windows and macOS on the same JVM node with per-OS replacements for /proc, process kill and thermal readings; iOS last (no JVM, no child processes).

### A device that leaves, the internet, caches, a browser

- A phone that stops answering is marked away after three missed beats (measured 2.1 to 2.2 s) and is never removed: the entry stays and is probed every 5 s. `NodeRegistry.remove` has no caller.
- Moves on by itself today: a single chat job (pinged every second while it runs remotely; resent once to the same phone if it still answers, else to the next best phone, this one included), every item of a batch (each is such a job), and a stream that has not yet sent text.
- Does not move on: a Choir lane (it ends as "away", nobody takes it over; a judge that fails has no second judge), a batch item that the scheduler gave up on (an error row, never tried again), a Data Union peer (its files are only there; the answer is marked incomplete), a MemoryCloud split (DEGRADED; the re-plan is a button; only the releases are automatic), a stream that already sent text, a batch whose coordinator died.
- Internet: no path exists. Link types are LAN, DIRECT and BT. An address outside the private ranges is refused when a peer is added (link/LinkRoute.kt:73-85, PeerLinks.kt:192,220), not at send time. The node server listens on every interface and serves any correctly signed request whatever its source. The only internet use in the code is the model download.
- Memory pooling is local only as a side effect of that pairing rule. MemoryCloud itself checks nothing: no same-subnet test, and the address a lender binds is never compared with the address the request came from. rpc-server has no authentication; its guards are the signed start, one driver at a time, the loan token and the 10 s lease.
- Caches: the rpc tensor cache on a lender is unbounded (1.6 GB for the 4B model, 5.7 to 6.0 GB for the 30B) with one clear-everything route and no button; the OpenCL program cache, model files, Choir evaluation files and tput.jsonl are unbounded; batch reports keep the newest 16; audit keeps 5 files of 1 MB; request dedup keeps 256 entries or 32 MB; job results 10 minutes; query vectors are the one true LRU (64). The Data Union stops at 60,000 chunks and the Knowledge Bank at 500 cards, both by refusing, not evicting.
- Browser: the node server serves JSON and server-sent events only. No HTML, no static files, no CORS, no cookie or token login; every route but `GET /v1/hello` needs the HMAC headers. The agent endpoint on 8790 is loopback only. Routes are added as a `SwarmModule`; a page that is not HMAC-signed needs a route group beside `/v1`.

## Design

In one sentence: a cloud is a named, closed group of devices with one identity each, a member list everybody holds, and four kinds of work on top of it (model jobs, files, questions over files, memory lending), of which only the first three may ever cross the internet.

### 1. Identity and membership (the base for registration, removal, isolation, browsers and the away phone)

- A cloud has an id (random) and a member list. Each device makes its own key pair on first start (EC P-256 from the platform's own crypto; the private key never leaves the device, on Android it sits in the hardware keystore). The member list is a log of signed entries: add (device id, public key, name, kind, added by) and remove.
- Joining stays a scan and one tap. The QR code carries the cloud id, the admitting device's address and public key, and a one-time invite code. The newcomer proves the invite, sends its public key, and the admitting device writes the add entry and passes it to the others. Hosting no longer makes a new secret each time, and the member list is on disk.
- Removing one device is an entry in the same log, made from a Devices list. The removed device holds nothing the others still accept. Nobody else has to scan again.
- A request names its sender, and the signature covers that name and the request id. Two devices agree a session key by a signed key exchange when they connect; requests keep the fast HMAC they use today, with that session key instead of the one shared secret.
- Kinds of member: `node` (phone or desktop: may compute, lend memory when local, store), `client` (the away phone: may send tasks, read and write files, ask questions; never computes for others, never lends), `browser` (a session of the device that serves it, see 6).
- The test scripts on the PC become clients with their own key, admitted through a debug-only path, so the regression checks keep working.

Revised on 2026-10-13 after the first protocol stage was read by the orchestrator and by Codex (docs/review/2026-10-13-cloud-protocol-codex-1.txt: 3 blockers, 12 high, 9 medium). The leaderless member log above does not hold: when removers can themselves be removed, a removal is not final (a removed device with a stale copy of the list can take its remover with it, and two concurrent removals can bring a removed device back), and the caps that bound the log become ways to block a removal. Replaced by:
- One writer. The cloud has one owner key, the creator's. The member list is a single hash-chained sequence of entries signed by the owner: add, remove (final), rotate (new shared secrets), transfer (a new owner key), checkpoint. A member accepts only an extension of the chain it holds. A device that is not the owner cannot write at all.
- Any node can still show the QR code: it passes the newcomer's request to the owner, and only the owner's signature makes a member. For the user it stays a scan and a tap, with the tap on the owner's device.
- A recovery key made at creation, shown once as a code to keep off the phone, may sign one thing: a transfer of ownership. A lost or stolen owner phone is replaced with it and then removed.
- The cost: membership cannot change while the owner's device cannot be reached. Members go on working with the list they hold.
- The join exchange uses a key exchange of its own and the admitter's signature, so a QR code learned later opens nothing. The discovery key and the storage key belong to an epoch that the owner changes on every removal. Token clients (scripts, browsers) are sealed too; unsealed only over loopback.

### 2. Isolation

- Discovery carries a short tag of the cloud id; a device ignores other tags and no longer probes them. The unsigned hello answers with the tag and the protocol version only; name, serial, address and model names move behind the signature.
- Bodies and streamed replies are sealed (authenticated encryption with the session key) on every link, the home Wi-Fi included, so another device on the same Wi-Fi reads nothing. Always on for anything that leaves the local network.
- Memory lending: the request must arrive on one of the lender's Wi-Fi or Wi-Fi Direct addresses with the driver on that same local network, the address rpc-server binds must be one of the lender's Wi-Fi or Wi-Fi Direct addresses (it may differ from the arrival address: a pair with both links sends the request over Wi-Fi and the bulk over Wi-Fi Direct), and a `client` or a remote link is refused. rpc-server itself still has no authentication; that stays written down.
- Sharing stays per folder; the private-folder rule is unchanged and applies to graph results too.

### 3. Device capability profile

- Measured at first run, after an app update and on request, and stored: total memory, cores and their top frequencies, chip, ABI, OS, free storage, whether the GPU build can start (a probe of seconds, not the 120 s trial), and, once a model is present, a short speed test.
- One pure function turns the profile into settings: which model of the catalog (by memory), GPU or CPU, threads, context size, slots and capacity, whether and how much memory the device may lend, how much storage it offers by default, and the time budget per token (from its measured speed, not the fixed 200 ms). A device that cannot run any model becomes a client that still stores and searches.
- The profile's class, total memory, backend, measured speed and storage figures are sent with the capacity report, kept across restarts, and used by the scheduler (no more 5 tok/s guess after a restart), the split planner and file placement.

### 4. Shared storage

- Each device offers a quota of its own storage (default from the profile, changed in Settings). Files put into the cloud are cut into chunks named by their hash; a manifest lists a file's chunks. Each chunk is kept on two devices (the count is a setting), chosen by free quota.
- When a device is removed or gone for good, the chunks it held are copied again from the other holder. Extra copies beyond the wanted count are a cache and are evicted least recently used when the quota is short.
- Chunks are encrypted at rest with a storage key of the cloud. Transfers are streamed, resumable and verified by hash.
- A device that holds a file indexes it for questions like any shared folder (see 5).

### 5. Index updates and graph

- The index updates itself: at app start, after a file arrives in the cloud store, when the photo library reports a change, and on a timer for picked folders, all through the existing incremental run. The rule for a folder whose last file was deleted (C21) is fixed by telling an empty folder from a failed listing.
- Each device publishes an index version, so the others know when to drop what they cached.
- Graph: at indexing time each chunk yields entities (people, organisations, places, dates, amounts, ids, email addresses, phone numbers) and links (entity to document, entity to entity when they share a chunk). A graph question returns the neighbours and the documents that connect two things, across devices, through the same private-folder filter. Retrieval for answers uses it to pull in linked documents.

### 6. A web app served by a device

- A device serves a small page from its own app (no outside files). Access is one scan: Settings shows a QR code whose link carries a session key in the part of the address that is never sent over the network. The browser signs its requests with it. Sessions are listed under Devices and removed one by one.
- First version: chat with streamed answers, questions over files, cloud files (list, upload, download), devices and status. On a desktop node this page is the whole interface.

### 7. Away from home

- The away phone is a `client`. Research 07 (docs/research/07-remote-access-to-a-home-cluster.md) compared an overlay the owner installs, the same overlay inside the app, a small relay, WebRTC, an onion service and iroh. Its first choice is a small relay: the home node and the away phone both dial out on port 443, the relay pairs them and forwards sealed frames it cannot read, so carrier NAT does not matter, nothing listens at home, and the owner does nothing beyond the pairing they already do. Its fallback is an overlay the owner installs (Tailscale), which needs no code beyond accepting that address. The app is built for both: every request and streamed reply is sealed end to end whatever carries it; the relay is one transport and a directly reachable address is another. The relay server is a small module in this repository that an owner can host; this plan does not deploy one. Running a relay costs about 2 to 6 dollars a month, and the research flags two questions it could not settle from primary sources (whether Cloudflare's terms allow it on Workers, and whether India's 2022 CERT-In rules would treat such a relay as a VPN service); both are the founder's to decide before a relay is offered to others. Always sealed, with nothing listening on a public address. Timeouts and the heartbeat are widened for that link. If the path drops in the middle of a task the home side finishes or reallocates as it does for a local device, and the client asks again for the same job id.

### 8. A device that leaves

- Away for longer than a set time, a device is taken out of routing and its stored chunks are re-copied; the owner can remove it for good. Until then it rejoins by itself.
- Everything reallocates by itself: a Choir lane is run again on another device and a failed judge has a second judge; a batch item the scheduler gave up on is queued again while the batch runs; a degraded split re-plans by itself onto the local devices that remain if the model still fits, else gives the phones their own models back as today.

### 9. Caches

- One small budget keeper, least recently used first, for: the rpc tensor cache (by model, never the one in use), the cloud store's extra copies, Choir evaluation files and the transfer log, with budgets from the profile and a Clear button. Model files are evicted only if the owner allows it and never the default ones.

### 10. Other platforms

- A headless node for Linux, Windows and macOS on the JVM: the Android-free part of the code compiled as it is, with a start-up, discovery, system readings and the upstream llama-server for that OS. It is managed through the web page of section 6. Other Android phones need only the profile of section 3. iOS is not attempted.

### 11. A faster local protocol that older devices can still use (asked by the founder on 2026-10-13)

- Measured today (gates/gate-1-swarmlink.md): 305 to 503 Mbit/s over Wi-Fi Direct and 96 to 104 Mbit/s through the access point for a 100 MiB probe; a request round trip averages 12 to 26 ms against a minimum of 2.7 ms, put down to Wi-Fi power save and a connection set up per request. Traffic is HTTP with JSON bodies, capped at 1 MiB.
- A persistent sealed channel per pair of devices: one long-lived connection with length-prefixed frames and stream ids, shared by requests, answers, streamed tokens and the heartbeat, so nothing is set up per request.
- A bulk path for files and model files: several parallel streams over the best link the two devices share (Wi-Fi Direct before the access point), frames up to 1 MiB, sealed, resumable, checked by a hash of the whole.
- Negotiation when two devices connect: each says what it supports (protocol versions, links, parallel streams, frame size, ciphers) and they use the best common set. A device that supports none of it stays on signed and sealed HTTP requests, so an older or weaker device is never locked out. A chip without AES instructions uses ChaCha20-Poly1305.
- Older devices in the other sense, the Android version: the app needs Android 12 (minSdk 31) today. Lowering it is a separate package: find what needs 31 with the lint check and guard or replace it. A device that cannot run a model joins as a client that stores, searches and asks (section 3).
- The frame format is shared with the relay of section 7, so one implementation serves the channel, the bulk path and the relay. It is specified in docs/CLOUD-PROTOCOL.md with the rest.

## Work packages, order and owners

Phase 1, in parallel, files kept apart:
- P1 capability profile and its use in runtime settings and scheduling: the inference engineer (new core/DeviceProfile, llm/LlamaArgs, ModelStore, LlamaRuntime's GPU probe, sched/Scheduler, GenBudget, the capacity report; one row in the setup screen).
- P3 index triggers, the last-file rule, the index version, the graph: the Data Union engineer (pdu/, kb/).
- P4 local-only memory lending, automatic re-plan, the cache budget keeper and the rpc cache: the builder that owns memcloud/ (resumed).
- P5a Choir lanes and the judge: the Choir engineer (choir/).
- P5b batch items queued again; devices taken out of routing after a time: the builder that owns sched/ (resumed; sched/BatchRunner, SwarmLlm).

Phase 2, after research 07 and a design review by the orchestrator:
- P2 identity, membership, isolation, sealed transport, the Devices list: the network engineer (link/, core/Signer, a new cloud/ package, discovery, pairing).

Phase 3, on top of P2:
- P6 shared storage: a storage builder (a new store/ package, routes, placement, repair, the Cloud screen).
- P7 the web gateway and the page: the network engineer for the gateway, a front-end builder for the page.
- P8 the away link: the network engineer.

- P10 the persistent channel, the bulk path and the negotiation: the network engineer, after P2 (it uses the session keys and the sealed frames).
- P11 a lower minimum Android version: the Android builder, after P1 (it reads device facts with calls that need newer versions).

Phase 4:
- P9 the desktop node: a builder for a new Gradle module that compiles the Android-free sources.
- Documents, then phone runs when the founder gives a window.

Constraint: correctness and security of a system that now holds people's files and keys, built by several workers at once. So: one owner per file in each phase, the security design fixed in this plan and in research 07 before its code is written, every package with unit tests that fail without it, every diff read by the orchestrator before a merge, an independent adversarial read (Codex) of P2 and P6 before they are called done, and nothing called working before it has run on devices.

Failure modes: two workers editing one file (the file lists above; a worker that needs a file outside its list stops and says so); a worker inventing a protocol detail that another worker contradicts (wire formats live in one document, docs/CLOUD-PROTOCOL.md, written by the P2 owner and cited by P6 to P9); a security mistake that tests cannot see (the Codex read, and the reviewer agent on the diff); a package that grows beyond what was asked (each brief has a size limit and a stop-and-report rule); the old shared-secret protocol breaking the regression scripts (clients with their own key, and the old signing kept in debug builds until the scripts are moved); a change that is fine in unit tests and wrong on a phone (the regression scripts of 2026-10-11 run again on phones before any claim).

Ledger: surveys and research delegated (bounded, checkable by reading); P1 to P9 delegated to specialists, one per area as the founder asked, Sonnet; the design in this file, the reviews, merges and documents direct; the adversarial read to Codex.

## Open decisions, with the default this plan proceeds on

- The transport for the away phone: sealed frames over either a relay the owner hosts or a directly reachable address (an overlay). The relay's code is built; none is deployed. Deploying one, and for whom, is the founder's decision.
- How many copies of a stored chunk: 2.
- Who may add or remove devices: the owner (the creator's device) signs every change; any node can show the QR code and pass the request on. Changed from "every node" after the protocol review showed that rule cannot make a removal final.
- A device's default storage offer: 10 % of its free space at the time of the profile, at least 1 GB, never more than half.
- Away for how long before a device is taken out of routing and its chunks are re-copied: 10 minutes.
- Phones: none is connected (2026-10-13 14:31). Unit tests and a desktop run come first; phone runs need a window from the founder.

## Status (2026-10-13, about 17:45; written as a stopping point because the session may be ended for a disk move)

Master is 41a5549, 16 commits ahead of the remote (the remote is at b9f510c). The last full run on it: 1910 unit tests, 0 failures, debug APK built. Nothing of this plan has run on a phone; none is connected.

Merged on master:
- P1 capability profile (core/DeviceProfile.kt, core/DeviceProbe.kt; used by the model runtime, the scheduler and the capacity report). Two corrections made before the merge: an unproven GPU probe cannot switch off a GPU whose library is present, and a client device still loads the embedder.
- P3 index updates and graph (pdu/AutoIndex.kt, pdu/Graph*.kt; the last-file rule C21 fixed; a shared index counter that does not move on private changes; graph retrieval for answers is built and OFF until measured).
- P4 local-only lending by its own rule, automatic re-plan of a degraded split, core/LruBudget.kt and the lending cache budget; plus the follow-up that feeds the device plan into lending and the cache budget.
- P5a Choir lanes and the judge move to another device (on in the app; off by default in ChoirConfig for the old tests).
- P5b batch items tried again, devices gone after 10 minutes (NodeRegistry.onGoneChanged is the signal the storage package will use), job results capped at 512; plus the follow-up: per-device time budget, a resend never gets the cancellation of an abandoned run, BatchReport.retried.
- P11 minSdk 26 (Android 8.0), lint clean of NewApi; heat derived from battery temperature below API 29 (core/BatteryHeat.kt); a phone blind to its heat counts as status 2.

In flight, NOT merged:
- P2 stage 1 (the protocol document and the platform-free core, package cloud/, on branch `worktree-agent-a4bc244ba3ebc2510`, worktree .claude/worktrees/agent-a4bc244ba3ebc2510). Stage 1 and 1b are committed there (head ae36a99 at the time of writing); stage 1c was sent at about 17:15 and may be partly done or uncommitted in that worktree. Two Codex reads exist: docs/review/2026-10-13-cloud-protocol-codex-1.txt and -2.txt. The brief of stage 1c in short: remove checkpoints and compaction (everybody holds the whole chain from the genesis, which is tied to the cloud id in the QR code); a recovery transfer is a boundary below which no fork is accepted, and a replaced recovery key signs nothing; the six digits become a committed short authentication string; token sessions complete once; bounded tables everywhere; a resync path for a member that missed two rotations; the browser page off by default with a 12 hour idle expiry; a numbered list of what the wiring stage must do.
- After stage 1c: a third Codex read limited to the changes (the prompts used are in scripts/review/; the command is in the 2026-10-11 audit plan), then merge, then stage 2.

Not started:
- P2 stage 2 (wiring): the Android keystore identity, persistence of chain, keys and secrets, NodeServer and NodeClient on session keys with sealed bodies, dedup keyed by the verified member, the invite and the owner's confirmation screen, the Devices list with Remove, the recovery code shown once, discovery tags, the test scripts as token clients over loopback (`adb forward`). Known from P11: link/Ble.kt `hasPermissions()` returns true below API 31 without checking location.
- P6 shared storage, P7 the web gateway and page, P8 the away link and the relay module, P10 the persistent channel and bulk path, P9 the desktop node.
- Phone runs of everything above, the lists of "what a phone run must show" are in each worker's report as summarised in the merge commits and in this plan; an Android 8 or 9 device is needed for P11.

Decisions taken by the orchestrator after reviews, to tell the founder if not yet acknowledged: one owner signs the member list, with a recovery code (replaces "every node may add or remove"); the web page is off by default; an unknown heat reading counts as status 2.

Push: the founder asked for a push once all packages are merged. A push at 41a5549 was refused by the session's permission layer as premature. Push when P2 stage 1 is merged, or when the founder says so.

Update 18:10: stage 1c is committed on the branch (head 304417c or later; 2075 unit tests there, 191 in cloud/). The third Codex read was started at 18:10 with scripts/review/codex-prompt-cloud-3.txt, run from that worktree, output to the session scratchpad as codex-audit-cloud3.txt and to be copied to docs/review/2026-10-13-cloud-protocol-codex-3.txt. If this session ended before it finished, run it again; then merge the branch if it says ready to wire, else send its findings back to the builder.
