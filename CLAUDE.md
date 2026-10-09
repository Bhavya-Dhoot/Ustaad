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
- RESERVED (founder, changed 2026-10-10 03:40): phone B is in the founder's hands. No adb commands, installs, launches or inference on B until the founder releases it. All device work runs on A and C. (A was reserved for ten minutes before that and is free again.)
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
