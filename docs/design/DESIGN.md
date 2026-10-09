# Design System: SwarmOS ("Whiteboard")

Platform: native Android phone app (Jetpack Compose). Light theme only. This file is the single source of truth for Google Stitch screens and for the app's Compose theme.

Design read: an instrument panel for three phones working as one computer, shown to hackathon judges on a table and on a mirrored laptop. Dials: Variance 6 (offset, ordered), Motion 4 (only motivated motion), Density 7 (many live numbers, all monospaced).

## 1. Visual Theme & Atmosphere

A clean whiteboard that an engineer has drawn on with three markers. The structure is strict and minimal: cool off-white surface, near-black ink lines, generous but efficient spacing, numbers in monospace. On top of that structure sits a thin hand-drawn layer: wobbly connector lines between phones, a handwritten note beside the one number that matters, an inked circle around the final answer, rubber-stamp marks for states. The feeling is "honest lab instrument sketched by a person", not "AI dashboard". It is restrained neo-brutalism (flat fills, ink outlines, one hard shadow) with a doodle accent, and it is never cute, pastel or neon.

Each phone has one marker colour. Colour in this product means "which phone did this come from" and nothing else.

## 2. Color Palette & Roles

- **Whiteboard** (#F6F7F5): app background. Cool off-white with a faint green-grey cast.
- **Sheet** (#FCFDFB): raised surfaces, input fields, bottom bar.
- **Ink** (#15171A): all text, all outlines, primary button fill, icons. Never pure black.
- **Graphite** (#5E636B): secondary text, units, timestamps.
- **Eraser Smudge** (#E4E7E3): hairline dividers, disabled fills, dot grid.
- **Marker Teal** (#1E9BB0): identity of Phone A. Token fill, lane band, layer cells, connector labels.
- **Marker Tangerine** (#E08A3C): identity of Phone B. Same uses.
- **Marker Magenta** (#C8457E): identity of Phone C. Same uses.
- Marker tints: each marker colour at 12 % opacity over Whiteboard, used only as the header tint of that phone's lane.

Rules: there is no separate accent colour. Primary actions are Ink. The three marker colours are categorical data colours and must never be used for decoration, buttons, links or status. Success, warning and error are communicated with stamps, hatching and words, not with green, yellow or red. No gradients. No glows. No purple or blue neon.

## 3. Typography Rules

- **Display and titles:** Bricolage Grotesque, weights 600 to 800, tight tracking (-1 % to -2 %). Screen titles 28 / 32, section titles 20 / 24. Hierarchy comes from weight, not from giant size.
- **Body and UI labels:** Geist, 15 / 22 for body, 13 / 16 medium for labels. Answers and snippets max 60 characters per line.
- **Numbers, metadata, file names, model names, stamps:** Geist Mono with tabular figures. Every number in the app is Geist Mono. Hero metric 44 / 48 medium; inline metrics 13 / 16.
- **Handwritten annotations:** Shantell Sans, 14 to 16, Ink or the relevant phone's marker colour, rotated between -3 and 3 degrees. Maximum two annotations per screen.
- **Banned:** Inter, Roboto as the visible brand face, any serif, all-caps wide-tracked micro-labels above every section, gradient text.

## 4. Component Stylings

- **Shape system (locked):** containers, buttons and inputs have 8 px corners. Chips and phone tokens are full pills or circles. Nothing else.
- **Lines:** 1.5 px Ink outline on interactive and raised elements. Groups are separated by a single Eraser Smudge hairline or by space. No line above and below every row.
- **Primary button:** Ink fill, Sheet text, 8 px corners, hard shadow 3 px right and 3 px down in solid Ink with no blur. On press it moves 3 px into its shadow. One primary button per screen.
- **Secondary button:** Sheet fill, 1.5 px Ink outline, Ink text, no shadow.
- **Phone token:** 28 px circle, marker fill, 1.5 px Ink outline, the letter A, B or C in Bricolage Grotesque 700 Ink. It appears wherever something is attributed to a phone.
- **Lane:** a full-width block for one phone. 6 px marker band on the left edge, header row with phone token and name on the marker tint, body on Whiteboard.
- **Layer strip:** the model drawn as one row of small cells (10 by 16, 1 px Ink outline), one cell per layer, filled with the marker colour of the phone that holds it. Cells still loading are diagonally hatched. This is the signature visual of MemoryCloud.
- **Doodle connector:** a slightly wobbly 2 px Ink line with a hand-drawn arrowhead joining phone tokens, with a handwritten label for throughput and round trip time. Used only in the swarm topology and the ExpertMesh explainer.
- **Stamp:** a rectangle with a double 1.5 px Ink border, uppercase Geist Mono 12, rotated -4 degrees, like a rubber stamp. Positive stamps are outline only: VERIFIED, CHAIN OK, 0 B TO INTERNET. Negative or cautionary stamps are filled with diagonal Ink hatching at 20 %: DISAGREE, UNVERIFIED, OFFLINE, FAKE, INCOMPLETE. Maximum one stamp per card.
- **Emphasis:** the single most important value on a screen gets a hand-drawn Ink underline or circle. Nothing else is emphasised.
- **Cards:** used only when a thing can be picked up as a unit (a source snippet, a model file, a peer). Sheet fill, 1.5 px Ink outline, no shadow. Metrics are laid out plainly on the Whiteboard without boxes.
- **Inputs:** label above, Sheet fill, 1.5 px Ink outline, helper text below in Graphite, error text below in Ink with a hatched left bar. No floating labels, no placeholder as label.
- **Charts:** Ink sparklines, no axes clutter. Bars are solid for "this phone" and hatched for "remote". Progress is a thin Ink bar with no filled background track.
- **Loading:** skeleton blocks in Eraser Smudge matching the final layout. No circular spinners.
- **Empty states:** one line of Shantell Sans with a hand-drawn arrow pointing at the action that fills the screen.
- **Icons:** one line-icon family at 1.5 px stroke (Phosphor regular, or Material Symbols Outlined weight 300 if Phosphor is unavailable). No emoji anywhere.

## 5. Layout Principles

- Phone canvas about 390 wide. 16 px side margins, 8 px base grid.
- Fixed chrome: a compact status strip at the top (phone token and name, thermal state, battery, free memory, link standard and speed, the "0 B TO INTERNET" stamp) and a bottom bar with five destinations: Swarm, Models, Choir, Data, Memory. The active destination has a bold label and a hand-drawn underline.
- Left-aligned, offset compositions. The hero metric sits top-left, large, with its unit in Graphite and one handwritten note. Supporting metrics run in a two-column list below, label left and mono value right.
- Lanes stack vertically in phone order A, B, C. Never three equal cards side by side.
- A faint 16 px dot grid in Eraser Smudge appears only behind the swarm topology drawing.
- No overlapping elements. Stamps sit in reserved space at the top-right of their container.
- Every interactive target is at least 48 px tall.

## 6. Motion & Interaction

Motion exists only where it explains something.
- Tokens streaming into a lane: text appears as it is generated, with a 2 px Ink caret.
- Stamp: lands once with a 120 ms scale from 1.15 to 1 and a 2 degree settle. Never loops.
- Layer strip: cells fill left to right as weights arrive.
- Heartbeat: the doodle connector between two phones thickens for 150 ms on each successful heartbeat; it becomes dashed when a phone is away.
- Press: buttons move into their shadow.
- Screen changes: 180 ms fade and 8 px rise, once.
With system "remove animations" on, everything above is static and state is shown by the final frame only.

## 7. Anti-Patterns (Banned)

- No emoji. No Inter. No serif. No pure black (#000000) and no pure white.
- No gradients, glows, glassmorphism, neon, purple or blue "AI" styling, dark hacker terminal look.
- No pastel neo-brutalism, no lime green, no tilted sticker piles, no thick 4 px borders on everything.
- No decorative use of the three marker colours. No fourth colour.
- No status colour coding (red, green, yellow). Use stamps, hatching and words.
- No three equal cards in a row. No card around every metric.
- No circular spinners. No progress bars with a filled grey track.
- No em-dashes in any visible text. Use a hyphen, a comma or a new line.
- No decorative dots, no wide-tracked uppercase eyebrow above each section, no section numbering.
- No marketing words: seamless, unleash, next-gen, elevate, powerful, revolutionary, AI-powered.
- No invented precision: every number shown is a live measurement or is visibly marked as sample.
- No generic names (John Doe, Acme). Phones are Phone A, Phone B, Phone C. Files have believable names.
