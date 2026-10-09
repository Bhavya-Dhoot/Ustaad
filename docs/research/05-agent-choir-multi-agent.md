# 05 - Agent Choir: 2-3 phones, small local LLMs, one task

Date: 2026-10-09. Status: research worker output. Confidence labels: (verified) = read in a fetched source this session; (secondary) = seen only in a search summary or blog; (unverified) = could not confirm; (inference) = my reasoning, not a measured result.

## 0. Verdict in five lines

1. Ensembles of small models beat a single run of the same model when the task has a checkable or low-entropy answer (math word problems, multiple choice, code with tests) and the single-run accuracy sits in the middle band (roughly 30-80%). The gain comes mostly from sampling plus voting or verification, not from "talking".
2. They do NOT reliably beat a single bigger model on knowledge-heavy tasks (MMLU-Pro, GPQA, ARC-Challenge-type recall), because wrong answers are correlated across copies and across similar models.
3. Debate and free-form "society of mind" chat do not beat plain self-consistency at equal sample count (verified: Smit et al. ICML 2024; Huang et al. ICLR 2024; "Stop Overvaluing MAD" 2025).
4. The real advantage of 3 phones is throughput, not intelligence: 3 phones decode 3 samples in the time one phone decodes 1 (inference). The honest baselines are (a) one phone sampling 3x sequentially, (b) one phone with llama.cpp batched slots, (c) one phone running the biggest model that fits. The demo must show at least (a).
5. Best protocol: parallel independent solve (3 phones, different prompts/personas, ideally 2 model families) -> short structured answer exchange -> vote; only if no majority, one critic round -> judge with tool/verifier (code execution, regex, unit test) rather than a vibes judge.

## 1. Evidence base (2024-2026)

### 1.1 Pattern-by-pattern

| Pattern | Key evidence | Verdict for 3-8B on phones |
|---|---|---|
| Self-consistency / sampling + majority vote | "More Agents Is All You Need" (Agent Forest), 40 samples: Llama2-13B GSM8K 0.35 -> 0.59, MATH 0.03 -> 0.09, MMLU 0.42 -> 0.51, HumanEval 0.14 -> 0.18; Llama2-70B GSM8K 0.54 -> 0.74, HumanEval 0.24 -> 0.33; gains larger for weaker models and harder tasks, and taper at extreme difficulty (verified) https://arxiv.org/html/2402.05120v2 . Llama-3.1-8B GSM8K greedy 87.5 -> maj@16 92.5 (secondary; table alignment uncertain) https://arxiv.org/pdf/2410.12608 . Llama-3.1-8B MATH-500 CoT 49.8 -> majority 66.4; Qwen2.5-7B 76.8 -> 83.6; Llama-3.2-3B 41.4 -> 58.4 (verified, N is large, up to hundreds; not 3) https://arxiv.org/html/2502.06703v1 | Strongest, cheapest pattern. But those numbers use N=16-256 samples, not N=3. With N=3 expect a small fraction of that gain (inference). |
| Self-consistency failure mode | On GPQA Diamond, majority vote lowered per-problem accuracy on 56.6% of problems for Qwen2.5-7B and 65.7% for Llama-3-8B (N=64); aggregate flat to mildly positive. Single-author preprint, not peer reviewed (secondary) https://arxiv.org/abs/2608.11403 | Hard science recall: do not expect help. |
| Mixture-of-Agents (MoA) | Layered proposers + aggregator; AlpacaEval 2.0 LC win rate 65.1% with open models vs GPT-4o 57.5% (verified via search) https://arxiv.org/abs/2406.04692 . Uses big models (Qwen1.5-110B, Llama-3-70B, WizardLM-8x22B). | Evidence is for 70B+ proposers on open-ended chat quality, not for 3-8B on reasoning. |
| Self-MoA ("is mixing models good?") | Same-model samples aggregated beat mixed-model MoA: +6.6 on AlpacaEval 2.0 (65.7 vs 59.1), +3.8% avg over MMLU/CRUX/MATH. MMLU Qwen2-7B 69.01 vs best mixed 68.90; Llama-3.1-8B 71.27 vs 70.73; CRUX 52.62 vs 47.00; MATH (Qwen2-Math-7B) 69.80 vs 67.62. Quality beat diversity; adding a weaker model hurt, adding a similarly strong one helped (~+2%) (verified) https://arxiv.org/html/2502.00674v1 | Important design rule: do not put a weak model on a phone just for "diversity". Mix only models of similar strength. |
| Multi-agent debate (Du et al.) | GSM8K 77.0 single, 75.0 self-reflection, 85.0 debate with 3 agents x 2 rounds (secondary; check PDF) https://arxiv.org/pdf/2305.14325 . Used ChatGPT-era models. | Promising headline, but see next rows. |
| Debate vs strong baselines | MAD "do not reliably outperform" self-consistency / Medprompt in default form, more tokens; tuning "agreement intensity" rescues Multi-Persona (verified via search) https://arxiv.org/pdf/2311.17371 . 5 MAD methods x 9 benchmarks x 4 models: MAD often fails to beat CoT and SC; model heterogeneity is the consistent fix (verified abstract) https://arxiv.org/html/2502.08788v3 . Huang et al.: debate not better than self-consistency at equal response count (secondary) https://arxiv.org/pdf/2310.01798 | Do not build a chat-room debate. Use debate only as a conditional tie-break round. |
| Intrinsic self-correction / critique | LLMs cannot self-correct reasoning without external feedback; accuracy sometimes drops (verified via search) https://arxiv.org/pdf/2310.01798 | A critic helps only if grounded: run code, check arithmetic with a tool, compare to the question's constraints. Never "are you sure?" |
| LLM-as-judge | Self-preference bias: judges favour lower-perplexity (own-style) text (secondary) https://arxiv.org/pdf/2410.21819 . Correctness-heavy tasks transfer poorly from preference-style judging (secondary blog; unverified). I found no solid number for a 7B generic judge on reasoning correctness (unverified). | Judge must be a different model than the winner's author and should rank only by checkable criteria. |
| Verifier / PRM + test-time compute | Compute-optimal TTS with a PRM: Llama-3.2-3B 41.4 CoT -> 78.2 (vs Llama-3.1-405B CoT 71.4 on MATH-500), Llama-3.2-1B 26.0 -> 66.2, Llama-3.1-8B 49.8 -> 80.6, Qwen2.5-7B 76.8 -> 91.0; but 72B models gain only ~9.5%, harder AIME24 gains are small (Qwen2.5-7B 13.3 -> 33.3-36.7), and for Llama-3.1-8B some PRM searches were worse than majority vote (verified) https://arxiv.org/html/2502.06703v1 | The "3x small beats 1x big" claim is real on MATH-500 but needs a trained PRM and many samples (hundreds). Not reproducible with 3 phones and a prompted judge. |
| Generative verifiers | Gemma2-9B GenRM, GSM8K best-of-N 73% -> ~93% (secondary) https://proceedings.iclr.cc/paper_files/paper/2025/file/214308a2d5e3f83ef9ad2739e1cbc46d-Paper-Conference.pdf | Verifier quality is the lever. Requires fine-tuned verifier (not an off-the-shelf phone model). |
| Repeated sampling with a checker | Coverage scales log-linearly with samples; real wins when answers are automatically verifiable e.g. code tests (secondary) https://arxiv.org/pdf/2407.21787 | Best fit for the Choir: code or math with a deterministic checker. |
| Error correlation limits | LLMs often agree on the same wrong answer; correlation rises with capability and training similarity (Goel et al. 2025, secondary); a ceiling claim that any pick-one-of-members policy is capped at 1 - (rate all members fail) (unverified preprint summary) https://aiweekly.co/alerts/josef-chen-shows-the-wrong-metric-caps-multi-model-llm-gains | Core risk; see section 6. |

### 1.2 Where small-model ensembles help most and least

Helps most (mid-band accuracy, checkable answer, multi-step reasoning, weaker model):
- GSM8K and MATH-500 style word and symbolic math. Relative gains in Agent Forest were largest for weak models on MATH (+120% to +200% relative, from tiny bases) and GSM8K (+37% to +69%) (verified).
- Code with unit tests (HumanEval, MBPP, CRUX): pass@k with a runnable checker. Agent Forest HumanEval gains were modest (0.14 -> 0.18, 0.24 -> 0.33) without a test oracle (verified); a test oracle is the key upgrade (inference).
- Multiple-choice with short rationales where models are not saturated: MMLU +5-9 pts for old Llama2 at 40 samples (verified); for modern 7-8B only ~+1 pt for Self-MoA (verified).

Helps least or hurts:
- Knowledge recall (MMLU-Pro, ARC-Challenge, GPQA): the same missing fact is missing in every copy. GPQA majority vote hurt most problems for 7-8B (secondary).
- Saturated tasks (GSM8K for Qwen2.5-7B is already near 90%): little headroom.
- Very hard tasks (AIME-level): gains taper off or vanish (verified, Agent Forest and TTS paper).
- Open-ended chat: MoA wins are real, but only with 70B-class proposers.

I did not find a clean 2025-2026 paper reporting 3-way ensembles of 3-8B models on ARC or MMLU-Pro with exact deltas (unverified); treat these two as "expect near-zero" until measured on the actual phones.

### 1.3 The honest compute accounting

- Three phones give about 3x aggregate decode, but one phone can also batch (llama.cpp parallel slots). Decode on a phone is memory-bandwidth bound, so batching 3 sequences likely costs far less than 3x time (inference, not measured here). That single-phone batched baseline may erase the wall-clock advantage; measure it before claiming one.
- Multi-agent systems pay extra tokens. Agent Forest tokens grow linearly with agents (verified); MoA aggregators ingest all proposals (secondary).
- A fair claim for the pitch: "same total tokens, same wall-clock, better accuracy on X", plus a privacy and no-single-device-RAM argument (three 5 GB models fit where one 20 GB model might not; gpt-oss-20b needs ~16 GB RAM and a 24 GB phone is the practical floor (secondary) https://simonwillison.net/2025/Oct/10/gpt-oss-20b-snapdragon ).

## 2. Role design for 3 phones

### 2.1 Roles

| Phone | Role | Model suggestion | Why |
|---|---|---|---|
| A | Solver 1 (step-by-step CoT) | Qwen3-8B or Qwen3-4B, non-thinking mode, Q4 | Qwen3 leads small-model BFCL overall among those listed (about 42.6% vs Llama 3.1 8B 25.8%, Gemma 3 12B 30.4%; third-party aggregator, approximate) https://www.datalearner.com/ai-models/leaderboard/berkeley-function-calling-leaderboard |
| B | Solver 2 (different method: work backwards / write code to compute) | Different family of similar strength (e.g. Gemma 3/4 E4B or Llama 3.1 8B) | Heterogeneity helps MAD (verified, https://arxiv.org/html/2502.08788v3) but Self-MoA says keep strength matched (verified). Use same-strength models. |
| C | Critic then Judge (two hats, sequential) | Strongest model that fits | Critic runs only on disagreement; judge ranks by checks. Judge must not be the author of the candidate it prefers. |
| Any | Tool executor (not an LLM) | Kotlin code | Runs code/arith/regex/unit tests: the external feedback that makes critique work (Huang et al. finding). |

With 2 phones: A and B solve, either phone judges only when they disagree. With 3 phones, rotate the judge each task to cancel self-preference.

### 2.2 Round structure (target: under 60 s typical, 90 s max)

Round 0 (host, 0.5 s): task string, task type, deadline, answer schema, per-role prompt.

Round 1, parallel independent solve (no peeking), cap 300 generated tokens each, answer must end with a JSON line: `{"answer":"...","conf":0-1,"key_steps":["...","..."]}` (key_steps max 3 items, 25 words each).

Round 2, exchange (only if answers differ): each phone receives the OTHER answers as short messages (answer + key_steps + conf, never full CoT), cap 150 tokens: "Find the single step where they differ, check it, then keep or change your answer." Cap 1 round.

Round 3, adjudicate (only if still no 2/3 majority): judge phone gets the three candidates (<= 600 input tokens), runs tool checks, returns `{"winner":"A|B|C","why":"<=40 words","check":"tool result"}` in <= 100 tokens.

Stop rules: (1) unanimous after round 1 -> stop, mark "unanimous" (but flag "all confident, no tool check" as a risk, see section 6); (2) 2/3 majority after round 2 with tool check passing -> stop; (3) deadline hit -> return plurality with "low confidence" flag; (4) never more than 3 rounds (debate returns diminish: Du et al. used 2 rounds, Hegazy 4 rounds with heterogeneous models, secondary).

### 2.3 Picking the final answer

Order of precedence: (1) answer that passes a deterministic check (code tests, arithmetic recomputation, schema or regex); (2) majority of normalised answers (string/number normalisation in Kotlin, not by LLM); (3) confidence-weighted vote only if conf is calibrated on a dev set (small models are over-confident; do not trust raw conf, inference); (4) judge pick; (5) abstain and escalate to human. Output always includes the vote table, so a human sees disagreement.

### 2.4 Token budgets and wall-clock at 15-30 tok/s per phone

Measured anchor: Qualcomm AI Hub lists Qwen3-8B w4a16 on the NPU at about 13.7 tok/s (8 Elite) and 16.1 tok/s (8 Elite Gen 5) at 4096 context (verified, vendor page) https://aihub.qualcomm.com/models/qwen3_8b . A 5.1 tok/s Llama 3.1 8B NPU result on the previous-gen chip also exists (secondary) https://grapeup.com/blog/running-llms-on-device-with-qualcomm-snapdragon-8-elite . The "up to 220 tok/s" marketing number has no model size attached and should not be used (secondary). I found no published llama.cpp 8B decode number for 8 Elite Gen 5 (unverified); measure with `llama-bench` on the actual phones. The 15-30 tok/s range in the brief is plausible for 4B-8B Q4; 4B or Gemma E4B will be at the top, 8B near the bottom (inference).

| Step | Tokens out | At 30 tok/s | At 15 tok/s |
|---|---|---|---|
| Prefill (about 400 token prompt; prefill is faster than decode, assume 2-4 s) | - | 2 s | 4 s |
| Round 1 solve (parallel, slowest phone sets time) | 300 | 10 s | 20 s |
| Round 2 exchange (only on disagreement) | 150 | 5 s + 2 s prefill | 10 s + 3 s prefill |
| Round 3 judge (only if needed) | 100 | 3.5 s + 2 s prefill | 7 s + 3 s prefill |
| Network and Kotlin overhead (local Wi-Fi, small JSON) | - | under 1 s | under 1 s |
| Total, agree after round 1 | | about 13 s | about 25 s |
| Total, full 3 rounds | | about 35 s | about 65 s |

Budget caps: total generated tokens per task <= 3 x 300 + 3 x 150 + 100 = 1450. Disable thinking mode (Qwen3 "/no_think" or equivalent): a thinking model can burn thousands of tokens and blow the 90 s limit. Keep contexts small to avoid prefill and KV-cache growth. Thermal throttling during repeated runs is a real risk (inference); pre-warm and cap runs per minute.

## 3. Message protocol, tool-calling, orchestration

### 3.1 Wire format (use this)

One JSON object per message over HTTP (or WebSocket) on the LAN, each <= 1 KB:

```json
{"v":1,"task_id":"t-17","round":1,"from":"B","role":"solver",
 "type":"answer","answer":"42","conf":0.7,
 "key_steps":["...","..."],"tool_checks":[{"name":"recompute","ok":true}],
 "tokens":212,"ms":9400,"model":"qwen3-8b-q4","ts":1760000000}
```

Types: `task`, `answer`, `critique` ({target, claim, evidence}), `verdict`, `abort`. Cap `key_steps` and `critique` strings. Reject oversize messages (prompt-injection and context-bloat guard).

Force valid JSON at decode time rather than hoping: llama-server can constrain output to a JSON schema through `response_format` / `json_schema` and GBNF grammar, which guarantees syntax but not correct content; always validate in code (secondary docs mirrors) https://docs.liquid.ai/deployment/on-device/llama-cpp/structured-output . Caveat: a 2025 issue reported an error when combining json_schema and grammar (and with tool setups) at the same time (secondary; fix status unverified) https://github.com/ggml-org/llama.cpp/issues/11847 . Use one mechanism per request.

### 3.2 Tool-calling support in small models

| Model | Status |
|---|---|
| Qwen3 (4B/8B) | Hermes-style tool calls, native handler in llama.cpp; best small-model BFCL overall of the group (about 42.6% / 35.5%, third-party; non-live AST accuracy high 80s) (secondary) |
| Llama 3.1 8B | Native handler in llama.cpp; BFCL overall about 25.8% (secondary) |
| Gemma 3 | No native tool tokens; Gemma 3 4B about 19.6%, 12B about 30.4% BFCL overall (secondary). Gemma 4 E2B/E4B are reported to support function calling and 128K context (secondary; release details inconsistent across sources, verify on the model card) https://www.jetson-ai-lab.com/models/gemma4-e4b |
| Phi | Not verified in this session (unverified); treat as prompt-based JSON only |
| gpt-oss-20b | Requires the harmony response format; tools via the commentary channel, answer in the final channel (secondary) https://docs.unsloth.ai/new/gpt-oss-how-to-run-and-fine-tune . 16 GB minimum, impractical as a routine phone model |

llama.cpp `llama-server --jinja` exposes OpenAI-style `tools` and `tool_calls`, with native handlers for Llama 3.x, Hermes 2/3, Qwen 2.5, Mistral Nemo and a generic fallback that costs more tokens (secondary) https://mintlify.com/ggml-org/llama.cpp/advanced/function-calling . Practical advice: do not rely on the small model to choose tools. Make the Kotlin orchestrator run fixed deterministic checks after each answer (code run, arithmetic, schema), and let the model only produce the answer JSON. That removes the weakest link (agentic tool choice at 4-8B).

### 3.3 A2A and MCP: use or ignore?

- A2A: reached v1.0 in early 2026 under Linux Foundation governance, JSON-RPC/gRPC/HTTP bindings, signed Agent Cards (secondary) https://linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year . Local-network discovery is not in the core spec; only an individual Internet-Draft proposes DNS-SD/mDNS (secondary) https://ftp.kaist.ac.kr/ietf/draft-zhao-a2a-dns-sd-00.html . Koog documents an A2A server/client (docs say v0.3.0; artifact versions differ by source) (secondary) https://blog.jetbrains.com/ai/2025/10/koog-a2a-ki-agents-in-kotlin-vernetzen . Recommendation: IGNORE for the hackathon core loop (adds Agent Card, task lifecycle, auth for three trusted phones). Use your own 6-field JSON above, but name the fields to match A2A (task, message, artifact) and keep an Agent Card JSON at `/.well-known/agent-card.json` as a cheap "enterprise-ready" talking point. Use NSD/mDNS (Android NsdManager) for discovery.
- MCP: useful for the TOOLS side (a phone's calculator, doc reader, file search exposed as MCP tools), not for phone-to-phone chat. The official Kotlin SDK has client and server modules with stdio/SSE/WebSocket transports (secondary; version numbers conflicting; Android suitability unverified) https://kotlin.sdk.modelcontextprotocol.io . Recommendation: skip for the demo; mention as extension point for the contract-review use case.

### 3.4 Kotlin/JVM orchestration, no Python on device

| Library | Android / local llama.cpp | Notes | Pick? |
|---|---|---|---|
| Koog (JetBrains) | Multiplatform: JVM, Android, iOS, JS, Wasm claimed; v1.0 announced KotlinConf 2026 with local LiteRT model support on Android and OpenTelemetry across targets (secondary) https://blog.jetbrains.com/ai/2026/05/koog-1-0-is-out-stable-core-better-interop-and-multiplatform-observability/ ; README page I fetched is internally inconsistent on Android target (lists Android in features but JVM/JS/Wasm/iOS in requirements) (verified inconsistency) https://github.com/JetBrains/koog . `OpenAILLMClient` takes `OpenAIClientSettings(baseUrl=...)`, so pointing it at `http://localhost:8080` of llama-server should work, Ktor-based (secondary; unrun) https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/ai.koog.prompt.executor.clients.openai/-open-a-i-l-l-m-client/index.html . Has MCP tools, A2A, persistence, Apache-2.0 | Yes, primary pick, with a 2-hour spike to confirm Android build and custom base URL before committing |
| LangChain4j | `OpenAiChatModel.builder().baseUrl("http://host:8080/v1")` works for any OpenAI-compatible server (secondary) https://docs.langchain4j.dev/integrations/language-models/openai-compatible . No official Android statement; one community report of an Android regex failure (secondary, undated) | Fallback only. A third-party `llama-langchain4j` JNI adapter ships Android binaries (secondary; unvetted) https://libraries.io/maven/net.ladenthin:llama-langchain4j |
| Spring AI, Embabel | Server-side Spring stack; no Android target found (unverified) | Skip |
| Arc | Not researched in this session (unverified) | Skip |
| Hand-rolled | Ktor client + kotlinx.serialization + coroutines `async` fan-out and a 100-line state machine | Genuinely viable: the protocol in section 2 is a fan-out/vote/conditional-round loop, not a general agent graph. If Koog Android build fights you, drop to this |

Per CLAUDE.md simplicity rule, I would start hand-rolled with Koog's OpenAI client as the HTTP layer only if it builds in minutes; otherwise plain Ktor. Orchestration pattern: each phone runs llama-server (or an in-process engine behind the same OpenAI-style interface) on `localhost`; the Kotlin app exposes one `/solve` endpoint to peers; host phone does fan-out with `coroutineScope { peers.map { async { ... } } }` with per-call timeouts.

## 4. Human oversight and enterprise use

Use cases that fit: clause-by-clause contract review ("is there an uncapped indemnity? termination notice period?"), code review of a diff, policy compliance checks. They fit because: data never leaves the device or LAN, answers can be extractive (quote the clause), and disagreement is itself the signal "a human should look here". Do not claim legal or compliance accuracy; there is no measured number for this task class in this research (unverified), and shared-error risk (section 6) is highest for domain knowledge.

Design for review:
- Findings are quotes plus a pointer (page, clause), never free paraphrase only. Verify by string match in Kotlin that the quote exists in the source (cheap deterministic check that kills fabricated citations).
- Show per-finding vote: `3/3 agree` (green), `2/3` (amber, show dissent), `split` (red, human decides). Never auto-approve; human clicks accept/reject, and that decision is logged.
- Reasoning trail on screen: three lanes (one per phone), each showing role, model, tokens, ms, `key_steps`, then a merged "decision tree" row. Show only the structured key_steps, not raw CoT (raw CoT is long and not faithful; inference).
- Audit log: append-only JSONL, one line per message exactly as exchanged (the JSON in 3.1), plus: prompt hash, model file SHA-256, quantisation, llama.cpp commit, sampling params and seed, tool results, human decision, timestamp, hash chain (each line contains hash of the previous line). Export as a signed bundle. Koog has OpenTelemetry support if spans are wanted (secondary).
- Retention and PII: log stays on device; redact option.

## 5. Demo design (under 90 s, visible disagreement then convergence)

Task (chosen so single small models are mid-band and answers are checkable): a 4-6 step word problem with a trap, or a short function spec with hidden unit tests. Example class: "multi-step GSM8K/MATH-hard-ish problem the single model gets right about 50% of the time" (calibrate: run the chosen model 20x offline, pick problems with 30-70% single-run accuracy; run a dev set of 30 and show the measured delta, not an anecdote). Use 3 prepared problems, 1 live, so the failure case is not the hero demo.

Timeline (inference-based plan, to be timed on the real phones):
- 0-5 s: task appears on all three screens plus a projector view; baseline phone D (or a 4th panel) starts solving the same task alone, one model, one sample.
- 5-25 s: three lanes stream tokens live, different colours; answers land as chips: A "42", B "36", C "42".
- 25-30 s: red "DISAGREE" banner. Round 2: lanes show the one differing step highlighted.
- 30-45 s: tool check runs (code executes, arithmetic recompute); B flips to 42 or judge picks 42 with the check result displayed.
- 45-55 s: "CONVERGED 3/3, verified by tool" and baseline phone's answer shown beside it, correct or wrong. Honest framing: if the baseline gets it right sometimes, say so, and show the dev-set scoreboard.
- 55-90 s: scoreboard slide and the audit-log line scroll.

Metrics to display (live, all measurable): per-agent answer + confidence + tokens + ms; agreement matrix; rounds used; total tokens vs baseline tokens; wall-clock vs baseline wall-clock; tool-check pass/fail; and accuracy on the offline dev set: single vs 1 phone x3 sequential vs Choir, with n and a confidence interval (with 30 items an interval is wide; state n). Energy or battery % per phone if easy. Always include the "single phone sampling 3 times and voting" bar; omitting it would overstate the case.

Baseline placement: the baseline is a 4th screen or split screen on the host, the same model as the strongest member, same prompt, same token cap, started at the same instant.

## 6. Five risks

| # | Risk | Evidence | Mitigation |
|---|---|---|---|
| 1 | Models agree on the same wrong answer (correlated errors); unanimous but wrong looks like strong confidence | LLMs often collapse onto one wrong answer; correlation rises with capability and similar training (secondary) ; majority voting hurt most GPQA problems for 7-8B models (secondary, unreviewed) | Two model families; different prompting methods (reasoning vs code); deterministic tool check as final arbiter; display "unanimous, not tool-verified" as a warning; choose demo tasks with checkable answers |
| 2 | Extra complexity yields no measurable gain over cheap baseline (sampling 3x on one phone, or batching) | Debate does not beat self-consistency at equal samples (verified); N=3 is far below the N=16-256 where reported gains live (verified) | Run the equal-compute dev-set comparison before the pitch; if no gain, pitch the Choir as audit, privacy and oversight, not accuracy |
| 3 | Weak judge or critic: self-preference bias, "are you sure" flips right answers to wrong | Intrinsic self-correction can reduce accuracy (verified via search); judges prefer own-style text (secondary) | Judge never judges its own author; critic must cite a check; keep rounds <= 2; default to tool/majority over judge |
| 4 | Latency, thermal throttling and memory: 8B Q4 near 14-16 tok/s on NPU (vendor page), thinking mode explodes tokens, three phones heat up, Android kills background apps, Wi-Fi hiccups | Qualcomm figure 16.1 tok/s for Qwen3-8B at 4K context on 8 Elite Gen 5 (verified); gpt-oss-20b needs about 16-24 GB RAM (secondary) | Non-thinking mode, hard token caps, 4B-class models as fallback, foreground service + wake lock, pre-warmed model, per-call timeouts with "2 of 3" quorum, recorded fallback video |
| 5 | Prompt injection and message poisoning between agents; also fabricated citations in contract/code review | Agents ingest each other's text and the document text (inference; standard multi-agent risk) | Peers exchange only schema-validated JSON with length caps; documents are data, never instructions; quote-existence check in Kotlin; no tool with side effects reachable from model output; signed audit log |

Other things I could not settle (unverified): exact llama.cpp 8B decode speed on Snapdragon 8 Elite Gen 5; whether Koog's Android target builds with a custom OpenAI base URL; Phi and Arc status; any 2025-2026 paper with 3x 7B ensemble numbers on ARC and MMLU-Pro.

## 7. Recommended next steps (each with a check)

1. Spike (2 h): Koog on Android + llama-server on localhost via `OpenAIClientSettings(baseUrl)`. Verify: one chat completion round-trips and one `tool_calls` round-trips on a phone.
2. Benchmark (half day): `llama-bench` Qwen3-4B/8B and one Gemma/Llama at Q4 on each phone, plus 3 parallel slots on one phone. Verify: tok/s numbers recorded; decide if the single-phone batched baseline is competitive.
3. Offline eval (1 day): 100 GSM8K/MATH items + 50 HumanEval-style items; arms: single, 3x sequential vote, Choir (2 rounds), Choir + tool check. Verify: accuracy table with n; pick demo problems from the 30-70% single-run band.
4. Build protocol (1 day): section 2/3.1 as written, JSONL hash-chained log. Verify: replay a log reproduces the final answer.
5. Demo rehearsal with stopwatch: Verify: end-to-end under 90 s on three consecutive runs.

## Sources (all cited inline above)

Mixture-of-Agents https://arxiv.org/abs/2406.04692 ; Self-MoA https://arxiv.org/html/2502.00674v1 ; More Agents Is All You Need https://arxiv.org/html/2402.05120v2 ; Test-time scaling 1B vs 405B https://arxiv.org/html/2502.06703v1 ; Should we be going MAD https://arxiv.org/pdf/2311.17371 ; Stop Overvaluing MAD https://arxiv.org/html/2502.08788v3 ; LLMs cannot self-correct https://arxiv.org/pdf/2310.01798 ; Multiagent debate (Du) https://arxiv.org/pdf/2305.14325 ; Self-preference bias https://arxiv.org/pdf/2410.21819 ; Large Language Monkeys https://arxiv.org/pdf/2407.21787 ; GenRM https://proceedings.iclr.cc/paper_files/paper/2025/file/214308a2d5e3f83ef9ad2739e1cbc46d-Paper-Conference.pdf ; Koog https://github.com/JetBrains/koog , https://blog.jetbrains.com/ai/2026/05/koog-1-0-is-out-stable-core-better-interop-and-multiplatform-observability/ ; LangChain4j https://docs.langchain4j.dev/integrations/language-models/openai-compatible ; MCP Kotlin SDK https://kotlin.sdk.modelcontextprotocol.io ; A2A https://linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year ; Qwen3-8B on Qualcomm https://aihub.qualcomm.com/models/qwen3_8b ; gpt-oss on phone https://simonwillison.net/2025/Oct/10/gpt-oss-20b-snapdragon ; llama.cpp function calling https://mintlify.com/ggml-org/llama.cpp/advanced/function-calling
