# Brief: Restyle one module's screens with the Whiteboard UI kit — swarm-android-builder

Task (one sentence): Rebuild the screens of ONE module (named in your prompt) so they match the founder's Stitch designs using the shared composables in `com.swarmos.app.ui`, without changing any behaviour, route or data flow.

Read first: CLAUDE.md, docs/CONTRACTS.md (sections 3, 8, 9), plans/briefs/_common.md, docs/design/DESIGN.md (binding), ui-kit/README.md (screen map, what to copy, what NOT to copy), then every `ui-kit/screens/<name>/screen.png` (look at it with the Read tool) and `code.html` (for exact spacing and structure) listed for your module in the README. Then the UI kit source in app/app/src/main/java/com/swarmos/app/ui/ (use these composables; do not restyle by hand what the kit already provides) and your module's existing screen code.

You own: only the Compose UI files of your module's package (the files that contain @Composable screen code). Do not touch view models, runtimes, routes, protocol types, tests, other packages or shared files. If the kit lacks a component you need, build it locally in your module and say so in the report (do not edit ui/).

Rules:
- Behaviour freeze: every button, input, state and navigation path that exists before your change exists after it and does the same thing. No new features, no removed features.
- Real data only: every text and number on screen comes from the module's state. Where the Stitch picture shows something the app does not have (see the "What NOT to copy" list), leave it out. Where the app has real data the picture lacks, place it in the style of the system.
- One compact status strip and the bottom bar come from the app shell; do not add headers.
- Phone identity: use `PhoneToken`, `Lane` and the marker colour from the kit for anything attributed to a phone; never use marker colours for buttons or decoration.
- One primary button per screen, at most one stamp per card, at most two handwritten notes per screen, every number in the mono style, no ALL-CAPS wide-tracked micro-labels, no middle-dot chains, no emoji, no em-dashes.
- States: design the empty, loading, error and FAKE-model states too (skeleton blocks, one handwritten hint with an arrow for empty, hatched stamp for errors), not only the happy path.

Verify:
- Build your variant, install on one phone, drive the module into each screen and each main state over adb or the signed routes, take a screenshot of each (`adb exec-out screencap -p`), save them to `docs/design/shots/<module>-<screen>.png`, then open each screenshot next to the matching Stitch PNG and list the remaining differences honestly. Fix what is cheap; report what is not.
- Run the module's existing unit tests; they must still pass unchanged.
- Report: files changed, screenshots saved, differences that remain, kit gaps.
