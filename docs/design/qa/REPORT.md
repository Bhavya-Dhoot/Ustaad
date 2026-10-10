# Visual QA of the master build on phone C (2026-10-10, commit 71f2359, debug build)

56 screenshots in this folder (`<nn>-<name>.png`), taken 07:08 to 07:38 IST with real models and the demo corpus. Compared by eye with the Stitch pictures in `ui-kit/screens/` and with `docs/design/DESIGN.md`. Fonts, palette, 8 dp corners and 48 dp tab targets are correct everywhere. The black "HackTracker by" pill in every screenshot is the hackathon's own telemetry app, not Ustaad.

Verdicts: Setup matches. Link detail matches. Benchmark matches. Data library matches. Settings matches. Host, Models (ready), Ask, Audit, Scan are acceptable. Shell strip, Swarm home, Join, Models (stopped), Choir compose, Memory need work.

Not reached: Choir single-phone run, result and scoreboard (no solo run in the UI; peers were busy or reserved); Swarm with no peers; Data "not indexed" and "re-index needed"; the too-hot stamp; Memory loading, running, dropped and ExpertMesh (no large model on phone C's app folder).

## Defects by owner

### [shell] and [ui]
1. Status strip: the "ONLINE" stamp is hatched (the caution style) for an ordinary state. Stamp only the state that matters: outline "NO INTERNET" when no network has validated internet; when online show a small plain word "online", no stamp.
2. Choir shows this phone's model as FAKE while the chat model is READY on the GPU: the registry's copy of this phone's NodeInfo keeps chatModel "FAKE" from start-up (choir/ChoirScreen.kt:284 reads it). Refresh the registry's self entry whenever the chat or embedding model changes.
3. Settings: "Save changes" is clipped by about 5 dp after Verify; the "Load models at start" switch appears twice.
4. DoodleConnector: the arrow shaft crosses the "Phone C" caption and the lines look straight; start the line below the caption and make the wobble visible. Peer rows print raw thermal words ("critical"): use the shared ThermalLevel word.

### [link]
5. Swarm home: "1 links" (plural); a developer line "Wi-Fi lock low_latency"; an "Internet validated yes" row; Host and Join only after about 2.5 screens of scrolling; two primary buttons on one screen ("Host a swarm" and the embedded "Run on the swarm").
6. Host page: QR drawn on pure white instead of Sheet; "band -" and "current Wi-Fi" shown instead of the measured band and the network name; Copy and Share are below the fold and the system "Copied" toast lands on "Stop hosting".
7. Join page: a stale "Hosting on the current Wi-Fi" message after hosting stopped; a bare black scanner box with no corner brackets.

### [sched]
8. Throughput panel inside the Swarm screen: table header clipped to 7 dp; an underlined "-" as the hero number when nothing has run; its "Run on the swarm" must not be a second primary button.

### [llm]
9. Models, stopped state: "Run benchmark" is the only primary while nothing is loaded and "Load defaults" is buried at the bottom; two FAKE stamps on one screen.
10. Models, ready state: "-" for prefill and decode until a benchmark runs; the loading bar is an empty grey track; three same-weight buttons stacked on every file card, Delete among them.
11. Benchmark page after the model is unloaded still shows the last numbers as if live; thermal rows print raw "NONE" while the strip says "Cool".

### [choir]
12. Compose: a hairline under every one of 20 problem rows; the switch wording is jargon. (The FAKE row is defect 2.)
13. No way to run with this phone alone when no peer is online.

### [pdu]
14. Ask: the keyboard stays open over the results; the answer sits about two screens below the sources with no emphasis; the single-line question field cuts off the start of the question; with the embedder stopped the chip text is truncated; an away peer is shown as "Phone A 0 ms".
15. Audit: tapping Verify appears to do nothing because the CHAIN OK stamp renders below the visible area.
16. Scan review: the recognised-text box cuts its last line mid-glyph; the whole form sits in one card; the "align edges" hint is a white pill.

### [memcloud]
17. Model card title reads "Hf" (the GGUF general.name) instead of the file name; lane caption clipped ("this phone, drives the mo..."); "Try on this phone alone" does nothing when no split is running; "Use / Skip Phone X" buttons hide the current state; with every peer skipped the plan vanishes and the button reads "Load across 1 phones"; the subtitle says "too big for one phone" for models that fit; the layer strip's accessibility text claims all layers are loaded before any load.

## Top fixes by visibility to a judge
1. Choir shows FAKE while a real model is loaded. [shell]
2. Status strip stamp semantics. [shell]
3. Ask: keyboard, answer placement and emphasis. [pdu]
4. Audit Verify result off-screen. [pdu]
5. Memory tab: dead button, "Hf", clipped caption, wrong subtitle. [memcloud]
6. Models stopped state primary action and stale benchmark numbers. [llm]
7. Swarm home: developer text, "1 links", double primary, Host and Join too far down. [link]
8. Throughput panel header and empty hero. [sched]
9. Join: stale message and bare scanner. [link]
10. Connector overlap and straight lines; QR on pure white. [ui] [link]
