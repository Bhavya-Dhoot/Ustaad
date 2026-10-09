# SwarmOS UI kit (Google Stitch export, 2026-10-10)

This folder is the visual reference for every screen of the app. It was generated in Google Stitch from `docs/design/stitch-prompts.md` and exported by the founder, who asked that the app use it.

- `screens/<name>/screen.png` : the picture to match.
- `screens/<name>/code.html` : Stitch's HTML/Tailwind for the same screen. Read it for exact spacing, sizes and structure; it is not app code (the app is Jetpack Compose).
- `STITCH-DESIGN.md` : the design system as Stitch recorded it (Material-style token names).
- `docs/design/DESIGN.md` : the binding design system. Where the two differ, `docs/design/DESIGN.md` wins.

## Screen to module map

| Module (package) | Screens |
|---|---|
| link | swarm_home_topology, host_a_swarm, join_a_swarm, link_a_to_b |
| llm (Models tab) | models, benchmark_phone_a |
| choir | agent_choir_compose, agent_choir_live_run, agent_choir_converged, agent_choir_scoreboard |
| pdu (Data tab) | data_union_library, data_union_ask_and_answer, data_union_query_audit, data_union_scan_document |
| memcloud (Memory tab) | memorycloud_plan_split, memorycloud_loading, memorycloud_running, memorycloud_phone_dropped, expertmesh_explainer |
| app shell | first_run_setup, settings_sheet |

## What to copy

Layout and order of sections, the phone tokens, the marker-banded lanes with tinted headers, the rubber stamps (outline for good states, hatched for cautions), the doodle connectors and handwritten notes, the hand-drawn underline or circle on the one key value, the ink primary button with the hard offset shadow, outlined secondary buttons, the bottom bar with the hand-drawn underline on the active tab.

## What NOT to copy (Stitch invented these; the app shows real data only)

1. Any text that is not backed by a real value in the app. Examples seen in the export: "MTU: 1500 BYTES", "LOCAL PROTOCOL v2.4", "TOPOLOGY: AD-HOC MESH (3 NODES)", "PENDING REJOIN", "3-NODE DEBATE CONSENSUS", "CHOIR CONSENTED VALUE", "delta: -3,872 off base", "MESH LIVE", "active stream", "Inject Benchmark Prompt", "LIVE SESSION CONTEXT". Leave them out.
2. Wrong sample entities: phones are always "Phone A", "Phone B", "Phone C" (never Pixel, Galaxy, OnePlus); model names come from the loaded GGUF file; IPs, speeds and temperatures come from live measurements.
3. Wide-tracked ALL-CAPS micro-labels above every section. Use a plain section title (Bricolage Grotesque 20) only where a section needs a name; most do not.
4. The double header. The app has one compact status strip (phone token and name, thermal, battery, free memory, link standard and speed, the "0 B TO INTERNET" stamp); there is no separate "SwarmOS / Node A" app bar.
5. The tall layer chart in the MemoryCloud screens. The layer strip is ONE row of small cells (one cell per layer, about 6 by 16 dp) as written in DESIGN.md, not 48 stacked bars.
6. Fallback fonts. Stitch rendered a typewriter mono and a generic sans. The app bundles Bricolage Grotesque (titles), Geist (body), Geist Mono (every number) and Shantell Sans (handwritten notes).
7. More than two handwritten notes or more than one stamp per card.
8. Middle-dot chains ("Cool · 79% · 8.6 GB"). Separate values with spacing or columns.
