# Ustaad explainer video: plan

Input: the project (H:\UStaaf). Narrated explainer, landscape 1920x1080, 30 fps, 91.3 s, voice `af_heart`.

Design read: a rendered explainer video (not a web page) for hackathon judges and for the pitch deck. Look: the app's own Whiteboard system from docs/design/DESIGN.md (whiteboard ground, ink strokes, stamps, phone tokens A teal, B tangerine, C magenta; Bricolage Grotesque, Geist, Geist Mono, Shantell Sans). Dials: variance medium, motion high, density medium.

Angle: one phone cannot run a large model, and three phones on the same table share nothing. Ustaad makes nearby phones one local AI with no cloud. The 18.6 GB model that fails in the first seconds comes back in scene 7 and runs.

Hook: "One phone. One 18.6 GB model. 134 seconds. 0 words."

Punchline: "Ustaad. Nearby phones, one local AI. Every number measured on three real phones."

Every number on screen is from a run on the three iQOO 15 phones (files in docs/benchmarks/):
- 18.6 GB model, no token in 134 s on one phone; three phones 6.4 tok/s, first word 1.5 s; lost phone named in 1.8 s; re-plan 65 s (2026-10-10-memorycloud-inapp.md, 2026-10-10-rpc-split-spike.md)
- 12.0 against 30.9 tok/s, 32 prompts, split 11/11/10 (2026-10-10-e2e2-swarm-throughput.md)
- 151 wrong alone, 164.5 right together (screenshot docs/design/qa2/21-choir-result-lanes.png); 20 s against 32 s per problem (2026-10-10-e2e2-choir.md)
- Phone B 119 ms, Phone A 72 ms (screenshot docs/design/qa2/06-data-ask-live-answer.png)
- card on the other phones in under 1 s, answer in 35 ms with the author's phone off (2026-10-10-knowledge-bank.md third run, 2026-10-10-e2e2-knowledge-bank.md)
- Wi-Fi Direct up to 725 Mbps (2026-10-10-swarmlink-throughput.md)

Phone screens are real screenshots from docs/design/qa2 and docs/design/shots, cropped below the system status bar.

## Storyboard (times follow the narration, see work/layout.js)
| Scene | Time | On screen | Voice |
|---|---|---|---|
| hook | 0.0 to 7.8 | A phone, the 18.6 GB model dropped on it, timer to 134 s, stamp 0 WORDS | One phone, trying to run a large AI model on its own. Two minutes in, it has not produced a single word. |
| problem | 7.8 to 22.2 | Three phones on a table, 48 GB and three chips counted, walls between them, the cloud drawn and crossed out | But look at the table... Side by side, and they share nothing. The usual fix is the cloud... |
| reveal | 22.2 to 30.0 | Eraser wipes the cloud, marker links the phones, wordmark, NO CLOUD and NO ACCOUNTS stamps | Ustaad links nearby phones, directly, into one local AI. No cloud. No accounts. |
| 1 swarm | 30.0 to 39.1 | Swarm screen; 32 prompts fly into three lanes; 12.0 against 30.9 tok/s | One. Work spreads... |
| 2 choir | 39.1 to 48.3 | Choir run, then the result screen; 151 struck out, 164.5 ticked; 20 s against 32 s | Two. Phones check each other's answers... |
| 3 data | 48.3 to 60.0 | Ask screen scrolls to its sources; question goes out, only snippets come back | Three. Ask once... |
| 4 memory | 60.0 to 71.7 | The model block returns, is cut in three, 6.4 tok/s; one phone pulled, named in 1.8 s | Four. Remember the model that was too big?... |
| 5 bank | 71.7 to 80.9 | A saved card copies to the other phones; the author leaves; 35 ms answer | Five. Good answers are saved... |
| close | 80.9 to 91.3 | Wordmark, five ticks, "every number measured on three iQOO 15 phones" | Ustaad. Nearby phones, one local AI... |
