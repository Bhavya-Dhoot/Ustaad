# Integration plan (orchestrator), 2026-10-10

Constraint: correctness of the merged app on three phones, with the PC known to hard-crash under load (Kernel-Power 41 at 00:49). Keep at most two Gradle builds at once, commit small and often.

Failure modes and what catches each:
- A branch compiles alone but not merged: after every merge run `:app:assembleDebug` and `:app:testDebugUnitTest` on master before the next merge.
- Two branches edited the same shared file (MainActivity, AndroidManifest, AppGraph, NodeServer, StatsBar): merge one branch at a time, resolve by keeping both intents, re-run the build.
- A worker's "verified" claim was on its variant with the fake model: device-tester re-runs each gate check on the merged base app with real models before the gate closes.
- PC crash mid-merge: every merge is its own commit; on restart run `git fsck`, check for NUL-filled files, delete build dirs.

Ledger:
- Merges, conflict resolution, AppGraph wiring, gate artifacts: orchestrator (glue).
- Restyle per module, MemoryCloud, first-run setup + settings screens: Sonnet workers in worktrees.
- Gate evidence runs: swarm-device-tester (Sonnet). Gate reviews: swarm-reviewer (Sonnet).

Merge order (each followed by build + unit tests on master):
1. uikit (ui/, fonts, chrome in MainActivity)
2. link (link/, manifest deep link, MainActivity intent handling, scripts/swarm-call.sh)
3. llm (llm/, jniLibs, AppGraph wiring of real clients, StatsBar WAN bytes)
4. choir (choir/, assets)
5. pdu (pdu/, demo corpus, manifest permissions if any)
Then install base app on A, B, C with real models and smoke-test: peers visible, chat model READY, one choir run, one data-union ask.

After merge, in parallel:
- memcloud worker (needs llm runtime + rpc spike results)
- restyle workers: link, models, choir, pdu (one each), then memcloud
- shell worker: first-run setup screen (permissions, battery whitelist) and settings sheet
- device-tester: G1 numbers (LAN + Wi-Fi Direct), G2 in-app tok/s on quiet phones
Gate artifacts G1..G6 written by the orchestrator from the benchmark files.
