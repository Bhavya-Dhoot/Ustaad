package com.swarmos.app.kb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.pdu.PageHeader
import com.swarmos.app.ui.CitedText
import com.swarmos.app.pdu.PromptBuilder
import com.swarmos.app.ui.EmptyHint
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SelectChip
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAVED as an outline stamp, REVOKED hatched: a revoked card is never used. */
@Composable
internal fun KbStamp(e: KbEntry) {
    if (e.usable) Stamp("Saved", small = true) else Stamp("Revoked", caution = true, small = true)
}

private fun tierText(e: KbEntry) = if (e.tier == KbTier.SWARM) "shared with the swarm" else "kept on this phone"

/** The Knowledge bank page, opened from the Data library: the cards, and one card in detail. */
@Composable
fun KbPage(onBack: () -> Unit) {
    val entries by KbEngine.entries.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { KbEngine.start(); withContext(Dispatchers.IO) { KbEngine.refresh() } }
    val open = entries.firstOrNull { it.card.id == openId }
    BackHandler { if (open != null) openId = null else onBack() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("Knowledge bank", if (open != null) ({ openId = null }) else onBack)
        if (open != null) {
            CardDetail(open)
        } else {
            Text(
                "Answers you saved. A card kept on this phone stays here; a shared one is copied to the other phones, so it can answer when the phone with the files is away.",
                style = SwarmType.body.copy(color = SwarmColors.graphite),
            )
            if (entries.isEmpty()) {
                EmptyHint("Ask a question in Data, then tap Save to knowledge bank under a cited answer.")
            }
            for (e in entries) CardRow(e) { openId = e.card.id }
        }
    }
}

@Composable
private fun CardRow(e: KbEntry, onOpen: () -> Unit) {
    SwarmCard(owner = e.card.authorName, modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken(e.card.authorName, 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.card.question, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 21.sp), maxLines = 2)
                Text("${KbText.dayTime(e.card.createdMs)} · ${tierText(e)}", style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
            KbStamp(e)
        }
    }
}

@Composable
private fun CardDetail(e: KbEntry) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val self = KbEngine.selfId
    val mine = e.card.authorId == self
    val why = KbPolicy.whyNotShareable(e, self)

    SwarmCard(owner = e.card.authorName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken(e.card.authorName, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(e.card.authorName, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
            KbStamp(e)
        }
        Spacer(Modifier.height(8.dp))
        Text(e.card.question, style = SwarmType.title)
        Spacer(Modifier.height(6.dp))
        Text(e.card.answer, style = SwarmType.body)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(tierText(e))
            if (!e.usable) Tag("not used in answers")
        }
    }

    SectionTitle("Sources")
    for (s in e.card.sources) {
        SwarmCard(owner = s.phone) {
            Text(s.title.ifBlank { "(untitled)" }, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 2)
            Text("${s.phone} · ${PromptBuilder.day(s.dateMs)}", style = SwarmType.mono.copy(color = SwarmColors.graphite))
            if (s.priv) { Spacer(Modifier.height(4.dp)); Tag("private folder, this phone only") }
        }
    }

    SectionTitle("Provenance")
    SwarmCard {
        Text(
            (if (mine) "Saved on this phone" else "Received from the swarm, saved by ${e.card.authorName}") + " on ${KbText.dayTime(e.card.createdMs)}",
            style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        )
        Spacer(Modifier.height(4.dp))
        Text("audit entry ${e.card.auditHash.take(16)}", style = SwarmType.mono.copy(color = SwarmColors.graphite))
        Text("card ${e.card.id.take(16)}", style = SwarmType.mono.copy(color = SwarmColors.graphite))
        Text(
            "The seal proves a phone with the pairing secret made this card and nobody changed it. It does not say which phone, so the author name is a claim.",
            style = SwarmType.body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SwarmColors.graphite),
        )
    }

    if (!mine) {
        Text("Only the phone that saved this card can share or revoke it.", style = SwarmType.body.copy(color = SwarmColors.graphite))
    } else if (e.usable) {
        val shared = e.tier == KbTier.SWARM
        if (shared) {
            SecondaryButton("Stop sharing", { scope.launch { message = KbEngine.setShared(e.card.id, false) ?: "Not offered to other phones any more. Copies they already hold stay until you revoke." } }, Modifier.fillMaxWidth())
        } else {
            SecondaryButton("Share with the swarm", { scope.launch { message = KbEngine.setShared(e.card.id, true) ?: "Shared. Phones that come online will copy it." } }, Modifier.fillMaxWidth(), enabled = why == null)
            if (why != null) Text(why, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SwarmColors.graphite))
        }
        Hairline()
        if (!confirming) {
            SecondaryButton("Revoke", { confirming = true }, Modifier.fillMaxWidth())
        } else {
            Text("Revoke this card? It is marked REVOKED here and on every phone that syncs, and is no longer used in answers. This cannot be undone.", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Yes, revoke", { scope.launch { message = KbEngine.revoke(e.card.id) ?: "Revoked."; confirming = false } })
                SecondaryButton("Keep it", { confirming = false })
            }
        }
    }
    message?.let { Text(it, style = SwarmType.label.copy(color = SwarmColors.graphite)) }
}

/**
 * Under a cited answer: the card the owner would save, then the choice. "Keep on this phone" is the default; a card with a source in
 * a private folder cannot be shared and says why.
 */
@Composable
fun SaveCardPanel(draft: KbDraft.Draft, onSaved: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var share by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val privateSource = draft.sources.any { it.priv }
    SwarmCard {
        SectionTitle("Save to knowledge bank")
        Spacer(Modifier.height(6.dp))
        Text(draft.question, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 21.sp))
        Spacer(Modifier.height(4.dp))
        Text(draft.answer, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
        if (draft.clipped) Text("Shortened to ${KbLimits.MAX_ANSWER} characters.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        Spacer(Modifier.height(6.dp))
        for (s in draft.sources) Text(KbText.sourceLine(s), style = SwarmType.mono.copy(color = SwarmColors.graphite), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Keep on this phone", !share, { share = false })
            SelectChip("Share with the swarm", share, { if (privateSource) message = "A source is in a private folder, so this card cannot be shared" else share = true })
        }
        message?.let { Text(it, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp)) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Save", {
                scope.launch {
                    when (val r = KbEngine.save(draft, share)) {
                        is KbSave.Saved -> onSaved(if (share) "Saved and shared with the swarm" else "Saved on this phone")
                        is KbSave.Refused -> message = r.reason
                    }
                }
            })
            SecondaryButton("Cancel", onCancel)
        }
    }
}

/** A saved answer that matched, shown above the file sources: the author's token, the save date, the sources and the SAVED stamp. */
@Composable
fun KbHitCard(h: KbHit, onCite: (Int) -> Unit) {
    val c = h.entry.card
    SwarmCard(owner = c.authorName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken(c.authorName, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(c.authorName, style = SwarmType.title.copy(fontSize = 15.sp, lineHeight = 20.sp), maxLines = 1, modifier = Modifier.weight(1f))
            Text(PromptBuilder.day(c.createdMs), style = SwarmType.mono.copy(color = SwarmColors.graphite))
            Spacer(Modifier.width(4.dp))
            KbStamp(h.entry)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.question, style = SwarmType.title.copy(fontSize = 15.sp, lineHeight = 20.sp), maxLines = 2)
        // the same marker chips as a live answer; a marker with no matching source stays plain text
        CitedText(c.answer, streaming = false, onCite = onCite, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), cites = 1..c.sources.size)
        Spacer(Modifier.height(4.dp))
        for (s in c.sources) Text(KbText.sourceLine(s), style = SwarmType.mono.copy(color = SwarmColors.graphite), maxLines = 1)
    }
}

/** The answer view when a saved answer clears the floor: the card's answer, marked as saved, with its age, and the way to run the live search. */
@Composable
fun SavedAnswerBlock(h: KbHit, onCite: (Int) -> Unit, onAskAgain: () -> Unit) {
    val c = h.entry.card
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Answer")
        Spacer(Modifier.width(10.dp))
        Tag("saved, ${KbSearch.age(c.createdMs, System.currentTimeMillis())} old")
    }
    SwarmCard {
        CitedText(c.answer, streaming = false, onCite = onCite, cites = 1..c.sources.size)
        Spacer(Modifier.height(6.dp))
        // the [n] in the text are the numbered sources listed below this block
        Text("Saved answer from ${c.authorName}, ${KbText.dayTime(c.createdMs)}", style = SwarmType.label)
        Text("No phone was searched for this; the files may have changed since.", style = SwarmType.label.copy(color = SwarmColors.graphite))
    }
    SecondaryButton("Ask again", onAskAgain, Modifier.fillMaxWidth())
}
