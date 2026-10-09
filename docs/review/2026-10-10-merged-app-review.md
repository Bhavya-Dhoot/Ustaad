# Review of the merged app (2026-10-10, master eb75c56, read-only reviewer)

Paths are relative to app/app/src/main/java/com/swarmos/app/ unless they start with app/. "Manifest" is app/app/src/main/AndroidManifest.xml. Each finding has an owner tag in brackets: [link] [pdu] [llm] [sched] [choir] [shell]. Status column is filled by the orchestrator as fixes merge.

## Fix first
1. Demo build and secret: the demo is a debug build, so every DEBUG route is live behind the fixed dev secret. [shell: demo build type] [link: unsigned route] [pdu: storage permission]
2. pdu/PduEngine.kt:192-200: indexing or scanning while the embedder is the fake client wipes the index. [pdu]
3. llm/LlamaServerClient.kt:71-83: chat() blocks and cannot be cancelled, so Agent Choir's 60 s deadline is not enforced. [llm]
4. ui/StatsBar.kt:115: "0 B to internet" only counts the model downloader's bytes. [link]
5. link/PeerLinks.kt:141-195 and link/LinkRuntime.kt:168: responses are not authenticated and pairing needs no confirmation. [link]

## 1. Privacy and Data Union
- [pdu] BLOCKER pdu/PduApi.kt:124-149: /pdu/debug/scan takes any path (all-files access), copies it to public Pictures and indexes it with caller-chosen privacy; /pdu/index `dir` (PduEngine.kt:232-234) does the same. Fix: these exist only in debug builds; the demo build must be non-debug; drop MANAGE_EXTERNAL_STORAGE from the main manifest.
- [link] BLOCKER link/NodeServer.kt:93, link/LinkRoutes.kt:172: unsigned GET /v1/link/pairing-payload returns the current secret and the Wi-Fi Direct passphrase to anyone on the LAN. Fix: delete the exemption; tooling signs with the dev secret.
- [pdu] MEDIUM pdu/PduEngine.kt:442-446, pdu/FileOpener.kt:47-57: any signer can open a cited file on this phone's screen. Fix: notification only, the user taps; rate-limit per peer.
- [pdu] MEDIUM pdu/Ranking.kt:92-93, pdu/PduScan.kt:87: only a folder literally named "private" is withheld; scans default to shared. Fix: new folders and scans default to private until the owner shares them.
- [pdu] LOW pdu/Ranking.kt:98-101: withheldPrivate count is an oracle (by design per PRD).

## 2. Authentication
- [link] HIGH link/PeerLinks.kt:141-195, link/Discovery.kt:104-146, link/NodeClient.kt:68-70: responses are never authenticated; any 200 JSON counts as a trusted peer; a session's IP can be overwritten by whoever answers with the same nodeId. Fix: HMAC response header verified in NodeClient.
- [link][llm][choir][sched] HIGH with the dev secret: admin routes are not debug-gated: /link/host rotates the swarm secret, models can be stopped, evals and batches started, /choir/debug/script plants fake answers. Fix: random secret in the demo build; gate debug routes with BuildConfig.DEBUG.
- [link] MEDIUM link/NodeServer.kt:100-102: the whole body is read before the signature check. Fix: reject Content-Length over 1 MiB and bad timestamps first.
- [link] MEDIUM link/Tput.kt:50-111: probe port open to all with no auth, unbounded threads and bytes. Fix: HMAC token first; cap connections, bytes, seconds.
- [link] MEDIUM link/LinkRuntime.kt:168-179,205-213, MainActivity.kt:62, Manifest:55-60: deep link or QR replaces the secret and joins a group with no confirmation. Fix: confirm dialog with host name and fingerprint; adb bypass in debug builds only.
- [link] LOW core/Signer.kt:10-11: no replay cache; query string unsigned. Fix: 60 s cache of seen request ids; sign the raw URI.
- [link] LOW link/PeerLinks.kt:154: every 401 reads as "different secret" though clock skew over 30 s gives the same. Fix: distinct body, show "clock off by N s".
- [memcloud] MEDIUM for G5: rpc-server on the link interface has no authentication. Fix: bind it to the Direct or LAN address only while a split is active, stop it afterwards, and state the trust model.

## 3. No cloud calls
- [pdu] MEDIUM (unmeasured): bundled ML Kit brings datatransport (CctBackendFactory, JobInfoSchedulerService) that may upload usage logs when internet exists. Fix: measure per-UID bytes; strip with tools:node="remove".
- [pdu] MEDIUM pdu/PduVoice.kt:36-37,81-85: falls back to the network speech recogniser. Fix: on-device only, like choir/ChoirVoice.kt.
- [link] MEDIUM link/PeerLinks.kt:89-104 and callers: a QR or peer can name any address, WAN included, and the phone retries forever. Fix: accept only private, link-local and p2p addresses.
- [llm] LOW llm/ModelStore.kt:48-52: the 18.5 GB catalog entry has no SHA-256; llm/Downloader.kt:68 User-Agent says SwarmOS.

## 4. Measured numbers
- [link] HIGH ui/StatsBar.kt:47,115: see Fix first 4. Fix: stamp "NO INTERNET" (outline) only when no network has validated internet; otherwise a hatched "ONLINE" stamp plus the downloader's byte count.
- [link] LOW link/LinkScreen.kt:270,285: literal "5 GHz" shown before it is measured.
- [sched] LOW sched/ThroughputPanel.kt:73,81,146: FAKE warning looks like body text. Fix: Stamp(caution = true).

## 5. Robustness
- [pdu] BLOCKER pdu/PduEngine.kt:192-200: see Fix first 2.
- [llm] HIGH llm/LlamaServerClient.kt:71-83: see Fix first 3.
- [llm] MEDIUM llm/LlamaRuntime.kt:329-340,126-134: nothing restarts a killed server. Fix: one automatic restart with backoff and a banner.
- [llm] MEDIUM llm/LlamaRuntime.kt:105-111,177-179: orphan kill is asynchronous, start waits 3 s then fails. Fix: wait for the pid to vanish; retry twice.
- [llm] MEDIUM llm/ModelStore.kt:110-114: auto-load reloads the last model, including very large ones. Fix: auto-load catalog defaults only.
- [shell] MEDIUM core/AuditLog.kt:29-31,43-44: the file never rotates and is re-parsed inside the monitor, also on the main thread at start. Fix: keep lastHash and count in memory; rotate near 1 MB.
- [shell] LOW core/AuditLog.kt:38,53: a kill during append leaves a torn line and Verify then shows "Chain broken". Fix: repair the torn tail at init.
- [shell] LOW AppGraph.kt:77-82: runBlocking DataStore reads in Application.onCreate; service stop blocks the main thread up to 2 s.
- [link] LOW link/NodeServer.kt:50-56: port already in use is only audited. Fix: show it.

## 6. Concurrency
- [sched] MEDIUM sched/JobExecutor.kt:48: capacity is coerced to at least 1 and Choir and Data Union call AppGraph.llm outside the executor, so slot counts are wrong while they run. Fix: capacity follows the chat server's slots (0 when stopped); one gate for all local model calls.
- [link] LOW link/Heartbeat.kt:51-101: tracker mutated from two threads.
- [choir] LOW choir/ChoirCoordinator.kt:183-184,229-234,277-278, choir/ChoirNode.kt:35,156: fields written outside the lock.
- [llm] LOW llm/LlamaRuntime.kt:235-244: stop() outside the role mutex.

## 7. Consistency
- [memcloud] MEDIUM: the Memory tab says "not built yet" (being built now).
- [choir][sched][pdu] MEDIUM choir/ChoirRuntime.kt:172-187, sched/SchedModule.kt:59-79, pdu/PduEngine.kt:466-475: private peer-registration routes write the registry outside PeerLinks, so those peers have no heartbeat. Fix: route through PeerLinks, debug builds only.
- [pdu][choir] LOW: two dictation classes with different network policy. Fix: one, on-device only.
- [llm][sched] LOW ui/Theme.kt:51-63: legacy colour names still used in llm/ModelsScreen.kt and sched/ThroughputPanel.kt.
- [shell] LOW: thermal thresholds differ between scheduler, benchmark guard and status strip. Fix: one shared definition in core.
- [link][pdu][llm] LOW: visible "SwarmOS" strings: link/LinkRuntime.kt:208 "Not a SwarmOS pairing code", pdu/PduIndexer.kt:162 folder Pictures/SwarmScans, llm/Downloader.kt:68.
- [link] LOW: Bluetooth permissions declared with no Bluetooth code yet (phase 2 adds it).

## 8. Resource use
- [shell] MEDIUM core/SwarmService.kt:65-77: partial wake lock and low-latency Wi-Fi lock held for the whole service life; nothing stops the service. Fix: release when no peers and screen off; a Stop control.
- [llm] MEDIUM llm/LlamaRuntime.kt:105-111: both servers (about 6 GB) start on every phone at service start. Mitigated by the new "Load models at start" switch; keep default on for the demo.
- [link] LOW link/LinkRuntime.kt:114-125: "Stop hosting" leaves the Wi-Fi Direct group up.
