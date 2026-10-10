package com.swarmos.app.link

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.Ports
import com.swarmos.app.ui.BackLink
import com.swarmos.app.ui.DoodleConnector
import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.PhoneNode
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.ScreenHeader
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import com.swarmos.app.ui.TopologyPanel
import kotlinx.coroutines.launch

private sealed interface Page {
    data object Home : Page
    data object Host : Page
    data object Join : Page
    data class Detail(val key: String) : Page
}

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.CAMERA,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
)

/** The Swarm tab: home (topology and peers), host (QR), join (scanner), link detail (probe result). */
@Composable
fun LinkScreen() {
    var page by remember { mutableStateOf<Page>(Page.Home) }
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val missing = RUNTIME_PERMISSIONS.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    BackHandler(page != Page.Home) { page = Page.Home }
    when (val p = page) {
        Page.Home -> HomePage(onHost = { page = Page.Host }, onJoin = { page = Page.Join }, onLink = { page = Page.Detail(it) })
        Page.Host -> HostPage(onBack = { page = Page.Home })
        Page.Join -> JoinPage(onBack = { page = Page.Home })
        is Page.Detail -> DetailPage(p.key, onBack = { page = Page.Home })
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

private val Dim get() = SwarmColors.graphite

// ------------------------------------------------------------------ home

@Composable
private fun HomePage(onHost: () -> Unit, onJoin: () -> Unit, onLink: (String) -> Unit) {
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val peers by PeerLinks.peers.collectAsStateWithLifecycle()
    val rejected by PeerLinks.rejected.collectAsStateWithLifecycle()
    val results by LinkRuntime.results.collectAsStateWithLifecycle()
    val internet by InternetState.validated.collectAsStateWithLifecycle()
    val message by LinkRuntime.message.collectAsStateWithLifecycle()
    val serverError by LinkRuntime.serverError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("") }

    val online = peers.map { it.nodeId }.distinct().count { id -> peers.any { it.nodeId == id && it.state == LinkState.ONLINE } }
    val firstOnline = peers.firstOrNull { it.state == LinkState.ONLINE }

    PageColumn {
        ScreenHeader("Swarm", "${online + 1} phones online, ${peers.count { it.state == LinkState.ONLINE }} links")
        serverError?.let { Text(it, style = SwarmType.body, fontWeight = androidx.compose.ui.text.font.FontWeight.W700) }
        KeptAwakeLine()

        Column {
            MetricRow("This phone", "${self.name}  ${self.ip}:${Ports.NODE}")
            MetricRow("Pairing code", Pairing.code(AppGraph.pairingSecret))
            MetricRow("Internet validated", if (internet) "yes" else "no")
        }

        Topology(self.name, peers, results)

        SectionTitle("Peers")
        if (peers.isEmpty()) Text("No peers yet. Phones on the same Wi-Fi appear here on their own.", style = SwarmType.body.copy(color = Dim))
        peers.groupBy { it.nodeId }.values.forEach { PeerCard(it) { key -> onLink(key) } }
        capacityLine(peers, results)?.let { Text(it, style = SwarmType.mono.copy(fontSize = 12.sp, color = Dim)) }
        rejected.forEach { Text("${it.name ?: it.ip} at ${it.ip}: ${it.reason ?: "rejected our signature"}", style = SwarmType.body) }
        message?.let { Text(it, style = SwarmType.body.copy(color = Dim)) }

        // the scheduler's load panel (tokens per second across the phones); it scrolls inside its own box
        com.swarmos.app.sched.ThroughputPanel(Modifier.height(340.dp))

        PrimaryButton("Host a swarm", onHost, Modifier.fillMaxWidth(), icon = SwarmIcon.Plus)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Join", onJoin, Modifier.weight(1f), icon = SwarmIcon.Join)
            SecondaryButton("Measure link", { firstOnline?.let { onLink(it.key) } }, Modifier.weight(1f), enabled = firstOnline != null, icon = SwarmIcon.Gauge)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            SwarmTextField(ip, { ip = it.trim() }, "Peer IP or ip:port", Modifier.weight(1f), mono = true)
            SecondaryButton("Add", {
                val host = ip.substringBefore(':')
                val port = ip.substringAfter(':', "").toIntOrNull() ?: Ports.NODE
                scope.launch { LinkRuntime.addPeer(host, port) }
            }, enabled = ip.isNotEmpty())
        }
    }
}

/** One phone with every path to it as a small tag: live round trip and the last measured Mbps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerCard(paths: List<PeerStatus>, onClick: (String) -> Unit) {
    val ordered = paths.sortedBy { it.link }
    val up = ordered.filter { it.state == LinkState.ONLINE }
    val head = up.firstOrNull() ?: ordered.first()
    val best = MeasuredPolicy().order(up, Use.SMALL).firstOrNull() ?: head
    Column(Modifier.fillMaxWidth().clickable { onClick(best.key) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken(head.name, 32.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(head.name, style = SwarmType.title)
                Text(best.ip, style = SwarmType.mono.copy(fontSize = 12.sp, color = Dim))
            }
            if (up.isEmpty()) Stamp("Offline", caution = true, small = true)
            else Column(horizontalAlignment = Alignment.End) {
                val bat = head.health?.batteryPct?.takeIf { it >= 0 }?.let { "$it%" } ?: "-"
                Text("${fmt1(best.rttMs)} ms", style = SwarmType.mono)
                Text("${thermalLabel(head.health?.thermalStatus)}  $bat", style = SwarmType.mono.copy(fontSize = 12.sp, color = Dim))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ordered.forEach { p ->
                val text = buildString {
                    append(p.link.name)
                    if (p.state == LinkState.ONLINE) {
                        p.ewmaRttMs?.let { append("  ${"%.0f".format(it)} ms") }
                        p.lastMbps?.let { append("  ${"%.0f".format(it)} Mbps") }
                    } else append("  away")
                }
                Tag(text)
            }
        }
        // why a path is away, when the phone says so: a backgrounded app is frozen by the OS and returns on its own
        ordered.firstOrNull { it.state == LinkState.AWAY && it.awayReason != null }?.let {
            val text = if (it.awayReason == "left the foreground") "${it.name} left the foreground; it rejoins by itself when the app is back in front"
            else "${it.name}: ${it.awayReason}"
            Text(text, style = SwarmType.body.copy(fontSize = 12.sp, color = Dim))
        }
        if (up.isNotEmpty() && head.peerForeground == false) Text("${head.name} is in the background and may be frozen soon", style = SwarmType.body.copy(fontSize = 12.sp, color = Dim))
    }
    Hairline()
}

/** One line of capacity from measured values only: the best single path and, if one was measured, the multipath total. */
private fun capacityLine(peers: List<PeerStatus>, results: List<TputResult>): String? {
    val online = peers.filter { it.state == LinkState.ONLINE && it.lastMbps != null }
    val best = online.maxByOrNull { it.lastMbps!! } ?: return null
    val multi = results.firstOrNull { it.toNodeId == best.nodeId && it.link.contains('+') && it.mbpsReceiver != null }
    return "Capacity to ${best.name}: ${"%.0f".format(best.lastMbps)} Mbps over ${best.link.name}" +
        (multi?.let { "; ${"%.0f".format(it.mbpsReceiver)} Mbps over ${it.link} together" } ?: "")
}

/** The swarm drawing: this phone on top, every peer below it, one connector per peer labelled with measured numbers. */
@Composable
private fun Topology(selfName: String, peers: List<PeerStatus>, results: List<TputResult>) {
    val nodes = peers.groupBy { it.nodeId }.values.map { g ->
        MeasuredPolicy().order(g.filter { it.state == LinkState.ONLINE }, Use.SMALL).firstOrNull() ?: g.first()
    }
    TopologyPanel(Modifier.height(220.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val selfC = DpOffset(w / 2, 36.dp)
            val peerY = 160.dp
            fun peerC(i: Int) = DpOffset(w * (i + 1) / (nodes.size + 1), peerY)
            nodes.forEachIndexed { i, p ->
                val mbps = results.firstOrNull { it.toNodeId == p.nodeId && it.link == p.link.name && it.mbpsReceiver != null }?.mbpsReceiver
                val label = listOfNotNull(mbps?.let { "${fmt1(it)} Mbps" }, p.rttMs?.let { "${fmt1(it)} ms" }, if (p.state == LinkState.AWAY) "away" else null).joinToString("\n")
                DoodleConnector(
                    selfC, peerC(i), Modifier.fillMaxSize(), label = label.ifEmpty { null }, dashed = p.state != LinkState.ONLINE,
                    gap = 26.dp, startGap = 30.dp, labelAt = 0.62f, seed = 7 + i, fromCaption = 36.dp,
                )
            }
            Box(Modifier.offset(selfC.x - 45.dp, selfC.y - 17.dp).width(90.dp), contentAlignment = Alignment.TopCenter) { PhoneNode(selfName) }
            nodes.forEachIndexed { i, p ->
                val c = peerC(i)
                Box(Modifier.offset(c.x - 45.dp, c.y - 17.dp).width(90.dp), contentAlignment = Alignment.TopCenter) { PhoneNode(p.name, away = p.state != LinkState.ONLINE) }
            }
            if (nodes.isEmpty()) Text("no peers", Modifier.offset(w / 2 - 28.dp, 150.dp), style = SwarmType.mono.copy(color = Dim))
        }
    }
}

/** Says whether the screen is held on, from the window flag the activity really set, and which Wi-Fi lock is held. */
@Composable
private fun KeptAwakeLine() {
    val ctx = LocalContext.current
    val fg by AppForeground.foreground.collectAsStateWithLifecycle()
    val lock by WifiLocks.held.collectAsStateWithLifecycle()
    var act = ctx
    while (act is android.content.ContextWrapper && act !is android.app.Activity) act = act.baseContext
    val flag = ((act as? android.app.Activity)?.window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    val awake = flag && fg
    Text(
        if (awake) "Kept awake: the screen stays on while this app is in front. Wi-Fi lock ${lock.name.lowercase()}."
        else "Not kept awake: the app is in the background, so the system may freeze it and peers will lose this phone.",
        style = SwarmType.body.copy(fontSize = 12.sp, color = Dim),
    )
}

/** Asks before a scanned or opened pairing code replaces this phone's secret (the debug extra `confirm` skips it). */
@Composable
fun PairingConfirmDialog() {
    val pending by LinkRuntime.pendingPairing.collectAsStateWithLifecycle()
    val p = pending ?: return
    AlertDialog(
        onDismissRequest = { LinkRuntime.cancelPairing() },
        containerColor = SwarmColors.sheet,
        title = { Text("Pair with ${p.name}?", style = SwarmType.title) },
        text = {
            Text(
                "This phone will take that swarm's pairing secret and join it${if (p.p2p != null) ", including its Wi-Fi Direct group" else ""}. " +
                    "Check that ${p.name} shows the code ${Pairing.code(p.secret)}.",
                style = SwarmType.body,
            )
        },
        confirmButton = { TextButton(onClick = { LinkRuntime.confirmPairing() }) { Text("Pair", color = SwarmColors.ink) } },
        dismissButton = { TextButton(onClick = { LinkRuntime.cancelPairing() }) { Text("Cancel", color = SwarmColors.ink) } },
    )
}

// ------------------------------------------------------------------ host

private fun copyPairingLink(ctx: Context, link: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ustaad pairing link", link))
    Toast.makeText(ctx, "Pairing link copied", Toast.LENGTH_SHORT).show()
}

private fun sharePairingLink(ctx: Context, link: String, hostName: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Join $hostName on Ustaad")
        .putExtra(Intent.EXTRA_TEXT, link)
    ctx.startActivity(Intent.createChooser(send, "Share pairing link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun HostPage(onBack: () -> Unit) {
    val hosting by LinkRuntime.hosting.collectAsStateWithLifecycle()
    val direct by DirectLink.state.collectAsStateWithLifecycle()
    val peers by PeerLinks.peers.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start(directMode: Boolean) {
        busy = true; error = null
        scope.launch {
            runCatching { LinkRuntime.startHosting(directMode) }.onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    PageColumn {
        BackLink(onBack)
        ScreenHeader("Host a swarm") { if (hosting != null) Stamp("Host active") }
        val h = hosting
        if (h != null) {
            val link = remember(h.payload) { Pairing.toLink(h.payload) }
            val bmp = remember(link) { QrCode.bitmap(link).asImageBitmap() }
            SwarmCard(owner = self.name) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhoneToken(self.name, 32.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("${self.name} (coordinator)", style = SwarmType.title)
                            Text("Local node", style = SwarmType.mono.copy(fontSize = 12.sp, color = Dim))
                        }
                    }
                    Image(bmp, "Pairing QR", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                    MetricRow("network", h.payload.p2p?.ssid ?: "current Wi-Fi")
                    MetricRow("band", if (h.payload.p2p != null) listOfNotNull(direct.band, direct.frequencyMhz?.let { "$it MHz" }).joinToString("  ").ifEmpty { "-" } else "-")
                    h.payload.ips.forEachIndexed { i, ip -> MetricRow(if (i == 0) "address" else "", "$ip:${h.payload.port}") }
                    direct.formationMs?.let { MetricRow("group formed in", "$it", "ms") }
                    MetricRow("pairing code", Pairing.code(h.payload.secret))
                    Text("The other phone shows this code before it pairs. Without a camera, copy or share the link and paste it on the Join page.", style = SwarmType.body.copy(fontSize = 12.sp, color = Dim))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Copy link", { copyPairingLink(ctx, link) }, Modifier.weight(1f))
                        SecondaryButton("Share link", { sharePairingLink(ctx, link, self.name) }, Modifier.weight(1f))
                    }
                }
            }
            SectionTitle("Joined")
            val joined = peers.filter { it.state == LinkState.ONLINE }.groupBy { it.nodeId }.values
            if (joined.isEmpty()) Text("waiting for a phone to scan", style = SwarmType.body.copy(color = Dim))
            joined.forEach { g ->
                val p = g.first()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneToken(p.name, 28.dp)
                    Text(p.name, Modifier.weight(1f).padding(start = 10.dp), style = SwarmType.title)
                    Tag(g.joinToString(" ") { it.link.name })
                }
                Hairline()
            }
            SecondaryButton("Stop hosting", { LinkRuntime.stopHosting() }, Modifier.fillMaxWidth())
        } else {
            Text(
                "A fresh pairing secret is made when hosting starts. Phones that scan the code adopt it; phones still on the old secret drop out of the list.",
                style = SwarmType.body.copy(color = Dim),
            )
            PrimaryButton(if (busy) "Starting..." else "Host over Wi-Fi Direct", { start(true) }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton("Use current Wi-Fi instead", { start(false) }, Modifier.fillMaxWidth(), enabled = !busy)
        }
        (error ?: direct.error)?.let { Text(it, style = SwarmType.body) }
    }
}

// ------------------------------------------------------------------ join

@Composable
private fun JoinPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val message by LinkRuntime.message.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    var cameraOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraOk = it }
    var manual by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf(false) }

    PageColumn {
        BackLink(onBack)
        ScreenHeader("Join a swarm")
        if (cameraOk) {
            // the box clips the camera view to its rounded outline (a TextureView otherwise paints over the text below it)
            Box(Modifier.fillMaxWidth().height(320.dp).clip(SwarmShape).border(1.5.dp, SwarmColors.ink, SwarmShape)) {
                QrScanner(Modifier.fillMaxSize()) { text ->
                    scanned = true
                    LinkRuntime.handleDeepLink(text)
                }
            }
            Text("Point at the host phone's code", style = SwarmType.title)
        } else {
            Text("The camera permission is needed to scan the host's code.", style = SwarmType.body)
            PrimaryButton("Allow camera", { camLauncher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth(), icon = SwarmIcon.Camera)
        }
        SwarmTextField(manual, { manual = it }, "Paste the pairing link or code", mono = true, minLines = 3, maxLines = 3, singleLine = false)
        SecondaryButton("Pair with pasted code", { LinkRuntime.handleDeepLink(manual) }, Modifier.fillMaxWidth(), enabled = manual.isNotBlank())
        SwarmCard(owner = self.name) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PhoneToken(self.name, 32.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(self.name, style = SwarmType.title)
                    Text(message ?: if (scanned) "Pairing..." else "Awaiting pairing payload", style = SwarmType.mono.copy(fontSize = 12.sp, color = Dim))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ link detail

@Composable
private fun DetailPage(key: String, onBack: () -> Unit) {
    val session = remember(key) { PeerLinks.session(key) }
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val results by LinkRuntime.results.collectAsStateWithLifecycle()
    val progress by LinkRuntime.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastError by remember { mutableStateOf<String?>(null) }

    PageColumn {
        BackLink(onBack)
        if (session == null) {
            Text("That link is gone.", style = SwarmType.body)
            return@PageColumn
        }
        val st by session.status.collectAsStateWithLifecycle()
        val runs = results.filter { it.toNodeId == st.nodeId && it.link == st.link.name }
        val last = runs.firstOrNull()
        val measuring = progress?.peerKey == key

        ScreenHeader("Link ${tokenLetter(self.name)} to ${tokenLetter(st.name)}")
        SwarmCard(owner = self.name) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneToken(self.name, 30.dp); Text(self.name, style = SwarmType.mono)
                Text("to", style = SwarmType.mono.copy(color = Dim))
                PhoneToken(st.name, 30.dp); Text(st.name, style = SwarmType.mono)
            }
        }
        HeroMetric(fmt1(last?.mbpsReceiver), "Mbps", note = last?.let { "${it.megabytes} MB in ${fmt1(it.receiverSeconds)} s" })
        Column {
            MetricRow("Round trip avg", last?.rtt?.let { "%.2f".format(it.avgMs) } ?: "-", "ms")
            MetricRow("p95", last?.rtt?.let { "%.2f".format(it.p95Ms) } ?: "-", "ms")
            MetricRow("min", last?.rtt?.let { "%.2f".format(it.minMs) } ?: "-", "ms")
            MetricRow("max", last?.rtt?.let { "%.2f".format(it.maxMs) } ?: "-", "ms")
            MetricRow("Standard", last?.local?.standard ?: "-")
            MetricRow("PHY speed", last?.local?.phyMbps?.toString() ?: "-", "Mbps")
            MetricRow("Frequency", last?.local?.frequencyMhz?.toString() ?: "-", "MHz")
            MetricRow("Link type", st.link.name)
            MetricRow("Heartbeat", "${st.state.name.lowercase()}  ${fmt1(st.rttMs)}", "ms")
        }
        progress?.takeIf { it.peerKey == key }?.let {
            val pct = if (it.totalBytes > 0) (100 * it.doneBytes / it.totalBytes).toInt() else 0
            Text(if (it.phase == "rtt") "Measuring round trip..." else "Sending $pct%", style = SwarmType.body.copy(color = Dim))
        }
        lastError?.let { Text(it, style = SwarmType.body) }

        SectionTitle("Earlier runs")
        if (runs.isEmpty()) Text("none yet", style = SwarmType.body.copy(color = Dim))
        runs.take(6).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(it.time.substringAfter('T').take(8), style = SwarmType.mono.copy(color = Dim))
                Text(it.link + (it.local?.band?.let { b -> "  $b" } ?: ""), style = SwarmType.mono.copy(color = Dim))
                Text("${fmt1(it.mbpsReceiver)} Mbps", style = SwarmType.mono)
            }
            Hairline()
        }

        fun run(mb: Int) {
            lastError = null
            scope.launch {
                val r = LinkRuntime.measure(st.nodeId, st.link, mb)
                lastError = r.error
            }
        }
        PrimaryButton(if (measuring) "Measuring..." else "Measure again (100 MB)", { run(100) }, Modifier.fillMaxWidth(), enabled = !measuring && st.link != LinkType.BT, icon = SwarmIcon.Gauge)
        SecondaryButton("Quick check (10 MB)", { run(10) }, Modifier.fillMaxWidth(), enabled = !measuring && st.link != LinkType.BT)
    }
}
