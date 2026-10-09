# Worker agents for SwarmOS

All workers are Sonnet, tools least-privilege, no nested delegation. Opus orchestrates. Pass `model: "sonnet"` explicitly on every Agent call; frontmatter is a request, not a guarantee.

| Agent | Job | Writes code |
|---|---|---|
| swarm-android-builder | app plumbing, screens, build/install | yes |
| swarm-network-engineer | SwarmLink (G1) | yes |
| swarm-inference-engineer | llama.cpp JNI, benchmarks, MemoryCloud split (G2, G5) | yes |
| swarm-rag-engineer | Private Data Union (G4) | yes |
| swarm-choir-engineer | Agent Choir (G3) | yes |
| swarm-device-tester | runs on phones, collects numbers | no |
| swarm-researcher | one bounded web question | no (docs only) |
| swarm-reviewer | read-only gate review | no |

## Brief template (paste into every Agent call)

```
Task (one sentence): ...
Gate: G<n>. Read: CLAUDE.md, docs/PRD.md section <x>, <research file section>, <existing source files>.
Files you may create or edit: <explicit list or directory>.
Deliverable: <code paths + the benchmark/doc file to write>.
Verify by: <exact command or on-phone check whose output proves it>.
Exclude: anything not listed; no new dependencies without stating why; no subagents.
Return: <= 40 lines.
```

## Routing rules
- Code task touching one module -> that module's engineer. Two modules -> split into two briefs, run in parallel only if files do not overlap.
- Any "is it working on the phone" question -> swarm-device-tester, never assume.
- Before closing a gate -> swarm-reviewer on the diff, then Opus writes gates/gate-<n>-<slug>.md.
- Fact newer than the six research reports -> swarm-researcher with one question.
