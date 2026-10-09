package com.swarmos.app.link

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
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
    // no Surface above the tab content, so set the text colour here (works for dark and light themes)
    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        when (val p = page) {
            Page.Home -> HomePage(onHost = { page = Page.Host }, onJoin = { page = Page.Join }, onLink = { page = Page.Detail(it) })
            Page.Host -> HostPage(onBack = { page = Page.Home })
            Page.Join -> JoinPage(onBack = { page = Page.Home })
            is Page.Detail -> DetailPage(p.key, onBack = { page = Page.Home })
        }
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

@Composable
private fun Back(text: String, onBack: () -> Unit) {
    Text("< $text", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp))
}

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
        Text("Swarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("${online + 1} phones online, ${peers.count { it.state == LinkState.ONLINE }} links", color = MaterialTheme.colorScheme.onSurfaceVariant)
        serverError?.let { Text(it, color = BadColor) }
        KeptAwakeLine()

        Column {
            MetricRow("This phone", "${self.name}  ${self.ip}:${com.swarmos.app.core.Ports.NODE}")
            MetricRow("Pairing secret", Pairing.fingerprint(AppGraph.pairingSecret))
            MetricRow("Internet validated", if (internet) "yes" else "no", if (internet) OkColor else MaterialTheme.colorScheme.onSurface)
        }

        Topology(self.name, peers, results)

        Text("Peers", style = MaterialTheme.typography.titleMedium)
        if (peers.isEmpty()) Text("No peers yet. Phones on the same Wi-Fi appear here on their own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        peers.groupBy { it.nodeId }.values.forEach { PeerCard(it) { key -> onLink(key) } }
        capacityLine(peers, results)?.let { Text(it, style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        rejected.forEach {
            Text("${it.name ?: it.ip} at ${it.ip}: ${it.reason ?: "rejected our signature"}", color = WarnColor)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // the scheduler's load panel (tokens per second across the phones); it scrolls inside its own box
        com.swarmos.app.sched.ThroughputPanel(Modifier.height(340.dp))

        PrimaryBtn("Host a swarm", onClick = onHost)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryBtn("Join", Modifier.weight(1f), onClick = onJoin)
            SecondaryBtn("Measure link", Modifier.weight(1f), enabled = firstOnline != null) { firstOnline?.let { onLink(it.key) } }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ip, { ip = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Peer IP or ip:port") })
            SecondaryBtn("Add", enabled = ip.isNotEmpty()) {
                val host = ip.substringBefore(':')
                val port = ip.substringAfter(':', "").toIntOrNull() ?: com.swarmos.app.core.Ports.NODE
                scope.launch { LinkRuntime.addPeer(host, port) }
            }
        }
    }
}

@Composable
private fun PeerRow(p: PeerStatus, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Token(p.name)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = peerAccent(p.name), fontWeight = FontWeight.Bold)
                Text(p.state.name.lowercase(), color = stateColor(p.state), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${p.ip}:${p.port}", style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.link.name, style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (p.rttMs != null) "${fmt1(p.rttMs)} ms" else "-", style = MonoStyle)
            val bat = p.health?.batteryPct?.takeIf { it >= 0 }?.let { "$it%" } ?: "-"
            Text("${thermalLabel(p.health?.thermalStatus)}  $bat", style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Topology(selfName: String, peers: List<PeerStatus>, results: List<TputResult>) {
    val nodes = peers.groupBy { it.nodeId }.values.map { g ->
        g.firstOrNull { it.link == LinkType.DIRECT && it.state == LinkState.ONLINE } ?: g.firstOrNull { it.state == LinkState.ONLINE } ?: g.first()
    }
    val line = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(230.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        val w = maxWidth
        val h = maxHeight
        val r = 22.dp
        val selfX = w / 2
        val selfY = 48.dp
        val peerY = h - 56.dp
        fun peerX(i: Int) = w * (i + 1) / (nodes.size + 1)

        Canvas(Modifier.fillMaxSize()) {
            nodes.forEachIndexed { i, p ->
                val away = p.state != LinkState.ONLINE
                drawLine(
                    color = if (away) dim else line,
                    start = Offset(selfX.toPx(), selfY.toPx()),
                    end = Offset(peerX(i).toPx(), peerY.toPx()),
                    strokeWidth = if (p.link == LinkType.DIRECT) 3.dp.toPx() else 1.5.dp.toPx(),
                    pathEffect = if (away) PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) else null,
                )
            }
        }
        Column(Modifier.offset(selfX - r - 20.dp, selfY - r), horizontalAlignment = Alignment.CenterHorizontally) {
            Token(selfName, r * 2)
            Text(selfName, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        nodes.forEachIndexed { i, p ->
            Column(Modifier.offset(peerX(i) - r - 20.dp, peerY - r), horizontalAlignment = Alignment.CenterHorizontally) {
                Token(p.name, r * 2)
                Text(p.name, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            val mbps = results.firstOrNull { it.toNodeId == p.nodeId && it.link == p.link.name && it.mbpsReceiver != null }?.mbpsReceiver
            val label = listOfNotNull(mbps?.let { "${fmt1(it)} Mbps" }, p.rttMs?.let { "${fmt1(it)} ms" }, if (p.state == LinkState.AWAY) "away" else null).joinToString("\n")
            if (label.isNotEmpty()) {
                Text(
                    label, style = MonoStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .offset(selfX + (peerX(i) - selfX) * 0.62f - 36.dp, selfY + (peerY - selfY) * 0.62f - 16.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 4.dp),
                )
            }
        }
        if (nodes.isEmpty()) Text("no peers", Modifier.offset(w / 2 - 28.dp, h - 70.dp), color = dim)
    }
}

// ------------------------------------------------------------------ paths, awake, pairing

/** One phone with every path to it as a small chip: live round trip and the last measured Mbps. */
@Composable
private fun PeerCard(paths: List<PeerStatus>, onClick: (String) -> Unit) {
    val ordered = paths.sortedBy { it.link }
    val head = ordered.firstOrNull { it.state == LinkState.ONLINE } ?: ordered.first()
    val anyOnline = ordered.any { it.state == LinkState.ONLINE }
    val best = ordered.filter { it.state == LinkState.ONLINE }.let { MeasuredPolicy().order(it, Use.SMALL).firstOrNull() } ?: head
    Column(Modifier.fillMaxWidth().clickable { onClick(best.key) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Token(head.name)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(head.name, color = peerAccent(head.name), fontWeight = FontWeight.Bold)
                    Text(if (anyOnline) "online" else "away", color = stateColor(if (anyOnline) LinkState.ONLINE else LinkState.AWAY), fontSize = 12.sp)
                }
                Text(best.ip, style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                val bat = head.health?.batteryPct?.takeIf { it >= 0 }?.let { "$it%" } ?: "-"
                Text(if (anyOnline) "${fmt1(best.rttMs)} ms" else "-", style = MonoStyle)
                Text("${thermalLabel(head.health?.thermalStatus)}  $bat", style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ordered.forEach { PathChip(it) }
        }
        // why a path is away, when the phone says so: a backgrounded app is frozen by the OS and returns on its own
        ordered.firstOrNull { it.state == LinkState.AWAY && it.awayReason != null }?.let {
            val text = if (it.awayReason == "left the foreground") "${it.name} left the foreground; it rejoins by itself when the app is back in front"
            else "${it.name}: ${it.awayReason}"
            Text(text, color = WarnColor, fontSize = 12.sp)
        }
        if (anyOnline && head.peerForeground == false) Text("${head.name} is in the background and may be frozen soon", color = WarnColor, fontSize = 12.sp)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun PathChip(p: PeerStatus) {
    val up = p.state == LinkState.ONLINE
    val text = buildString {
        append(p.link.name)
        if (up) {
            p.ewmaRttMs?.let { append("  ${"%.0f".format(it)} ms") }
            p.lastMbps?.let { append("  ${"%.0f".format(it)} Mbps") }
        } else append("  away")
    }
    Text(
        text, style = MonoStyle.copy(fontSize = 11.sp),
        color = if (up) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.border(1.dp, if (up) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** One line of capacity from measured values only: the best single path and, if one was measured, the multipath total. */
private fun capacityLine(peers: List<PeerStatus>, results: List<TputResult>): String? {
    val online = peers.filter { it.state == LinkState.ONLINE && it.lastMbps != null }
    val best = online.maxByOrNull { it.lastMbps!! } ?: return null
    val multi = results.firstOrNull { it.toNodeId == best.nodeId && it.link.contains('+') && it.mbpsReceiver != null }
    return "Capacity to ${best.name}: ${"%.0f".format(best.lastMbps)} Mbps over ${best.link.name}" +
        (multi?.let { "; ${"%.0f".format(it.mbpsReceiver)} Mbps over ${it.link} together" } ?: "")
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
        color = if (awake) MaterialTheme.colorScheme.onSurfaceVariant else WarnColor, fontSize = 12.sp,
    )
}

/** Asks before a scanned or opened pairing code replaces this phone's secret (the debug extra `confirm` skips it). */
@Composable
fun PairingConfirmDialog() {
    val pending by LinkRuntime.pendingPairing.collectAsStateWithLifecycle()
    val p = pending ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { LinkRuntime.cancelPairing() },
        title = { Text("Pair with ${p.name}?") },
        text = {
            Text(
                "This phone will take that swarm's pairing secret and join it${if (p.p2p != null) ", including its Wi-Fi Direct group" else ""}. " +
                    "Check that ${p.name} shows the code ${Pairing.fingerprint(p.secret)}.",
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { LinkRuntime.confirmPairing() }) { Text("Pair") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { LinkRuntime.cancelPairing() }) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ host

@Composable
private fun HostPage(onBack: () -> Unit) {
    val hosting by LinkRuntime.hosting.collectAsStateWithLifecycle()
    val direct by DirectLink.state.collectAsStateWithLifecycle()
    val peers by PeerLinks.peers.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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
        Back("Swarm", onBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Host a swarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (hosting != null) Text("host active", color = OkColor, fontWeight = FontWeight.SemiBold)
        }
        val h = hosting
        if (h != null) {
            val link = remember(h.payload) { Pairing.toLink(h.payload) }
            val bmp = remember(link) { QrCode.bitmap(link).asImageBitmap() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Token(self.name)
                Text("${self.name} (coordinator)", fontWeight = FontWeight.SemiBold)
            }
            Image(bmp, "Pairing QR", Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White))
            Column {
                MetricRow("network", h.payload.p2p?.ssid ?: "current Wi-Fi")
                MetricRow("band", if (h.payload.p2p != null) listOfNotNull(direct.band, direct.frequencyMhz?.let { "$it MHz" }).joinToString("  ").ifEmpty { "-" } else "-")
                MetricRow("address", h.payload.ips.joinToString("  ") + ":" + h.payload.port)
                direct.formationMs?.let { MetricRow("group formed in", "$it ms") }
                MetricRow("secret", Pairing.fingerprint(h.payload.secret))
            }
            val joined = peers.filter { it.state == LinkState.ONLINE }
            Text("Joined", style = MaterialTheme.typography.titleMedium)
            if (joined.isEmpty()) Text("waiting for a phone to scan", color = MaterialTheme.colorScheme.onSurfaceVariant)
            joined.forEach { PeerRow(it) {} }
            SecondaryBtn("Stop hosting", Modifier.fillMaxWidth()) { LinkRuntime.stopHosting() }
        } else {
            Text(
                "A fresh pairing secret is made when hosting starts. Phones that scan the code adopt it; phones still on the old secret drop out of the list.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryBtn(if (busy) "Starting..." else "Host over Wi-Fi Direct", enabled = !busy) { start(true) }
            SecondaryBtn("Use current Wi-Fi instead", Modifier.fillMaxWidth(), enabled = !busy) { start(false) }
        }
        (error ?: direct.error)?.let { Text(it, color = BadColor) }
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
        Back("Swarm", onBack)
        Text("Join a swarm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (cameraOk) {
            QrScanner(Modifier.fillMaxWidth().height(320.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))) { text ->
                scanned = true
                LinkRuntime.handleDeepLink(text)
            }
            Text("Point at the host phone's code", fontWeight = FontWeight.Medium)
        } else {
            Text("The camera permission is needed to scan the host's code.")
            PrimaryBtn("Allow camera") { camLauncher.launch(Manifest.permission.CAMERA) }
        }
        OutlinedTextField(
            manual, { manual = it }, Modifier.fillMaxWidth(), label = { Text("Paste swarmos://pair link or code") }, maxLines = 3,
        )
        SecondaryBtn("Pair with pasted code", Modifier.fillMaxWidth(), enabled = manual.isNotBlank()) { LinkRuntime.handleDeepLink(manual) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Token(self.name)
            Column {
                Text(self.name, fontWeight = FontWeight.SemiBold)
                Text(message ?: if (scanned) "Pairing..." else "Awaiting pairing payload", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Back("Swarm", onBack)
        if (session == null) {
            Text("That link is gone.")
            return@PageColumn
        }
        val st by session.status.collectAsStateWithLifecycle()
        val runs = results.filter { it.toNodeId == st.nodeId && it.link == st.link.name }
        val last = runs.firstOrNull()
        val measuring = progress?.peerKey == key

        Text("Link ${tokenLetter(self.name)} to ${tokenLetter(st.name)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Token(self.name); Text(self.name, color = peerAccent(self.name)); Text("to"); Token(st.name); Text(st.name, color = peerAccent(st.name))
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fmt1(last?.mbpsReceiver), style = MonoBig)
            Text("Mbps", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            last?.let { Text("${it.megabytes} MB in ${fmt1(it.receiverSeconds)} s", style = MonoStyle, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        Column {
            MetricRow("Round trip avg", last?.rtt?.let { "${"%.2f".format(it.avgMs)} ms" } ?: "-")
            MetricRow("p95", last?.rtt?.let { "${"%.2f".format(it.p95Ms)} ms" } ?: "-")
            MetricRow("min", last?.rtt?.let { "${"%.2f".format(it.minMs)} ms" } ?: "-")
            MetricRow("max", last?.rtt?.let { "${"%.2f".format(it.maxMs)} ms" } ?: "-")
            MetricRow("Standard", last?.local?.standard ?: "-")
            MetricRow("PHY speed", last?.local?.phyMbps?.let { "$it Mbps" } ?: "-")
            MetricRow("Frequency", last?.local?.frequencyMhz?.let { "$it MHz" } ?: "-")
            MetricRow("Link type", st.link.name)
            MetricRow("Heartbeat", "${st.state.name.lowercase()}  ${fmt1(st.rttMs)} ms")
        }
        progress?.takeIf { it.peerKey == key }?.let {
            val pct = if (it.totalBytes > 0) (100 * it.doneBytes / it.totalBytes).toInt() else 0
            Text(if (it.phase == "rtt") "Measuring round trip..." else "Sending $pct%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lastError?.let { Text(it, color = BadColor) }

        Text("Earlier runs", style = MaterialTheme.typography.titleMedium)
        if (runs.isEmpty()) Text("none yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        runs.drop(0).take(6).forEach {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(it.time.substringAfter('T').take(8), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.link + (it.local?.band?.let { b -> "  $b" } ?: ""), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${fmt1(it.mbpsReceiver)} Mbps", style = MonoStyle)
            }
        }

        fun run(mb: Int) {
            lastError = null
            scope.launch {
                val r = LinkRuntime.measure(st.nodeId, st.link, mb)
                lastError = r.error
            }
        }
        PrimaryBtn(if (measuring) "Measuring..." else "Measure again (100 MB)", enabled = !measuring) { run(100) }
        SecondaryBtn("Quick check (10 MB)", Modifier.fillMaxWidth(), enabled = !measuring) { run(10) }
    }
}
