package com.swarmos.app.pdu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.AuditVerify
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.Lane
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What left this phone, what each phone returned, and what other phones took from this one. */
@Composable
internal fun AuditPage(onBack: () -> Unit) {
    val q by PduEngine.query.collectAsStateWithLifecycle()
    val served by PduEngine.served.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var verify by remember { mutableStateOf<AuditVerify?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scroll = rememberScrollState()
    // the CHAIN OK / CHAIN BROKEN stamp is the last thing on the page: bring it into view once it exists
    LaunchedEffect(verify) {
        if (verify != null) {
            withFrameNanos { } // let the page lay out with the stamp before measuring how far there is to scroll
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("Query audit", onBack)
        val cur = q
        if (cur == null) Note("no question asked yet on this phone", rotation = -1.5f)
        else {
            SectionTitle("Sent from this phone")
            SwarmCard {
                Text("\"${cur.text}\"", style = SwarmType.body)
                val peers = cur.devices.filter { !it.local && it.status != "offline" }
                Spacer(Modifier.height(8.dp))
                Tag("${peers.firstOrNull()?.sentBytes ?: 0} bytes to ${AuditText.phones(peers.size)}, text only")
            }

            SectionTitle("Returned by each phone")
            for (d in cur.devices.distinctBy { it.deviceId }.sortedBy { it.name }) {
                Lane(
                    name = d.name,
                    role = if (d.local) "this phone" else "peer",
                    trailing = {
                        when {
                            d.status == "ok" && d.withheldPrivate > 0 -> Stamp("Withheld", caution = true, small = true)
                            d.status != "ok" && d.status != "waiting" -> Stamp(if (d.status == "error") "Error" else "No reply", caution = true, small = true)
                        }
                    },
                ) {
                    when (d.status) {
                        "ok" -> {
                            MetricRow("matches", "${d.hits}")
                            if (!d.local) MetricRow("bytes returned", "${d.bytes}")
                            MetricRow(if (d.local) "time" else "round trip", "${if (d.local) d.tookMs else d.rttMs}", "ms")
                            if (d.titles.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Hairline()
                                Spacer(Modifier.height(6.dp))
                                for (t in AuditText.titles(d.titles)) Text(t, style = SwarmType.mono, maxLines = 1)
                            }
                            if (d.withheldPrivate > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text("${d.withheldPrivate} match withheld (private folder)", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                            }
                        }
                        "waiting" -> Text("waiting", style = SwarmType.mono.copy(color = SwarmColors.graphite))
                        else -> {
                            Text(if (d.status == "error") "answered with an error" else "did not answer", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                            d.detail?.let { Text(it, style = SwarmType.mono.copy(fontSize = 12.sp, color = SwarmColors.graphite), maxLines = 2) }
                        }
                    }
                }
            }
            cur.auditHash?.let { MetricRow("audit log entry", it.take(8) + "..." + it.takeLast(4)) }
        }

        SectionTitle("Asked of this phone")
        if (served.isEmpty()) Text("No phone has asked this one yet.", style = SwarmType.body.copy(color = SwarmColors.graphite))
        for (s in served) {
            SwarmCard(owner = s.peer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhoneToken(s.peer, 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(s.peer, style = SwarmType.title.copy(fontSize = 15.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f), maxLines = 1)
                    Text(fmt.format(Date(s.atMs)), style = SwarmType.mono.copy(color = SwarmColors.graphite))
                }
                Spacer(Modifier.height(6.dp))
                MetricRow("snippets sent", "${s.n}")
                MetricRow("bytes sent", "${s.bytes}")
                if (s.withheldPrivate > 0) MetricRow("private matches withheld", "${s.withheldPrivate}")
                for (t in AuditText.titles(s.titles)) Text(t, style = SwarmType.mono.copy(color = SwarmColors.graphite), maxLines = 1)
            }
        }

        PrimaryButton(
            "Verify local audit ledger",
            { scope.launch { verify = withContext(Dispatchers.IO) { AppGraph.audit.verify() } } },
            Modifier.fillMaxWidth(),
        )
        verify?.let { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.ok) Stamp("Chain ok") else Stamp("Chain broken", caution = true)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (v.ok) "${v.entries} entries, every hash matches" else "line ${v.badLine}: ${v.reason}",
                    style = SwarmType.mono.copy(color = SwarmColors.graphite),
                )
            }
        }
    }
}

/** Wording and lists of the audit page. */
internal object AuditText {
    fun phones(n: Int) = if (n == 1) "1 phone" else "$n phones"

    /** A file shown once per phone, even when the phone answered with the same title twice. */
    fun titles(titles: List<String>): List<String> = titles.distinct()
}
