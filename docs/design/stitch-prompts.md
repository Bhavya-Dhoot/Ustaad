# Google Stitch prompts for SwarmOS

How to use
1. Open Stitch, choose Mobile app, and pick the highest quality mode.
2. If your Stitch build accepts a DESIGN.md or design-system file, attach `docs/design/DESIGN.md` first.
3. Paste Prompt 1. It sets the theme and draws the two anchor screens. Do not continue until those two look right. If they drift, reply with the "Correction" line at the bottom of this file.
4. Paste Prompts 2 to 5 in the same project, one at a time. Each starts with the same style capsule so the theme holds.
5. Numbers in these prompts are sample values for layout. The link numbers (56 Mbps, 11 ms, 11ac, 433 Mbps) are real measurements from the phones.

---

## Prompt 1 - theme and two anchor screens

```
Design a native Android phone app called SwarmOS. It makes three phones on one local Wi-Fi link work together as a single AI computer with no cloud. The app is an instrument panel: it shows which phone is doing what, and every number on screen is a live measurement. Audience: hackathon judges looking at three phones on a table.

VISUAL LANGUAGE: "Whiteboard". A clean whiteboard that an engineer has drawn on with three markers. Strict, minimal structure with a thin hand-drawn layer on top. Restrained neo-brutalism with a doodle accent. Light theme only. It must not look like a typical AI app: no dark mode, no gradients, no glows, no glass, no purple or blue neon, no pastel sticker brutalism, no emoji.

COLOURS (use exactly these, no others):
- Whiteboard #F6F7F5 app background
- Sheet #FCFDFB raised surfaces and inputs
- Ink #15171A all text, outlines, icons and the primary button fill
- Graphite #5E636B secondary text and units
- Eraser Smudge #E4E7E3 hairline dividers
- Marker Teal #1E9BB0 means Phone A
- Marker Tangerine #E08A3C means Phone B
- Marker Magenta #C8457E means Phone C
Colour only ever means "which phone". Never use the three marker colours for buttons, links, decoration or status. There is no green, red or yellow status colour: states are shown with rubber-stamp marks, diagonal hatching and words.

TYPE:
- Bricolage Grotesque, weight 600 to 800, tight tracking, for screen titles (28) and section titles (20)
- Geist for body (15) and labels (13)
- Geist Mono with tabular figures for every number, file name, model name and stamp
- Shantell Sans for handwritten annotations only, at most two per screen, rotated a few degrees
No Inter, no Roboto as the brand face, no serif.

COMPONENTS:
- Corners: 8 px on containers, buttons and inputs. Chips and phone tokens are pills or circles. Nothing else.
- 1.5 px Ink outlines. Separate groups with one hairline or with space. Do not box every metric.
- Primary button: Ink fill, light text, hard shadow 3 px right and 3 px down in solid Ink, no blur. Only one per screen. Secondary button: Sheet fill with Ink outline, no shadow.
- Phone token: 28 px circle filled with that phone's marker colour, Ink outline, bold letter A, B or C.
- Lane: a full-width block for one phone, with a 6 px marker-colour band on its left edge and a header row tinted with that marker colour at 12 percent.
- Doodle connector: a slightly wobbly hand-drawn 2 px Ink line with a hand-drawn arrowhead, with a handwritten label.
- Stamp: a small rectangle with a double Ink border, uppercase Geist Mono, rotated minus 4 degrees, like a rubber stamp. Cautionary stamps are filled with light diagonal hatching.
- Emphasis: the one most important value on a screen gets a hand-drawn Ink underline or circle.
- Line icons at 1.5 px stroke, one family. No circular spinners, no progress bars with grey tracks, no three equal cards in a row, no em-dashes in any text.

FIXED CHROME ON EVERY SCREEN:
- Top status strip, compact, Geist Mono 12: phone token and "Phone A", thermal "Cool", battery "79%", free memory "8.6 GB", link "11ac 433 Mbps", and on the right a small outline stamp "0 B TO INTERNET".
- Bottom bar with five destinations and line icons: Swarm, Models, Choir, Data, Memory. The active one has a bold label and a hand-drawn underline.

SCREEN 1 - Swarm (home, active tab Swarm)
Title "Swarm" with a short line under it: "3 phones, 1 link, no cloud".
Upper half: a topology drawing on a faint dot grid. Three phone tokens A, B, C arranged in a loose triangle and joined by doodle connectors. Handwritten labels on the connectors: "56 Mbps", "11 ms". A small handwritten note with an arrow pointing at Phone A: "host".
Below, a plain list of the two peers (no heavy cards): phone token, name, IP in mono (192.168.242.23), link type "LAN", round trip "11 ms", state "online", thermal "Cool", battery "83%". One row shows Phone C with state "away" and a hatched stamp "OFFLINE".
Bottom: primary button "Host a swarm", secondary buttons "Join" and "Measure link".

SCREEN 2 - Agent Choir, live run (active tab Choir)
Title "Agent Choir". Under it the problem in a Sheet box: "A shop sells a laptop for Rs 62,400 after a 22% discount. What was the list price?"
A row of three small round indicators labelled R1, R2, R3 with R2 active.
Three lanes stacked in order A, B, C. Each lane header: phone token, "Phone A", role in Graphite ("Solver", "Alt solver", "Critic"), and on the right mono metrics "212 tok" and "9.4 s". Each lane body: two or three short lines of streamed reasoning text, then an answer chip in mono. Phone A answers "80,000", Phone B answers "76,128", Phone C answers "80,000". Phone B is still streaming, with a text caret.
Between the lanes and the footer: a hatched stamp "DISAGREE" and a handwritten note "B took 22% of the sale price".
Below: a thin fourth lane with a grey band labelled "Baseline - Phone A alone" with answer "76,128" and "8.1 s".
Footer row: "Tool check: 62400 / 0.78 = 80000" in mono with an outline stamp "VERIFIED", and the final answer "Rs 80,000" large with a hand-drawn circle around it.
```

---

## Style capsule (paste at the top of every later prompt)

```
Continue the SwarmOS Android app in the exact same "Whiteboard" design system as the existing screens: light theme, Whiteboard #F6F7F5 background, Sheet #FCFDFB surfaces, Ink #15171A text and 1.5 px outlines, Graphite #5E636B secondary text, Eraser Smudge #E4E7E3 hairlines. Marker Teal #1E9BB0 = Phone A, Marker Tangerine #E08A3C = Phone B, Marker Magenta #C8457E = Phone C, and colour is used only to say which phone. Bricolage Grotesque titles, Geist body, Geist Mono for every number and file name, Shantell Sans for at most two handwritten notes per screen. 8 px corners, one Ink primary button with a hard 3 px offset shadow per screen, phone tokens, marker-banded lanes, doodle connectors, rubber-stamp state marks with hatching for cautions, a hand-drawn underline or circle on the single key value. Same top status strip and the same five-item bottom bar (Swarm, Models, Choir, Data, Memory). No gradients, glows, dark mode, emoji, spinners, status colours, three equal cards in a row, or em-dashes.
```

---

## Prompt 2 - pairing, link and models (5 screens)

```
[paste the style capsule here]

SCREEN 3 - Host a swarm (reached from Swarm)
Title "Host a swarm". A large QR code inside a Sheet frame with an Ink outline and the hard offset shadow. Under it in mono: network "DIRECT-sw-SwarmA", band "5 GHz", address "192.168.49.1:8787". A handwritten note with an arrow to the QR: "scan me". Below: a list "Joined" with Phone B (token, "joined 4 s ago") and an empty dashed row "waiting for Phone C". Secondary button "Use current Wi-Fi instead".

SCREEN 4 - Join a swarm
Full-bleed camera viewfinder with four hand-drawn Ink corner brackets framing the scan area on a light scrim. One line of text under the frame: "Point at the host phone's code". Secondary button "Enter code by hand". No other decoration.

SCREEN 5 - Link measurement
Title "Link A to B". Hero metric top-left: "56.3" very large in Geist Mono with "Mbps" in Graphite and a hand-drawn underline. A handwritten note: "100 MB in 14.2 s".
Two-column plain list below, label left and mono value right: Round trip avg 11.1 ms, p95 17.4 ms, min 6.6 ms, max 19.8 ms, Standard 11ac, PHY speed 433 Mbps, Frequency 5765 MHz, Link type LAN.
Then "Earlier runs": four compact rows with time, link type and Mbps, and a small Ink sparkline. Primary button "Measure again".

SCREEN 6 - Models (active tab Models)
Title "Models". Section "Loaded on this phone": two rows without heavy boxes. Row 1: role "Chat", name "Qwen3.5-4B Q4_K_M" in mono, "2.7 GB", state "ready", with metrics "prefill 148 tok/s" and "decode 17.2 tok/s". Row 2: role "Embedder", name "embeddinggemma-300m Q8", "0.3 GB", "ready", "26 ms per text".
Section "On this phone" as cards (a model file is a unit you can pick up): each card has name in mono, size, role chip, and a secondary button "Load". One card is downloading: thin Ink progress bar with no grey track, "1.9 of 18.6 GB", "3.9 MB/s".
Primary button "Run benchmark".

SCREEN 7 - Benchmark result
Title "Benchmark - Phone A". Hero metric "17.2" with "tok/s decode" and a hand-drawn circle. Plain list: Prefill 148 tok/s, Time to first token 0.6 s, Server memory 3.1 GB, Thermal Cool, Threads 6. A small Ink line chart "10 minute run" showing tok/s by minute with a slight decline, and a handwritten note "throttles after minute 7". Secondary button "Share to log".
```

---

## Prompt 3 - Agent Choir (3 screens)

```
[paste the style capsule here]

SCREEN 8 - Agent Choir, compose (active tab Choir)
Title "Agent Choir" with one line: "Each phone answers alone, then they compare."
A labelled multi-line input "Problem" with a microphone icon button at its right. Under it a row of pill chips for kind: Math (selected, Ink fill), Code, Multiple choice, Text.
Section "Phones in this run": three compact rows, each with phone token, name, role (Solver, Alt solver, Critic) and model name in mono.
Section "Try one": five short problem rows, each one line of text with a kind chip.
Primary button "Run". Secondary button "Run all 20".

SCREEN 9 - Agent Choir, converged
Same layout as the live run screen but finished. Round indicators R1 and R2 done. All three lanes show answer chip "80,000". An outline stamp "VERIFIED" near the final answer "Rs 80,000", which has a hand-drawn circle.
Under it a small agreement matrix, 3 by 3, phone tokens on both axes, cells are either a solid Ink check mark or a hatched cell.
Totals as a plain list: Rounds 2, Tokens 1,146, Wall clock 21.4 s, Baseline alone 8.1 s and wrong.
Secondary buttons "See messages" and "New problem".

SCREEN 10 - Agent Choir, scoreboard
Title "20 problems". Three horizontal bars stacked, each with a label left and a mono count right: "One phone alone 11 of 20", "Choir 14 of 20", "Choir + tool check 17 of 20". Bars are solid Ink with no background track; the best one has a hand-drawn underline. A handwritten note: "n = 20, same model, same token cap".
Below: a compact table of the 20 problems with columns Kind, Alone, Choir, Rounds, Seconds; use small check marks and hatched cells, hairline between groups of five only. Secondary button "Export results".
```

---

## Prompt 4 - Private Data Union (4 screens)

```
[paste the style capsule here]

SCREEN 11 - Data, library (active tab Data)
Title "Data Union" with one line: "Every phone searches its own files. Only short snippets travel."
Hero row: "1,284" in large mono with "chunks indexed on this phone", and a small line "last indexed 12 min ago".
Section "Sources": three rows with line icons: Photos (214 with text), Documents folder "SwarmDemo" (37 files), Scanned (5). Each has a secondary button "Index".
Section "Private folders": rows with a switch. "Private" is on, with a hatched stamp "NEVER SHARED". "Documents/SwarmDemo" is off.
Primary button "Ask the swarm". Secondary button "Scan a document".

SCREEN 12 - Data, ask and answer
A labelled input "Question" containing "Where is the warranty bill for my laptop?" with a microphone icon button.
A timing strip in mono under the input: "embed 31 ms", "search 18 ms", "peers 64 ms", "first word 0.9 s".
Section "Sources" as snippet cards. Each card: phone token and phone name at top-left, file name in mono at top-right, a date line in Graphite, then a two-line snippet. Card 1 from Phone B: "IMG_2231.jpg", "Koramangala Computers - Tax invoice. ThinkBook 14 G7, serial PF4X91KQ. Warranty 36 months from 14 Mar 2025." Card 2 from Phone A: "warranty-certificate.pdf", "Extended warranty policy EW-2291-774 valid until 13 Mar 2029." Card 3 from Phone A: "laptop-order-email.txt".
Section "Answer": a paragraph in Geist with inline citation chips [1] and [2] in mono, and a text caret at the end as if still streaming.
A notice row with a hatched stamp "INCOMPLETE" and the text "Phone C did not answer in 1.5 s".

SCREEN 13 - Data, what left this phone
Title "Query audit". Two sections stacked. "Sent from this phone": one row, the question text and "148 bytes to 2 phones". "Returned by each phone": three lanes A, B, C. Lane B shows "5 snippets, 1,912 bytes, 61 ms" and a line "1 match withheld (private folder)" with a hatched stamp "WITHHELD". Lane C shows "no reply". A handwritten note near the top: "no files, no embeddings, no locations". Outline stamp "CHAIN OK" at the bottom next to "audit log entry 0x7c1e...a93f" in mono.

SCREEN 14 - Data, scan a document
Camera viewfinder with hand-drawn Ink corner brackets around a paper receipt. A bottom sheet in Sheet colour rising over the lower third: title "Text found", four lines of recognised text in mono, a labelled input "Title" containing "Electricity bill Sep", a switch "Keep private", and a primary button "Add to index".
```

---

## Prompt 5 - MemoryCloud, ExpertMesh and system screens (6 screens)

```
[paste the style capsule here]

A new component for these screens, the layer strip: the AI model drawn as one row of 48 small cells (about 6 by 16 px each, 1 px Ink outline), one cell per layer, each filled with the marker colour of the phone that holds it. Cells still loading are diagonally hatched.

SCREEN 15 - Memory, plan the split (active tab Memory)
Title "MemoryCloud" with one line: "A model too big for one phone, split by layers."
A model card: "Qwen3-30B-A3B Q4_K_M" in mono, "18.6 GB", "48 layers", and a hatched stamp "TOO BIG FOR ONE PHONE".
The layer strip, wide, split into three runs coloured teal, tangerine, magenta, with handwritten labels under it: "0-15", "16-31", "32-47".
Three lanes A, B, C, each with: layers held, "6.2 GB", free memory in mono, and a small stepper to move layers between phones.
Primary button "Load across 3 phones". Secondary button "Try on this phone alone".

SCREEN 16 - Memory, loading
Same header and layer strip, but the teal cells are solid, about half of the tangerine cells are solid and the rest hatched, and all magenta cells are hatched. Three lanes each with a thin Ink progress bar without a grey track and mono text: "Phone B 3.4 of 6.2 GB, 41 MB/s". A handwritten note: "first load only, cached after". Secondary button "Cancel".

SCREEN 17 - Memory, running
Hero metric top-left: "8.4" very large with "tok/s" and a hand-drawn underline. Next to it small mono lines: "first word 2.1 s", "hop A to B 6 ms", "hop B to C 7 ms".
The layer strip, fully solid, with a small Ink marker moving across it to show the token passing through.
A chat area: one user bubble with an Ink outline and one assistant answer as plain text streaming with a caret.
Three compact lanes at the bottom: memory used "6.4 GB" and thermal "Warm" per phone.
A plain panel "This phone alone": text "Could not load: out of memory after 41 s" with a hatched stamp "FAILED".

SCREEN 18 - Memory, a phone dropped
Same as the running screen, but the tangerine run of the layer strip is hatched, the connector to Phone B is dashed, and a notice block near the top says "Phone B stopped answering. Generation paused." with a hatched stamp "DEGRADED". Primary button "Re-plan with 2 phones". Secondary button "Wait for Phone B".

SCREEN 19 - ExpertMesh explainer (reached from Memory)
Title "ExpertMesh" with one line: "Why experts are grouped by layer, not scattered."
A drawing: a grid of small cells, 8 columns of experts by 6 visible layers, with 8 cells per layer outlined in bold Ink as "active". The layers are banded teal, tangerine, magenta by rows. Two doodle connectors show a token passing down through the bands, labelled "1 hop per band".
A plain calculation in mono, three lines: "round trip 11 ms", "48 MoE layers x 11 ms = 528 ms per token if experts were remote", "ceiling 1.9 tok/s". Under it: "grouped by layer range: 2 hops per token, measured 8.4 tok/s" with a hand-drawn circle around 8.4.

SCREEN 20 - First run setup
Title "Three things before we start". Three numbered rows, each with a line icon, a one-line reason and a secondary button: "Allow nearby devices" (find the other phones), "Allow notifications" (keep working with the screen off), "Turn off battery limits for SwarmOS" (the phone will stop the swarm otherwise). Completed rows show a solid Ink check mark instead of the button. A handwritten note with an arrow at the third row: "important on this phone". Primary button "Continue".

Also draw one bottom sheet, SCREEN 21 - Settings: labelled input "Phone name" with "Phone A", a row of the three marker colours as selectable tokens, a mono row "Pairing secret - reset", a switch "Remove animations", and a mono row "Audit log - 1,204 entries" with an outline stamp "CHAIN OK".
```

---

## Correction line (use if a screen drifts)

```
This drifted from the design system. Fix it: light Whiteboard background #F6F7F5, Ink #15171A text and 1.5 px outlines, only teal, tangerine and magenta as phone colours and never as decoration or buttons, no gradients, no shadows except the single hard 3 px offset shadow on the one primary button, 8 px corners, every number in Geist Mono, at most two handwritten Shantell Sans notes, states shown as rubber stamps with hatching instead of red or green, no boxes around plain metrics, no emoji, no em-dashes.
```
