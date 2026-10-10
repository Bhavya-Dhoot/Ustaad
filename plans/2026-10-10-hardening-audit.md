# Adversarial audit, hardening and a never-idle mode (gate 9)

Founder goal, 2026-10-10 13:25: a proper adversarial end-to-end audit (Codex with gpt-6.1-sol plus cheap subagents); find performance and throughput gains; make the protocol production grade (no silent failure, a fallback for every path); handle distribution, dynamic allocation, memory and CPU or thermal throttling properly; and add a mode that keeps the phones from sitting idle so usage stays consistent.

Standing limits: Phone A is reserved by the founder (CLAUDE.md), so phone checks run on B and C. Phone A keeps build c2ca589 until released, so nothing in this gate may break the wire format between a new build and c2ca589. Every number comes from the phones.

## Phases
1. Audit, read-only, in parallel: Codex (gpt-6.1-sol, high effort) attacks the distributed behaviour end to end; four Sonnet reviewers each take one area: scheduling and throughput, resources (memory, thermal, CPU, GPU, battery, background survival), link and protocol, and failure handling in the features (Choir, Data Union, Knowledge Bank, MemoryCloud, agent endpoint).
2. Triage by the orchestrator into docs/review/2026-10-10-adversarial-audit.md: P0 can lose work or hang in a demo; P1 measurable throughput or latency gain; P2 hardening. Each finding gets fix, defer (with reason) or reject (with reason).
3. Fixes by Sonnet builders in worktrees, split by package so they do not collide. The never-idle mode is built in this phase.
4. Check on phones B and C: unit suite, build, install on B and C, the existing acceptance scripts, and a measurement for every P1 change (before and after on the same phone, same thermal state). A change that does not measure better is reverted.
5. Gate artifact gates/gate-9-hardening.md and status lines.

## Never-idle mode ("Keep busy"), the design to build
- A switch in Settings, off by default. When on, a phone that has had no model work for a short while runs filler work at the lowest priority until real work arrives.
- Filler work is useful, not a spin loop: Choir evaluation problems and benchmark prompts through the normal model path, in short jobs (a few seconds each) so a real request never waits longer than one short job; a real request cancels the filler job in flight.
- Duty is steered by heat and power, re-read before every filler job: Thermal Status 0 or 1 full duty; 2 half duty; 3 or above pause until it cools; on battery below a floor pause. Nothing in this mode may push a phone into throttling that slows real work.
- The swarm scheduler never routes real jobs away from a phone because it is doing filler work, and filler work is never sent to another phone.
- Visible: the status strip says "keeping busy" with the duty in use; the audit log counts filler jobs and tokens; filler numbers are never mixed into benchmark or scoreboard figures.
- Measured on a phone before the gate closes: utilisation over 15 minutes with the mode on (share of time the model is generating, thermal status every minute), and the delay it adds to a real request arriving mid-filler.

## Plan lines
Constraint: correctness under failure first, then throughput; phone time on two phones; wire compatibility with c2ca589.
Failure modes: an audit finding that is plausible and wrong (each P0 and P1 is confirmed in the code by the orchestrator or by a failing test before anyone fixes it); a "fix" that slows the phones (before and after measurement, revert if not better); fixes colliding (one builder per package set, merged one at a time with the full suite); the never-idle mode overheating phones or starving real work (thermal gate, short cancellable jobs, measured delay); a change that breaks talking to Phone A's older build (reviewed for wire changes; a B and C check with A online as it is).
Ledger: Codex audit and four reviewers delegated (independent reads, cheap, verifiable against the code); triage kept by the orchestrator (it decides what is real); fixes delegated to builders per package; phone checks and the gate artifact kept by the orchestrator.

## Continuation on two phones (2026-10-10, 16:00): Phone A held back again by the founder
Constraint: phone time and heat. Two phones, one model slot each, and a warm phone decodes about 20 % slower, so one experiment per phone at a time and the long soak last.
1. Phone C: the fixed cost per request inside the model server (16 to 20 prompt tokens take 0.77 to 0.83 s although the same server prefills at 126 tok/s). Verify: fixed cost per variant; if a setting removes it, bench32 on C before and after.
2. Phone B: n-gram speculation in the model server. Verify: decode tok/s with identical output, alternating order.
3. Orchestrator: reaction to the low-memory signal, keep busy with the app in the background, the Choir majority switch if the Choir runs three voices on two phones.
4. One builder: whatever step 1 recommends plus a plain sentence for the cut-answer error; build; install on B and C; regression.
5. A 60 minute keep-busy soak on B and C with the chat server memory sampled. Verify: share of time generating, thermal trace, memory trace, failed jobs.
Failure modes: an engineer leaves a phone on a non-default server load (caught: the orchestrator reads the state of both phones before step 3); a reported gain that is only heat (caught: alternating order, a thermal column, and a bench32 re-run by the orchestrator); an agent touching Phone A (each brief pins one serial and forbids the address); a hung agent (stopped, phone restored with a default reload).
Ledger: 1 and 2 delegated to Sonnet inference engineers (bounded, checkable by a re-run, their exploration stays out of the orchestrator context); 3 direct (a few calls); 4 delegated (Kotlin); 5 direct (one background script).
