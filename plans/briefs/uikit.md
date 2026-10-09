# Brief: UI kit ("Whiteboard" theme) — swarm-android-builder

Task (one sentence): Implement the SwarmOS design system from docs/design/DESIGN.md as the app's Compose theme and a small set of shared composables in package `com.swarmos.app.ui`, restyle the app chrome (status strip, bottom bar) with it, and prove it on a phone with a gallery screen.

Read first: CLAUDE.md, docs/CONTRACTS.md (sections 2, 3, 8, 9), plans/briefs/_common.md, docs/design/DESIGN.md (binding: colours, type, shape, components, motion, bans), and Prompt 1 plus the style capsule in docs/design/stitch-prompts.md (they describe the chrome and two anchor screens). Then the scaffold code: app/app/src/main/java/com/swarmos/app/MainActivity.kt, ui/*, core/NodeInfo.kt.

Build flags: `-PidSuffix=.ui -PportOffset=40`.

You own: app/app/src/main/java/com/swarmos/app/ui/** , app/app/src/main/res/font/** , app/app/src/main/res/values/** (themes/colors only), and the chrome parts of MainActivity.kt (top strip and bottom bar; list your exact edits).

Build:
1. Fonts: bundle Bricolage Grotesque, Geist, Geist Mono and Shantell Sans as res/font files (all are SIL OFL on Google Fonts; download the TTFs once from the google/fonts GitHub repository or fonts.google.com; variable fonts are fine on minSdk 31). If a family cannot be fetched in 10 minutes, fall back (Bricolage -> system sans bold, Geist -> system sans, Geist Mono -> system monospace, Shantell Sans -> system cursive) and say so. Record source URLs and licences in app/app/src/main/res/font/README.txt.
2. Theme: `SwarmTheme` (light only) with the exact palette from DESIGN.md section 2 exposed as `SwarmColors` (whiteboard, sheet, ink, graphite, smudge, markerA, markerB, markerC, plus `marker(nodeName)` returning the colour for "Phone A/B/C" and graphite for anything else), `SwarmType` (display, title, body, label, mono, monoHero, note), shapes locked to 8 dp, and a Material3 colour scheme mapped onto it so stock components are not purple. System bars light.
3. Shared composables, each small, each with a @Preview:
   - `PhoneToken(name, size)`, `Lane(name, role, trailing) { content }` (6 dp marker band, tinted header), `Stamp(text, caution: Boolean)` (double border, mono caps, rotated -4 degrees, hatched fill when caution, lands once with a 120 ms scale unless animations are removed), `PrimaryButton` (ink fill, hard 3 dp offset shadow that it presses into), `SecondaryButton`, `MetricRow(label, value, unit)`, `HeroMetric(value, unit, note)` (mono 44 sp with a hand-drawn underline drawn on a Canvas with a slightly irregular path), `InkCircle { content }` (hand-drawn circle around content), `Note(text, color)` (Shantell Sans, slight rotation), `LayerStrip(assignments: List<String?>, loaded: List<Boolean>)` (one cell per layer coloured by holder phone, hatched while loading), `DoodleConnector` (wobbly ink line with arrowhead between two points on a Canvas, dashed when away), `InkProgress(fraction)` (thin bar, no track), `Skeleton(height)`, `SectionTitle(text)`, `Hairline()`.
   - Hand-drawn shapes must be deterministic (seeded jitter) so they do not shimmer on recomposition.
4. Chrome: restyle the top status strip and the bottom navigation per DESIGN.md section 5 (active destination: bold label + hand-drawn underline; the "0 B TO INTERNET" stamp on the right of the strip when no WAN bytes were used).
5. Gallery: a debug-only screen reachable with `adb shell am start -n <pkg>/com.swarmos.app.MainActivity --ez gallery true` that shows every composable above with sample content, including a mock of the Swarm topology (three tokens, doodle connectors with labels) and a mock Agent Choir lane, so the kit can be judged at a glance.
6. Respect the system "remove animations" setting (Settings.Global.ANIMATOR_DURATION_SCALE == 0): no stamp or connector animation.

Verify:
- Build and install your variant on phone C; open the gallery; save screenshots to docs/design/uikit-gallery-1.png and -2.png (`adb exec-out screencap -p`), and look at them yourself: correct fonts actually rendering (not fallbacks), no purple anywhere, 8 dp corners, colours match the hex values, text contrast fine, nothing clipped at 600 dpi.
- A JVM unit test for `marker(nodeName)` mapping.
- Report the public API of the kit (one line per composable) so the screen workers can use it.
