# HANDOFF — for the Opus session that builds this

Status (2026-10-09, end of Fable session): G0 closed. Research done, devices probed, PRD written, agent harness in place. No app code exists yet.

Read in this order (30 minutes):
1. CLAUDE.md (doctrine, device facts, gates)
2. docs/PRD.md (what to build, in what order, with acceptance numbers)
3. gates/gate-0-research-prd.md (decisions + risks + ledger)
4. docs/research/*.md only when a worker needs the detail; the PRD already summarizes them.
5. docs/device-probes/*.md for raw device output.

Then: open plans/2026-10-09-build-plan.md, confirm the three plan lines (Constraint / Failure modes / Ledger) still hold, and start G1 by delegating to swarm-network-engineer with the brief template in .claude/agents/README.md.

Tools ready:
- scripts/probe-device.sh <serial> : full device probe to Markdown
- scripts/tput/tput-arm64 : TCP throughput + RTT probe binary (see scripts/tput/README.md)
- scripts/adb-connect.sh : reconnect both phones over wireless ADB
