# Local copies that the UI kit now provides

Measured on master at eb75c56 plus the kit commits on branch worktree-agent-aa6a99e10b3c6d312 (2026-10-10). Nothing in the modules was edited. Paths are under `app/app/src/main/java/com/swarmos/app/`. Kit symbols are in package `com.swarmos.app.ui`.

New kit pieces used below: `SwarmIcon.{Mic, Lock, Folder, Camera, Scan, Photo, Tick, Cross, Back, Settings, Download, Stop, Refresh}`, `TickMark`, `SelectChip`, `CiteChip`, `CitedText`, `MonoText`, `monoNumbers`, `TimingStrip`, `ScoreBar`, `AgreementMatrix`, `ScreenHeader`, `BackLink`, `InkSwitch`, and `SwarmTextField(minLines, maxLines, trailing)`.

| Where (file:line) | What it is now | Kit replacement |
|---|---|---|
| choir/ChoirScreen.kt:338-365 (call 238) | `ProblemField`, local copy of the text field with 3 lines and a mic inside | `SwarmTextField(text, onChange, "Problem", helper = ..., minLines = 3, trailing = { mic })` |
| choir/ChoirScreen.kt:369-390 (call 361) | `MicButton`, hand-drawn microphone in a circle | `LineIcon(SwarmIcon.Mic, tint = ...)` inside a 48 dp Box in the `trailing` slot (keep the listening fill locally) |
| choir/ChoirScreen.kt:393-405 (call 247) | `KindChip` | `SelectChip(kindLabel(k), k == kind, onClick)` |
| choir/ChoirScreen.kt:408-415 (call 299) | `KindTag`, outlined pill | `Tag(text)` (Smudge block, no outline) |
| choir/ChoirScreen.kt:260 | stock Material `Switch` | `InkSwitch(useTest, { useTest = it })` (or `null` when the row toggles) |
| choir/ChoirScreen.kt:587-607 (call 501) | `MatrixCard` grid of tokens and cells | `AgreementMatrix(m.names, m.same)` inside the existing card and header row |
| choir/ChoirScreen.kt:610-622 | `MatrixCell` | `TickMark(agree, size = 26.dp, boxed = true)` (done by `AgreementMatrix`) |
| choir/ChoirScreen.kt:669-693 (calls 644-647) | `ScoreBar(label, k, n, meanMs, bold)` | `ScoreBar(label, k, n, emphasised = bold, note = "mean ${secs(ms)} s")` |
| choir/ChoirScreen.kt:724-731 (calls 711-714) | `MarkCell` tick or hatched box | `TickMark(right)` |
| choir/ChoirScreen.kt:632 | title row `Text(display)` + `Stamp` | `ScreenHeader(title) { Stamp(...) }` |
| pdu/PduAsk.kt:72-78 | `BackLink` | `BackLink(onClick)` (delete the local one) |
| pdu/PduAsk.kt:81-86 (calls 125, PduAudit.kt:60, PduScan.kt:110) | `PageHeader(title, onBack)` | `BackLink(onBack)` above `ScreenHeader(title)` |
| pdu/PduAsk.kt:167-207 (call 126), glyph at 194 | `QuestionField`, text field with a mic at its right end | `SwarmTextField(text, onValue, "Question", helper, error, trailing = { Box(48.dp) { LineIcon(SwarmIcon.Mic, tint) } })` |
| pdu/PduAsk.kt:211-229 (call 138) | `TimingStrip(q)` built with FlowRow and annotated pairs | `TimingStrip(listOf("embed" to ms(t.embedMs), "search" to ..., d.name to ..., "first word" to ..., "total" to ...))` |
| pdu/PduAsk.kt:286-293 | `StreamingText` plus a FlowRow of `AnswerChip("[n]", Modifier.clickable)` | `CitedText(q.answer, streaming, onCite, cites = 1..q.sources.size)`; for a separate row use `CiteChip(n, onClick)` |
| pdu/PduAsk.kt:235 | `Text("[${s.n}]", mono)` label on a source card | `CiteChip(s.n, onOpen)` if it should be tappable, else keep |
| pdu/PduIcons.kt:24-85 | `PduIcon`, `PduGlyph` (Photo, Folder, Scan, Lock, Camera, Mic) | `SwarmIcon.{Photo, Folder, Scan, Lock, Camera, Mic}` with `LineIcon(icon, size = 24.dp)`; same drawings, delete the file's enum and glyph |
| pdu/PduIcons.kt:87-93 (uses PduScreen.kt:170, 186) | `PduIconBox` 40 dp outlined square | keep as a 3-line local Box around `LineIcon`, or ask for a kit `IconBox` |
| pdu/PduScreen.kt:139, 144, 146, 150 | `SourceRow(PduIcon.X, ...)` | `SwarmIcon.X` |
| pdu/PduScreen.kt:196 | stock `Switch` | `InkSwitch(f.isPrivate, { PduEngine.setFolderPrivate(f.folder, it) })` |
| pdu/PduScan.kt:188 | stock `Switch` | `InkSwitch(keepPrivate, { keepPrivate = it })` |
| link/LinkScreen.kt:98-100 (calls 254, 305, 343) | `Back("< Swarm")` in Material grey | `BackLink(onBack, label = "Back")` |
| link/LinkScreen.kt:145 | `OutlinedTextField` "Peer IP or ip:port" | `SwarmTextField(ip, { ip = it.trim() }, "Peer IP or ip:port", mono = true)` |
| link/LinkScreen.kt:317-319 | `OutlinedTextField` pasted code, 3 lines | `SwarmTextField(manual, { manual = it }, "Paste the pairing link or code", mono = true, minLines = 3, maxLines = 3)` |
| link/LinkUi.kt:47 | `Token(name, size)` wrapper | `PhoneToken(name, size)` |
| link/LinkUi.kt:50-56 | `PrimaryBtn`, `SecondaryBtn` wrappers | `PrimaryButton(text, onClick, Modifier.fillMaxWidth())`, `SecondaryButton(...)` |
| link/LinkUi.kt:58-62 | `MetricRow(label, value, valueColor)` | `MetricRow(label, value, unit)` (no value colour: states are words and stamps) |
| link/LinkUi.kt:30-37, 40-45 | `OkColor/WarnColor/BadColor/DimColor`, `MonoStyle/MonoBig`, `tokenLetter` | `SwarmColors.ink/graphite`, `SwarmType.mono/monoHero`, `phoneLetter` (internal, same module) |
| link/LinkScreen.kt:156-177 | `PeerRow` with Material text colours and `HorizontalDivider` | `PhoneToken` + `Tag(state)` + `SwarmType.mono` lines + `Hairline()`; `Stamp("Offline", caution = true)` for away |
| link/LinkScreen.kt:179-234 | `Topology`, BoxWithConstraints, Canvas lines, Material colours | `TopologyPanel(Modifier.height(220.dp)) { DoodleConnector(a, b, label = "56 Mbps\n11 ms", dashed = away, gap = 24.dp, labelAt = 0.62f); PhoneNode(name, away = away, ...) }` |
| shell/SettingsSheet.kt:236-250 (call 230) | private `InkSwitch(checked)` | `InkSwitch(removed, null)` (the row stays the toggle) |
| llm/ModelsScreen.kt:294-299 | private `Tag(text)` | `Tag(text)` |
| llm/ModelsScreen.kt:213 | `TextButton { Text("Back") }` | `BackLink(onBack)` |
| llm/ModelsScreen.kt (41 refs), e.g. 301-306 `stateColor` | legacy aliases `SwarmColors.Dim/Ok/Warn/Bad/Text/Surface/Line`, `MetricStyle`, `LabelStyle`; status shown as colour | `SwarmColors.graphite/ink/sheet/smudge`, `SwarmType.mono/label`; state as `Stamp` or `Tag` (screen is not restyled yet) |
| sched/ThroughputPanel.kt:133-148 (30 legacy refs) | `ResultCard` with `LabelStyle` "TOKENS/S" caps label, `Warn` colour for running and FAKE | `SwarmCard { HeroMetric(value, "tok/s"); MetricRow(...); Stamp("Fake", caution = true) }` |
| sched/ThroughputPanel.kt:151-153 | `Cell` mono text | `Text(..., SwarmType.mono)` (no kit piece needed) |

Not provided by the kit, still local and fine: choir `Mono11`/`Mono11Dim` (88-89, a mono 11 sp style), choir `ScoreTable` (696-720) and `PhonesCard` (418-436), pdu `Corners` viewfinder (PduScan.kt:216), shell `SetupRow`/`Wordmark`, Models sparkline (ModelsScreen.kt:280).
