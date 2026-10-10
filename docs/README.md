# Documents

This folder holds everything written about Ustaad. The front page is [../README.md](../README.md). Below, each document or folder has one line saying what it is and when to read it.

One document is binding: [CLAIMS.md](CLAIMS.md). It lists every claim made about the product, the evidence for it, and the words that may be used. Any sentence about what Ustaad can do, or any number, has to agree with its row. The other documents were written to obey it. Where two documents disagree, take the newer one and check CLAIMS.md.

## Start here

| Document | What it is | Read it when |
|---|---|---|
| [SUMMARY.md](SUMMARY.md) | What was built and measured, in one file: scope, design, what each gate delivered, the table of measured before and after pairs, ideas rejected by measurement, failure handling, Keep busy, the agent endpoint, state of the phones, what is open. Its title says the evening of 2026-10-10; its last section runs to the final audit of 2026-10-11 | You want the whole picture |
| [CLAIMS.md](CLAIMS.md) | Binding. The ledger of claims with a verdict each (proven, proven with caveat, unproven, false as stated), the sentences to drop or reword, and the next measurements | Before you write or say anything about capability or numbers |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The parts of Ustaad and how they talk: the phone as a node, pairing and the signed node API, the scheduler, each module, failure handling | You want to know how it works, or to change it |

## Build and run

| Document | What it is | Read it when |
|---|---|---|
| [BUILD.md](BUILD.md) | Build types (debug and demo), install, pushing models, pairing, checks on the demo build, unit test runs | You build the app or put it on a phone |
| [SETUP.md](SETUP.md) | A clean phone to a ready swarm: first-run taps, models, demo files, pairing, the pre-demo checklist, troubleshooting | You set up phones for a demo |
| [models.md](models.md) | The pinned model files with sizes, SHA-256 and licences | You fetch or check a model |

## The product

| Document | What it is | Read it when |
|---|---|---|
| [KEEP-BUSY.md](KEEP-BUSY.md) | A guide for the owner of a phone to Keep busy and Hammer: the rules, the measurements, what a warm phone does to speed, what is not measured | You turn the mode on, or wonder what it costs |
| [PRD.md](PRD.md) | Requirements as written on 2026-10-09. Section 12 lists what changed as built and overrides earlier sections where they disagree. The rest is history | You want the original intent and scope |
| [DEMO.md](DEMO.md) | The 6-minute and 90-second demo script with fallbacks, the numbers you may say and a "do not claim" list. Written against the build of 2026-10-10 (0a71f33); it does not cover the agent endpoint, hardening, Keep busy or Hammer | You present |
| [PITCH.md](PITCH.md) | The one-page pitch: the problem, novelty statements with their caveats, limits, applications. Same date as DEMO.md | You write about Ustaad |
| [DECK.md](DECK.md) | A slide-by-slide outline for the 6-minute pitch. Same date as DEMO.md | You build slides |

## Interfaces

| Document | What it is | Read it when |
|---|---|---|
| [AGENT-API.md](AGENT-API.md) | The OpenAI-format endpoint for other apps on the same phone: key, routes, tool calling, file search, limits, trust model | You write an app that uses the phone's model |
| [CONTRACTS.md](CONTRACTS.md) | The build contracts: decisions that override the PRD, ports, the node API, module plug-in points and the addenda of 2026-10-10. Partly history, still the reference for ports and the node API | You add a module or call the node API |
| [llama-server-api-notes.md](llama-server-api-notes.md) | Working `llama-server` and `rpc-server` command lines and request shapes, measured on the phones | You start a model server by hand |

## Evidence

| Folder | What it holds | Read it when |
|---|---|---|
| [benchmarks/](benchmarks/) | One dated note per run or topic, measured numbers only; `raw/` holds the raw results behind them and has its own README. The first end-to-end run (`e2e-*`) is superseded by the second (`e2e2-*`) for every figure the second covers | You check a figure |
| [device-probes/](device-probes/) | Raw adb probes of phones A, B and C (features, services, thermal zones) | You wonder what the hardware has or lacks |
| [review/](review/) | Code reviews and audits: two reviews of the merged app (2026-10-10), the adversarial audit that gate 9 was built from, and the final audit of 2026-10-11 (the newest). The `.txt` files are the raw text of two outside audits | You want to know what was found and what was left |

## Background

| Folder | What it holds | Read it when |
|---|---|---|
| [research/](research/) | Eight reports written on 2026-10-09 before the build: on-device stack, networking, split inference, Data Union, Choir, hackathon context, novelty and prior art (07), applications (08). Background, not results | You need the prior art or the reasons for a choice |
| [design/](design/) | The design system (`DESIGN.md`, the "Whiteboard" look), the prompts used for the screen designs, screenshots, and four visual passes on the running app (`qa/` to `qa4/`) | You work on the screens |
| [demo-corpus/](demo-corpus/) | Synthetic files for the Data Union demo, one set per phone | You prepare a demo |
| [img/](img/) | The seven diagrams used by the README and these documents | You reuse a diagram |

## Outside this folder

- [../HANDOFF.md](../HANDOFF.md): what is done, in progress and next, and the state of the phones. Read it first to continue the build.
- [../gates/](../gates/): one file per closed gate (0 to 9) with decisions, changes from the plan and open risks. History, apart from the newest.
- [../plans/](../plans/): dated plans written before a stretch of work.
