# Ustaad (working name: SwarmOS) — project doctrine (read first, every session)

Naming: the product and the app are called **Ustaad** (founder decision, 2026-10-10). Every user-visible string says Ustaad. Code identifiers keep the old name for now (package `com.swarmos.app`, `SwarmService`, `SwarmLink`, scripts, routes); the applicationId switches to `com.ustaad.app` only in the final integration step. Feature names stay: Swarm, Agent Choir, Data Union, MemoryCloud, ExpertMesh.

Hackathon: iQOO Hackathon 2026 Grand Finale, Bengaluru, 2026-10-09 to 2026-10-11 (48 h). Demo must run on the iQOO phones.
Scope is locked to the diagram in docs/PRD.md: SwarmOS (coordination layer) + Agent Choir + MemoryCloud + Private Data Union + ExpertMesh, all on SwarmLink (local Wi-Fi). Nothing else. Blackout Cloud is a one-line "emergency node" footnote, not a feature. Do not reopen scope.

Orchestrator: Opus. Opus plans, delegates, reviews, closes gates. Opus writes no feature code beyond glue; workers do.
Workers: .claude/agents/swarm-*.md. Pass `model` explicitly on every Agent call (sonnet for build/research, haiku only for log parsing). Workers never spawn subagents.

Start of every session: read HANDOFF.md, then the newest file in gates/. Everything before the newest gate artifact is dead context.

Devices (verified 2026-10-09, see docs/device-probes/):
- iQOO A = serial 10BFC41T2N001UZ, wireless ADB 192.168.242.89:5555, build 16.0.24.1
- iQOO B = serial 10BFAU14Q6000XR, wireless ADB 192.168.242.23:5555, build 16.0.21.12
- adb = C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe ; NDK 28.2 ; JDK 17 ; no Android Studio GUI needed, use gradlew.
- iQOO C = serial 10BFAT216Z000XQ, wireless ADB 192.168.242.76:5555, build 16.0.20.1 (joined 2026-10-10)
- Phone A was reserved by the founder from 2026-10-10 13:12 to 2026-10-11 00:26, and released then for an update, a clean-up and tests ("now I have the Phone A that you can update and clean up and also such that you can test everything out on that as well"). While it was reserved the only thing done on it, at the founder's request, was random screen input into a vivo Remote Control session (17:17 to 21:58 and 22:58 to 00:27, taps inside a 10 % border; the founder's stated purpose: a presentation is open on that computer, nothing sensitive is on screen, the taps only keep it awake).
- Phone C carries that remote-control session since about 00:25 on 2026-10-11, and the founder asked at 00:26 for the same random taps there: one every 1 to 20 seconds, 10 % border, 120 minutes, same purpose. Nothing else is injected on Phone C; taps are held back when the remote-control screen is not in front. While that session is in front, Ustaad on Phone C is in the background and Phone C is not a reliable swarm member.
- At 01:31 on 2026-10-11 the founder asked for the same taps on Phone B as well (it also shows a remote-control session) and for 300 minutes on both B and C, one tap every 1 to 20 seconds, 10 % border; tests go to Phone A. Asked directly whether the taps are meant to count as phone or Office Kit usage for the organisers (both phones run HackTracker), the founder answered: no, only keeping the computers awake. Phones B and C are not to be used for tests, installs or anything else while those sessions are in front. Phone B restarted at about 02:37 and lost wireless adb; at 02:48 the founder wrote "done on both, check Phone B and Phone A connect to both and you can continue": Phone B was on the USB cable with Ustaad in front and no remote-control session, so from then on Phone B is used for tests together with Phone A (wireless adb restored with `adb tcpip 5555`, current build installed at 02:51). The tap run for Phone B stays alive and sends nothing unless a remote-control session is in front there again. Phone C keeps its session and its taps.
- Redmi M2101K6P is NOT a target. Ignore it.
- In Git Bash always `export MSYS_NO_PATHCONV=1` before adb shell commands with absolute paths.

Hard facts that bound the design:
- No Wi-Fi Aware on iQOO I2501 (feature flag absent, service absent). Wi-Fi Direct and softAP present. See docs/device-probes/.
- NNAPI service absent on Android 16; NPU path is Qualcomm QNN/HTP (libQnnHtpV81.so present) or GPU via OpenCL (libOpenCL_adreno.so present).
- Vivo OriginOS 6 kills background work. Every long task runs in a foreground service; prompt for battery whitelist on first launch.

Gates (each closes with gates/gate-<n>-<slug>.md: decisions, deltas from plan, open risks, ledger):
- G0 research+PRD (closed by Fable, 2026-10-09)
- G1 SwarmLink: two phones discover and exchange 100 MB with measured Mbps and RTT in-app
- G2 Inference baseline: chosen model runs in-app on one phone, tokens/s logged
- G3 Agent Choir demo end-to-end on 2 phones
- G4 Private Data Union demo end-to-end on 2 phones
- G5 MemoryCloud: a model that does not fit one phone's budget runs split across 2
- G6 ExpertMesh (stretch) and demo polish
Order is fixed. A gate without measured numbers on the real phones does not close.

Rules:
- Every number shown to judges must come from a run on the iQOO phones, logged in docs/benchmarks/.
- Kotlin + Jetpack Compose, minSdk 31, target 36. Native via NDK only for llama.cpp. No Flutter, no React Native, no Python on device.
- No cloud calls. If internet is used for a model download, say so in the UI.
- Commit messages: plain, no AI attribution.
