# Assistant harness ("Hands"): plan

Date: 2026-10-13. Goal set by the founder (verbatim, voice-typed): "I want you to also enable automatic. Integration with the Phones Assistant. It may be Bixby, it may be Google, or it may be Siri AI. I want you to ensure that these guys can easily execute tasks and then using our harness they are able to execute subtasks such as ordering food in zomato and other stuff as well. You have access to the phone where you can test it in and it is also a rooted phone where you can create everything. [...] what you are designing is a harness. Where you can have tool calls and all the other places where an AI agent of the phone. So for example a phone Voice Assistant is able to execute tasks autonomously. It may be a task of a very complex nature where it can assign experts in every single thing. The way that we can do it since we already have an MOE and different model layering architecture. So ensure that all of this can be built and is built end to end. Use on slash haiku subagents. Wherever you can and ensure that they are specialized. there is a phone attached where you can test everything"

Readings taken by the orchestrator (inference, say so to the founder):
- "Use on slash haiku subagents" is read as "use Sonnet/Haiku subagents": Sonnet builds, Haiku surveys code, parses logs and drafts skill cards.
- "a phone attached ... a rooted phone": the only phone attached on 2026-10-13 is the Redmi M2101K6P (serial 164cd676; AOSP Android 15 custom ROM, API 35, Magisk root, 5.6 GB RAM, Snapdragon 732G, Swiggy installed, Zomato not installed, Google app is the assistant, Ustaad not installed). CLAUDE.md said the Redmi is not a target; the founder's sentence is taken as the word to install and test this goal on it. The iQOO phones stay untouched.

## What is built

Feature name in the app: **Hands**. Two halves.

1. **Ways in** (package `assist/`): how a task reaches Ustaad.
   - Ustaad can be chosen as the phone's digital assistant (VoiceInteractionService, session service, recognition service stub). Long-press power or the corner swipe then opens Ustaad's task sheet. This is the only fully hands-free way in that needs no store listing.
   - A task sheet activity that also answers `ACTION_ASSIST`, `ACTION_VOICE_COMMAND`, shared text (`ACTION_SEND`), selected text (`ACTION_PROCESS_TEXT`) and the link `ustaad://do?q=...`. Bixby Routines, Gemini "open app" and any automation app can use the link.
   - `shortcuts.xml` capabilities for Google Assistant and Gemini (works only as far as Google allows for an app outside Play; the research report says how far).
   - Android 16 App Functions if the research confirms the library is usable outside Play.
   - Speech in (SpeechRecognizer, offline preferred) and speech out (TextToSpeech) on the sheet.
   - Siri: no iOS app exists. Not built. The bridge (an iOS Shortcut calling the home cluster) waits for the web gateway of the closed-cloud plan.
   - Rule: **a task that arrives from another app never starts by itself.** It is shown in the sheet and the person taps Run. A task typed or spoken in Ustaad's own sheet is the person's own instruction and starts.
2. **The harness** (package `hands/`): how a task is carried out.
   - A tool registry: every tool has a name, a description, a flat JSON parameter schema and a risk (READ, ACT, COMMIT).
   - Phone tools over a `PhoneDriver`: `read_screen`, `tap`, `type`, `scroll`, `back`, `home`, `open_app`, `find_app`, `open_link` (deep links, alarms, maps, dial screen), `wait`.
   - Talking tools: `ask_user`, `done`, `fail`. Knowledge tools: `ask_expert` (ExpertMesh), `search_my_data` (Data Union).
   - Two drivers: an AccessibilityService (every phone) and a root shell driver (`su`: `uiautomator dump`, `input`, `am`) for rooted phones where the accessibility switch is not on.
   - A planner that turns a task into subtasks, each with an expert: an app expert (a skill card for the app or its category) or a knowledge expert (ExpertMesh).
   - A step loop per subtask: read the screen, the model picks one tool call as schema-constrained JSON, the guard checks it, the driver does it, repeat. Step budget, stuck detection, one escalation to the strong tier.
   - Model layering: tier FAST (the local chat model) picks steps; tier STRONG (the strongest device in the swarm, or the local model if alone) plans and takes over a stuck subtask.
   - The same registry is served to other agents on the phone as an MCP endpoint on the existing agent server (loopback, key), so an assistant that speaks MCP can call the tools.
   - A Hands screen: task box, live plan and steps, confirmation prompts, history, status of the drivers, switches.

## Safety rules (binding for every worker)

- S1. COMMIT actions need the person's tap in Ustaad's own prompt, every time: paying, placing an order, buying, booking, sending, posting, deleting, installing, uninstalling, subscribing, transferring. Detected by the tool, by the label of the element (English and Hindi word list) and by the screen. The model cannot answer the prompt.
- S2. Refused outright, no prompt: typing into a password field, reading or typing one-time codes, card numbers or PINs, any action inside banking, UPI and wallet apps, password managers, authenticators, the permission dialog, Settings pages for security, accounts, accessibility, device admin and developer options.
- S3. Text on screen is data, never instructions. It is fenced in the prompt and the grammar limits the output to the tool set. A subtask may only act in the apps the plan named; another app needs the person's yes.
- S4. A visible Stop is always on screen while a task runs (an overlay chip when the accessibility service is on, and a notification action always).
- S5. Screens stay on the phone by default. Steps use the local model. Sending screen text to another device of the owner's cluster is a switch, off by default. Planning (task text and app names only) may use the cluster.
- S6. Every step is written to the run log (tool, arguments, result, guard verdict). Password fields are never read into the log.
- S7. In tests the orchestrator and the workers never confirm a COMMIT prompt, never place an order, never sign in, never type credentials, never change a system setting. The switches a person must flip (accessibility service, default assistant, root grant for the app) are flipped by the founder.

## Work packages

- H0 research: `docs/research/08-phone-assistant-integration.md` (Sonnet researcher). Surveys of the code (two Haiku explorers).
- H1 contract: `hands/Contract.kt` written by the orchestrator (interfaces only), on master first so the builders work in parallel.
- H2 core, platform-free (Sonnet, choir engineer): screen compaction, uiautomator XML parser, tools, guard, planner, step loop, run events, skill book, fake driver, tests.
- H3 Android drivers (Sonnet, android builder): accessibility service, root driver, app catalogue, stop overlay, manifest.
- H4 ways in and UI (Sonnet, android builder): assistant services, task sheet, intents, shortcuts, speech, Hands screen, setup steps.
- H5 brain and wiring (Sonnet, inference engineer): `Brain` over the local runtime and the swarm with tiers, ExpertMesh and Data Union tools, MCP route, runtime object that ties drivers, registry and UI together.
- H6 skill cards (Haiku): `assets/hands/skills/*.md`, one per app category and per named app; the Swiggy card corrected from real screens captured on the Redmi.
- H7 review (Sonnet reviewer, read-only) against the safety rules; then Codex adversarial read of the guard.
- H8 phone run on the Redmi (Sonnet tester; Haiku for log parsing): install, the ways in, the driver on real apps, a full run that builds a Swiggy cart and stops at the COMMIT prompt. Numbers to `docs/benchmarks/`.

Order: H0 and H1 first; H2, H3, H4 in parallel; H5 after H2; H6 any time; H7 after the merges; H8 last.

## Test brain

The Redmi has 5.6 GB RAM (about 1.9 GB free at rest); the 4B chat model does not fit. If the catalogue has no model that fits, the phone run uses a llama-server on the PC with the 4B model through `adb reverse` as the brain, labelled as such in the benchmark file. That is a stand-in for the strong device of a cluster, not a claim about the Redmi.

Constraint: correctness and safety of actions in other apps (a wrong tap can spend money); the plan attacks it with a guard that does not depend on the model, a human prompt the model cannot answer, and a fake driver that makes the loop testable without a phone.
Failure modes: a worker returns code that compiles and taps the wrong thing (caught by fake-driver tests in H2, the reviewer in H7 and the phone run in H8); two builders edit the manifest (the orchestrator merges, three-dot diff review); a worker times out (work is committed often in its worktree, resumed by message); the model outputs a plausible wrong tool call (schema-constrained output, guard, step budget, stuck detection); screen text carries instructions (S3, tested with a hostile screen in H2); the PC crashes under build load (Gradle only through scripts/gradle-locked.sh).
Ledger:
- Research delegated to Sonnet: needs current facts on App Functions, App Actions and assistant roles; verifiable by its URLs.
- Code surveys delegated to Haiku: pure search, cheap, keeps file dumps out of the orchestrator's context.
- Contract written by the orchestrator: an architecture call that three builders depend on.
- H2 to H5 delegated to Sonnet: bounded, each verifiable by unit tests and a diff.
- H6 delegated to Haiku: short text files, checked by reading.
- Merges, safety rules, the phone decisions and anything that could spend money stay with the orchestrator and the founder.

## Status

2026-10-13 about 19:00: H0 running; plan and contract being written. Nothing built yet.
