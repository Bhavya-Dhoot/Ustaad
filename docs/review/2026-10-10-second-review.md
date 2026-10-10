# Second review (2026-10-10, master 119d008, read-only reviewer)

Paths are relative to app/app/src/main/java/com/swarmos/app/ unless they start with app/. Owner tags: [pdu] [memcloud] [sched] [llm] [shell] [choir] [link].

## Status of the first review
Fixed on master: demo build type (BuildConfig.DEBUG false, random secret, no all-files permission, debug routes gated); Data Union private by default and peer-open is a notification only; cancellable chat and the Choir deadline; on-device-only dictation; scheduler capacity 0 and the shared gate; audit log rotation, tail repair and off-main loading; restart policy, orphan cleanup, default-only autoload; shared thermal levels; FAKE stamp; legacy colours; peer registration through PeerLinks.

Fixed on the SwarmLink branch, not yet on master (they land with the phase 2 merge): response authentication, removal of the unsigned pairing-payload route, private-address filter, body cap and skew check before the body, probe-port authentication and limits, pairing confirmation, request-id dedup and raw-URI signing, "No internet / Online" stamp, measured band instead of a literal "5 GHz", port-in-use shown, Stop hosting removes the Direct group, "Not an Ustaad pairing code", Bluetooth code behind the declared permissions.

Still open or partly open: see the owner lists below.

## New findings and leftovers, by owner

### [pdu]
- HIGH pdu/PduIndexer.kt:211: the embedder is read per batch; when the embed server stops mid-run (MemoryCloud, memory kill, restart) AppGraph.embedder becomes the fake client and its hash vectors are indexed under the real key; IndexGuard is checked once (PduEngine.kt:198). Fix: capture the client once per run, re-check IndexGuard.isReal before every batch, abort the run cleanly.
- LOW pdu/IndexGuard.kt:40: the wipe happens before the re-read; a kill mid-run leaves no documents to rebuild from. Fix: wipe after the first file embeds, or keep the list.
- LOW pdu/PduEngine.kt:498 with sched/GatedLlmClient.kt:24: the answer waits without bound behind a long peer job. Fix: bound the wait and show "waiting for the model".
- MEDIUM (demo trap): while no chat model is READY the answer comes from the fake client and reads "[FAKE] ... The real runtime is not wired in yet." Fix: Ask is disabled with a reason until a real model is ready; snippets-only mode is allowed and labelled.
- LOW pdu/Ranking.kt:113: withheldPrivate count is an oracle (by design; leave).
- PARTLY: ML Kit data-transport components are removed from the manifest, but no per-app byte measurement exists yet (needs a phone; defer).

### [memcloud]
- MEDIUM memcloud/RpcWorker.kt:86: the bind check uses LinkRoute.localLinks() (every up interface, cellular included) instead of advertisable(); contradicts the stated trust model.
- MEDIUM memcloud/RpcWorker.kt:138-161: the watchdog ends the loan only when the driver is away; a driver that is online with no split leaves rpc-server open and the worker's models stopped for good. Fix: a lease the driver renews (re-POST /rpc/start every few seconds); stop and restore on expiry.
- MEDIUM memcloud/MemCloud.kt:276-279,105-111: fail() and a failed rpc start clean up nothing: peers keep rpc-server and their stopped models, this phone's chat and embedder stay stopped. Fix: run the stop() teardown on every failure path.
- MEDIUM memcloud/MemCloud.kt:93-96 vs RpcWorker.kt:134,147: driver and worker both use PhoneActivity owner "memcloud"; the driver's first IDLE emission clears the worker's "Lending memory" line. Fix: separate owners.
- MEDIUM memcloud/MemCloudScreen.kt:91: the text about stopping after 10 s is a constant that was never exercised and is false until the lease exists.
- MEDIUM memcloud/MemCloudScreen.kt:475-479, ExpertMesh.kt:17: "Ceiling" is computed from the heartbeat round trip (an HTTP call), so it is not a ceiling. Fix: label it an estimate, or measure a TCP round trip to the rpc port.
- LOW memcloud/MemCloudScreen.kt:310 with MemCloud.kt:459: "Received 1.6 GB of 1.0 GB" on the tied-head model. Compare with planned bytes plus the embedding copy.
- LOW memcloud/SplitPlanner.kt:72, MemCloudScreen.kt:199: the overhead constant is shown as "needs X GB". Say "estimate".
- LOW memcloud/GgufMeta.kt:93: unbounded array size from the file header; a corrupt file throws OutOfMemoryError. Bound it and catch.
- LOW memcloud/RpcWorker.kt:125, MemCloud.kt:169, MemCloudModule.kt:48: file names from signed requests are not reduced with File(name).name, so "../" loads any readable file.
- LOW memcloud/MemCloud.kt:89-91,601: object init reads SharedPreferences on the main thread.
- LOW memcloud/MemCloud.kt:315,505,587: ask, measure and baseline calls bypass the shared model gate.

### [sched]
- MEDIUM sched/StreamClient.kt:59-65: streamed replies carry no MAC and lines have no length cap; a LAN host can inject tokens. Fix: a final signed event over request id and full text (core/Signer), verified by the client; cap line length. sched/RemoteLlmClient.kt:34 uses peer.ip directly, bypassing path choice.
- LOW sched/GatedLlmClient.kt:24: acquire() has no bound.
- LOW sched/SchedRoutes.kt:65-99, SchedDtos.kt:34: no cap on maxTokens, message count and size, schema size, texts from a peer. Clamp.

### [llm]
- LOW llm/LlamaRuntime.kt:502,284,499: the restart epoch is read at death time, not at start, so a stop between the two is missed; a stale status after stop can show STARTING or FAILED. Store the epoch in the running record.
- LOW llm/LlamaServerClient.kt:158: withChatBudget is unused.
- MEDIUM (demo trap) llm/FakeLlmClient.kt:64: the fake answer text "The real runtime is not wired in yet." reaches users. Fix: "[FAKE] No chat model is loaded on this phone."
- NOTE: models routes (/load /stop /autoload /gpu /selftest /bench) are callable by any paired phone. Accepted for the demo (MemoryCloud needs it); say so in docs.

### [shell]
- MEDIUM MainActivity.kt:86-93: intent extras name, setupDone, autoload, gpu are honoured in every build and the activity is exported, so any app on the phone can use them. Fix: debug builds only.
- LOW AppGraph.kt:53: the initial secret is the dev secret in every build until the stored one loads. Make it an unusable placeholder.
- LOW core/AuditLog.kt:46,58,67: count and append block while verify holds the monitor; archives are never pruned.
- NOTE: in the demo build, pairing has no adb path: every phone needs a camera QR scan (never tested on a phone) or a pasted link (link/LinkScreen.kt:318).
- NOTE: the demo package com.ustaad.app has its own empty models folder; models must be pushed there.

### [choir]
- MEDIUM (demo trap): Run works while the model is the fake client. Fix: in non-debug builds Run and Run all are disabled with a reason until a real chat model is ready; debug builds keep the FAKE path with its stamps.
- LOW: an abandoned model call keeps its gate slot until it returns (fixed in practice by cancellable chat; verify with a test).

### [link] (for the phase 2 merge)
- The merge with master conflicts in core/SwarmService.kt and ui/StatsBar.kt: keep master's ServicePolicy lock logic and call the branch's Wi-Fi lock helper from it; keep master's strip layout and the branch's "No internet / Online" stamp.
- PARTLY: a chunked body has no Content-Length and is still read whole. Cap bytes while reading.
- PARTLY: heartbeat tracker is only @Volatile.
- GET replays inside 30 s remain (read-only routes; accepted).
- Peer queries and snippets travel over plain HTTP with a MAC, not encrypted. State it in the trust model; do not call it private against someone sniffing the Wi-Fi unless the link is the app's own Direct group.
