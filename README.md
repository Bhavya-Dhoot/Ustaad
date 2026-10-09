# SwarmOS

Two or three iQOO 15 phones on one local Wi-Fi link acting as one AI system, no cloud. Built for the iQOO Hackathon 2026 finale.

Start here: HANDOFF.md, then CLAUDE.md, then docs/PRD.md.

```
CLAUDE.md            project doctrine, device facts, gates
HANDOFF.md           what the next session does first
docs/PRD.md          requirements, build order, demo script
docs/research/       six research reports (models, networking, distributed inference, RAG, multi-agent, hackathon)
docs/device-probes/  raw ADB probes of both iQOO phones
docs/benchmarks/     measured numbers only
plans/               dated build plans
gates/               one artifact per closed gate
scripts/             probe-device.sh, adb-connect.sh, tput/ (NDK throughput probe)
.claude/agents/      specialized Sonnet workers + brief template
app/                 (not created yet) Android app
```
