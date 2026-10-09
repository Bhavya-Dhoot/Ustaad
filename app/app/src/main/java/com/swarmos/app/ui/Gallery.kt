package com.swarmos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Debug-only gallery (adb shell am start -n <pkg>/com.swarmos.app.MainActivity --ez gallery true --ei galleryPage N).
 * Pages 1 and 2 show every kit composable with sample content; 3 and 4 rebuild the Stitch "Swarm home"
 * and "Agent Choir live run" screens with the kit, as static mocks; 5 and 6 show the second round of
 * kit pieces (icons, fields, chips, tables). Every value here is sample data.
 */

const val GALLERY_PAGES = 6

/** Bottom bar index the gallery page pretends to be on (0 Swarm ... 2 Choir). */
fun galleryTab(page: Int): Int = if (page == 4) 2 else 0

@Composable
fun GalleryPage(page: Int, modifier: Modifier = Modifier) = key(page) { // a new page starts at the top
    Column(
        modifier
            .fillMaxSize()
            .background(SwarmColors.whiteboard)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (page) {
            1 -> KitSheetOne()
            2 -> KitSheetTwo()
            3 -> SwarmHomeMock()
            4 -> ChoirLiveRunMock()
            5 -> KitSheetThree()
            else -> KitSheetFour()
        }
        Text("Gallery page $page of $GALLERY_PAGES, sample values", style = SwarmType.mono.copy(fontSize = 10.sp, color = SwarmColors.graphite))
    }
}

// ---------------------------------------------------------------- page 1

@Composable
private fun KitSheetOne() {
    Text("UI kit", style = SwarmType.display)
    Text("Whiteboard theme, light only", style = SwarmType.label.copy(color = SwarmColors.graphite))

    val swatches = listOf(
        Triple("Whiteboard", SwarmColors.whiteboard, "#F6F7F5"),
        Triple("Sheet", SwarmColors.sheet, "#FCFDFB"),
        Triple("Ink", SwarmColors.ink, "#15171A"),
        Triple("Graphite", SwarmColors.graphite, "#5E636B"),
        Triple("Smudge", SwarmColors.smudge, "#E4E7E3"),
        Triple("Marker A", SwarmColors.markerA, "#1E9BB0"),
        Triple("Marker B", SwarmColors.markerB, "#E08A3C"),
        Triple("Marker C", SwarmColors.markerC, "#C8457E"),
    )
    swatches.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (name, color, hex) ->
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(30.dp).background(color, SwarmShape).border(1.5.dp, SwarmColors.ink, SwarmShape))
                    Text(name, style = SwarmType.label.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1)
                    Text(hex, style = SwarmType.mono.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SwarmColors.graphite))
                }
            }
        }
    }

    Hairline()
    Text("Agent Choir", style = SwarmType.display)
    SectionTitle("Section title in Bricolage 700")
    Text("Body copy is Geist 15 on 22, at most sixty characters a line.", style = SwarmType.body)
    Text("Label is Geist medium 13", style = SwarmType.label)
    Text("192.168.242.23  56 Mbps  11 ms  Qwen3.5-4B-Q4_K_M.gguf", style = SwarmType.mono)
    Note("a handwritten note", Modifier.padding(start = 4.dp))

    Hairline()
    PrimaryButton("Host a swarm", {}, Modifier.fillMaxWidth(), icon = SwarmIcon.Plus)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Join", {}, Modifier.weight(1f), icon = SwarmIcon.Join)
        SecondaryButton("Measure link", {}, Modifier.weight(1f), icon = SwarmIcon.Gauge)
    }

    Hairline()
    HeroMetric("8.4", "tok/s", "measured, 2 phones")
    Column {
        MetricRow("Time to first word", "2.1", "s")
        MetricRow("Hop A to B", "6", "ms")
        MetricRow("Decode", "17.2", "tok/s")
    }

    Hairline()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Stamp("Verified")
        Stamp("Chain ok")
        Stamp("Disagree", caution = true)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Stamp("Offline", caution = true)
        Stamp("Fake", caution = true)
        Stamp("0 B to internet", small = true)
    }
    InkProgress(0.62f)
    Skeleton(18.dp)
}

// ---------------------------------------------------------------- page 2

@Composable
private fun KitSheetTwo() {
    SectionTitle("Layer strip")
    val holders = List(48) { if (it < 16) "Phone A" else if (it < 32) "Phone B" else "Phone C" }
    LayerStrip(holders, List(48) { it < 36 })
    Text("48 layers, 36 loaded, the rest hatched", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))

    Hairline()
    Lane("Phone A", "Solver", trailing = { LaneMetrics("212 tok", "9.4 s") }) {
        StreamingText("Let list price = x. 0.78x = 62,400, so x = 80,000.", streaming = true, style = laneBody)
    }
    Lane("Phone C", "Critic", trailing = { Stamp("Verified", small = true) }) {
        Text("Phone B applied 22% to the discounted total.", style = laneBody)
    }
    SwarmCard(owner = "Phone B") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken("Phone B", 22.dp)
            Spacer(Modifier.width(8.dp))
            Text("Phone B", style = SwarmType.title.copy(fontSize = 14.sp, lineHeight = 18.sp))
            Spacer(Modifier.weight(1f))
            Tag("IMG_2231.jpg")
        }
        Text("Koramangala Computers, tax invoice. Warranty 36 months.", style = laneBody, modifier = Modifier.padding(top = 6.dp))
    }

    Hairline()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneToken("Phone A"); PhoneToken("Phone B"); PhoneToken("Phone C"); PhoneToken("Other")
        Spacer(Modifier.weight(1f))
        RoundDots(current = 2)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AnswerChip("80,000"); AnswerChip("76,128"); Tag("online"); Tag("away")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { SwarmIcon.entries.forEach { LineIcon(it, size = 26.dp) } }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        InkCircle { Text("Rs 80,000", style = SwarmType.monoHero.copy(fontSize = 32.sp)) }
    }
    var question by remember { mutableStateOf("Where is the warranty bill for my laptop?") }
    SwarmTextField(question, { question = it }, "Question", helper = "Searches the index on every phone")
    SwarmTextField("192.168.242.7", {}, "Peer address", error = "Not a valid address", mono = true)
}

private val laneBody
    get() = SwarmType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun LaneMetrics(a: String, b: String) {
    val s = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite)
    Text(a, style = s)
    Spacer(Modifier.width(10.dp))
    Text(b, style = s)
}

// ---------------------------------------------------------------- page 5: icons, fields, chips

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitSheetThree() {
    BackLink({})
    ScreenHeader("Data Union", "ask a question, get cited sources") { Stamp("Fake model", caution = true, small = true) }

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwarmIcon.entries.forEach { icon ->
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LineIcon(icon, size = 26.dp)
                Text(icon.name, style = SwarmType.mono.copy(fontSize = 8.sp, lineHeight = 10.sp, color = SwarmColors.graphite), maxLines = 1)
            }
        }
    }

    Hairline()
    var problem by remember { mutableStateOf("A shop sells a laptop for Rs 62,400 after a 22% discount. What was the list price?") }
    SwarmTextField(
        problem, { problem = it }, "Problem", minLines = 3,
        helper = "speech runs on this phone",
        trailing = { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LineIcon(SwarmIcon.Mic) } },
    )
    var question by remember { mutableStateOf("Where is the warranty bill?") }
    SwarmTextField(question, { question = it }, "Question", trailing = { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LineIcon(SwarmIcon.Mic) } })

    var kind by remember { mutableStateOf(0) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Arithmetic", "Logic", "Units", "Dates").forEachIndexed { i, k -> SelectChip(k, kind == i, { kind = i }) }
    }
    PrimaryButton("Run all 20", {}, Modifier.fillMaxWidth(), icon = SwarmIcon.Play)
    SecondaryButton("Download 2.4 GB", {}, Modifier.fillMaxWidth(), icon = SwarmIcon.Download)
    MonoText("The list price was Rs 80,000 after 22% off, checked in 8.4 s.")

    SwarmCard {
        CitedText(
            "Your laptop is covered until 14 Mar 2028 under the standard 36 month warranty [1], with the extended policy active through 13 Mar 2029 [2].",
            streaming = true, onCite = {}, cites = 1..3,
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { CiteChip(1, {}); CiteChip(2, {}) }
    }
}

// ---------------------------------------------------------------- page 6: tables

@Composable
private fun KitSheetFour() {
    ScreenHeader("Scoreboard", "20 problems, same token caps") { RoundDots(current = 3) }
    TimingStrip(listOf("embed" to "31 ms", "search" to "18 ms", "Phone B" to "64 ms", "first word" to "0.9 s", "total" to "2.4 s"))
    SwarmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreBar("One phone alone", 12, 20, note = "mean 8.1 s")
            ScoreBar("One phone, three samples", 14, 20, note = "mean 24.0 s")
            ScoreBar("Choir", 16, 20, note = "mean 21.4 s")
            ScoreBar("Choir with tool check", 19, 20, emphasised = true, note = "mean 21.4 s")
        }
    }
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agreement matrix", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite), modifier = Modifier.weight(1f))
            Text("2 of 3 agree", style = SwarmType.mono.copy(fontSize = 11.sp))
        }
        Spacer(Modifier.height(8.dp))
        AgreementMatrix(
            listOf("Phone A", "Phone B", "Phone C"),
            listOf(listOf(true, false, true), listOf(false, true, false), listOf(true, false, true)),
        )
    }
    SwarmCard {
        listOf("P01" to true, "P02" to false, "P03" to true).forEachIndexed { i, (id, ok) ->
            if (i > 0) Hairline(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(id, style = SwarmType.mono.copy(fontWeight = FontWeight.W700), modifier = Modifier.weight(1f))
                TickMark(ok)
                Spacer(Modifier.width(16.dp))
                TickMark(!ok)
            }
        }
    }
}

// ---------------------------------------------------------------- page 3: Swarm home, static mock

@Composable
private fun SwarmHomeMock() {
    Column {
        Text("Swarm", style = SwarmType.display)
        Text("3 phones, 1 link, no cloud", style = SwarmType.label.copy(fontSize = 14.sp, color = SwarmColors.graphite), modifier = Modifier.padding(top = 2.dp))
    }
    TopologyPanel(Modifier.height(220.dp)) {
        // token centres in dp inside the panel; the panel is about 352 dp wide on a 384 dp phone
        val a = DpOffset(92.dp, 64.dp)
        val b = DpOffset(215.dp, 150.dp)
        val c = DpOffset(300.dp, 150.dp)
        DoodleConnector(a, b, Modifier.fillMaxSize(), label = "56 Mbps\n11 ms", gap = 24.dp, startGap = 32.dp, labelAt = 0.55f, seed = 7)
        DoodleConnector(b, c, Modifier.fillMaxSize(), dashed = true, gap = 24.dp, startGap = 24.dp, seed = 3)
        DoodleConnector(DpOffset(34.dp, 30.dp), DpOffset(72.dp, 56.dp), Modifier.fillMaxSize(), seed = 11)
        Note("host", Modifier.offset(12.dp, 10.dp), rotation = -5f)
        PhoneNode("Phone A", modifier = Modifier.offset(a.x - 23.dp, a.y - 17.dp))
        PhoneNode("Phone B", modifier = Modifier.offset(b.x - 23.dp, b.y - 17.dp))
        PhoneNode("Phone C", away = true, modifier = Modifier.offset(c.x - 23.dp, c.y - 17.dp))
    }
    SectionTitle("Peers")
    Column {
        PeerRow("Phone B", tag = "online", detail = "192.168.242.23  LAN", right = { PeerStats("11 ms", "Cool  83%") })
        Hairline()
        PeerRow("Phone C", tag = "away", detail = "192.168.242.76  LAN", right = { Stamp("Offline", caution = true) })
    }
    Spacer(Modifier.height(4.dp))
    PrimaryButton("Host a swarm", {}, Modifier.fillMaxWidth(), icon = SwarmIcon.Plus)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Join", {}, Modifier.weight(1f), icon = SwarmIcon.Join)
        SecondaryButton("Measure link", {}, Modifier.weight(1f), icon = SwarmIcon.Gauge)
    }
}

@Composable
private fun PeerRow(name: String, tag: String, detail: String, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PhoneToken(name)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = SwarmType.label.copy(fontSize = 14.sp, fontWeight = FontWeight.W700))
                Tag(tag)
            }
            Text(detail, style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite), modifier = Modifier.padding(top = 2.dp))
        }
        right()
    }
}

@Composable
private fun PeerStats(top: String, bottom: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(top, style = SwarmType.mono.copy(fontWeight = FontWeight.W700))
        Text(bottom, style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite))
    }
}

// ---------------------------------------------------------------- page 4: Agent Choir live run, static mock

@Composable
private fun ChoirLiveRunMock() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text("Agent Choir", style = SwarmType.display, modifier = Modifier.weight(1f))
        RoundDots(current = 2, modifier = Modifier.padding(bottom = 4.dp))
    }
    SwarmCard {
        Text(
            "A shop sells a laptop for Rs 62,400 after a 22% discount. What was the list price?",
            style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.W500),
        )
    }
    ChoirLane("Phone A", "Solver", "212 tok", "9.4 s", "Let list price = x. After 22% discount, selling price is 0.78x = 62,400.\nSolving for x: x = 62,400 / 0.78 = 80,000.", false, "Proposal", "80,000")
    ChoirLane("Phone B", "Alt solver", "184 tok", "8.7 s", "Discount is calculated as 62,400 x 0.22 = 13,728.\nList price = 62,400 + 13,728 = 76,128", true, "Candidate", "76,128")
    ChoirLane("Phone C", "Critic", "140 tok", "6.2 s", "Phone B applied the 22% rate to the discounted total rather than the original list price. Correct formulation requires dividing by (1 - 0.22).", false, "Endorsement", "80,000")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Stamp("Disagree", caution = true)
        Spacer(Modifier.weight(1f))
        Note("B took 22% of the sale price", rotation = -2f)
    }
    Row(
        Modifier.fillMaxWidth().border(1.dp, SwarmColors.smudge, SwarmShape).background(SwarmColors.sheet, SwarmShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Baseline: Phone A alone", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite), modifier = Modifier.weight(1f))
        Tag("76,128")
        Spacer(Modifier.width(10.dp))
        Text("8.1 s", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
    }
    SwarmCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Tool check: 62400 / 0.78 = 80000", style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 15.sp), modifier = Modifier.weight(1f))
            Stamp("Verified")
        }
        Hairline(Modifier.padding(vertical = 8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            InkCircle { Text("Rs 80,000", style = SwarmType.monoHero.copy(fontSize = 34.sp, lineHeight = 38.sp)) }
        }
    }
}

@Composable
private fun ChoirLane(
    name: String, role: String, tokens: String, seconds: String,
    text: String, streaming: Boolean, answerLabel: String, answer: String,
) {
    Lane(name, role, trailing = { LaneMetrics(tokens, seconds) }) {
        StreamingText(text, streaming, style = laneBody)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$answerLabel:", style = SwarmType.mono.copy(fontSize = 10.sp, color = SwarmColors.graphite), modifier = Modifier.weight(1f))
            AnswerChip(answer)
        }
    }
}

// ---------------------------------------------------------------- previews

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 384, heightDp = 900)
@Composable
private fun GalleryPreview1() { SwarmTheme { GalleryPage(1) } }

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 384, heightDp = 900)
@Composable
private fun GalleryPreview3() { SwarmTheme { GalleryPage(3) } }

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 384, heightDp = 900)
@Composable
private fun GalleryPreview4() { SwarmTheme { GalleryPage(4) } }
