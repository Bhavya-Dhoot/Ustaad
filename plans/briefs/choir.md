# Brief: Agent Choir (G3) — swarm-choir-engineer

Task (one sentence): Build the Agent Choir module (package `com.swarmos.app.choir`): a coordinator fans one problem out to the phones, each answers with its local model as constrained JSON, disagreement triggers one exchange round, a Kotlin tool-checker verifies, and the screen shows lanes, disagreement, convergence and a single-phone baseline.

Read first: CLAUDE.md, docs/CONTRACTS.md (all), .claude/agents/swarm-choir-engineer.md, plans/briefs/_common.md, docs/PRD.md section 5.3, and from docs/research/05-agent-choir-multi-agent.md only the protocol, final-pick, demo-design and risk sections (grep the headings). Then the scaffold code: app/app/src/main/java/com/swarmos/app/AppGraph.kt, core/*, llm/LlmClient.kt, llm/FakeLlmClient.kt, link/NodeClient.kt, link/NodeServer.kt, choir/*.

Build flags: `-PidSuffix=.choir -PportOffset=20`.

You own: app/app/src/main/java/com/swarmos/app/choir/** , app/app/src/test/java/com/swarmos/app/choir/** , app/app/src/main/assets/choir/** .

Peers: read online peers from AppGraph.registry. The discovery module is being built by another worker at the same time, so add a debug way to register peers by IP in your module (a signed POST /v1/choir/debug/peers {ips: [...]} that calls GET /v1/hello on each and adds them to the registry).

Build:
1. Protocol (all messages @Serializable, at most 1 KB each, every message appended to AppGraph.audit):
   - R1 blind solve: the coordinator sends signed POST /v1/choir/solve {taskId, problem, kind: MATH | CODE | MCQ | TEXT, role: SOLVER | ALT_SOLVER, maxTokens: 300} to every peer and runs its own solve in parallel. Each node calls AppGraph.llm.chat with a per-role temperature, thinking off, and jsonSchema = {answer: string, confidence: number 0..1, key_steps: array of at most 3 strings}. ALT_SOLVER is prompted to use a different method (MATH: write the computation as one arithmetic expression; CODE: trace one test case by hand).
   - R2 only if the normalised answers differ: POST /v1/choir/revise with the other answers and key_steps (no author names), 150 tokens, same schema.
   - R3 only if there is still no majority: POST /v1/choir/judge to a node that authored neither leading answer when three nodes exist (otherwise the coordinator), 100 tokens, schema {pick: index, reason: string}.
   - Hard deadline 60 s overall; a node that misses its deadline is dropped from the vote and shown as timed out.
2. ToolChecker (pure Kotlin, no model): MATH: a safe arithmetic expression evaluator (+ - * / ^, parentheses, decimals, percent) applied to an expression extracted from key_steps, or to the problem's own "check" expression when the problem supplies one; MCQ: the answer is one of the option letters; CODE: nothing is executed on device, compare the returned outputs with the problem's expected outputs when it supplies them; TEXT: quote-exists check (every quoted span in the answer appears in the source text). Result: VERIFIED | REFUTED | NOT_CHECKABLE.
3. Final pick order: tool-verified, then majority of normalised answers, then highest summed confidence, then judge, then ABSTAIN. Label "unanimous but unverified" when all agree and the checker returns NOT_CHECKABLE.
4. Answer normalisation (numbers: strip units and commas, compare as decimals with a tolerance; MCQ: letter; text: lowercase trimmed).
5. Screen: problem input (free text + kind + a list of built-in problems from assets/choir/problems.json; write 20 problems with known answers: 8 multi-step arithmetic word problems, 4 percentage or ratio, 4 MCQ logic, 4 short code-tracing; aim for problems a 4B model gets right about half the time), one lane per node in its accent colour with streaming status, answer chip, confidence, tokens, ms; a DISAGREE / AGREE banner; round indicator; tool-check result; final answer; and a baseline lane = the coordinator's own R1 answer alone, started at the same instant, with its wall-clock. Agreement matrix and totals (tokens, wall-clock, rounds).
6. Eval harness: signed POST /v1/choir/eval {problemIds or "all"} runs the built-in set sequentially and returns and stores (filesDir/choir-eval-<ts>.json) per problem: baseline answer / correct / ms, choir answer / correct / ms / rounds / verified, plus summary accuracy for baseline vs choir vs choir+tool with n. This produces the scoreboard and must work unattended over scripts/swarm-call.sh (if that script does not exist yet, write the same HMAC + curl call inline in your test commands). A signed POST /v1/choir/run {problemId or problem} runs one task and returns the full trace, so a run can be triggered without touching the screen.

Verify:
- JVM unit tests: normaliser, ToolChecker (10+ cases including malformed expressions), final-pick logic with scripted node answers covering agree / disagree-then-converge / no-majority-judge / all-timeout, and deadline handling.
- On the phones with the fake model (the real runtime is being built by another worker): install your variant on A, B, C; add a debug option in your module to script per-node fake answers so a disagreement path can be forced; the coordinator on A runs a problem end to end across all three; confirm the round flow, the audit-log entries, and save one screenshot to docs/benchmarks/choir-fake-A.png.
- Everything is written against LlmClient only, so swapping in the real client needs no change in your package.
