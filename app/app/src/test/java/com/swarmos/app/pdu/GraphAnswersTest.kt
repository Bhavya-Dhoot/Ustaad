package com.swarmos.app.pdu

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An in-memory copy of the graph tables, filled by the same [GraphBuild.plan] the store uses. The tests below hold what the SQL layer
 * cannot be held to on the JVM: who may see what ([GraphAnswers]), how answers merge, and what a question names.
 */
internal class FakeGraph : GraphRows {
    class Doc(val id: String, val title: String, var folder: String, var isPrivate: Boolean, val chunks: List<Pair<Long, String>>)
    class Link(val a: Long, val b: Long, val doc: String, val n: Int)

    val docs = LinkedHashMap<String, Doc>()
    private val entities = ArrayList<EntityRow>()
    private val mentions = ArrayList<Triple<Long, String, Long>>() // entity, doc, chunk
    private val links = ArrayList<Link>()
    private var nextChunk = 1L

    fun add(id: String, title: String, folder: String, isPrivate: Boolean, vararg texts: String): FakeGraph {
        val chunks = texts.map { nextChunk++ to it }
        docs[id] = Doc(id, title, folder, isPrivate, chunks)
        val plan = GraphBuild.plan(chunks)
        fun ent(m: Mention) = entities.firstOrNull { it.type == m.type && it.key == m.key }
            ?: EntityRow(entities.size + 1L, m.type, m.key, GraphExtract.display(m)).also { entities += it }
        plan.mentions.forEach { (m, c) -> mentions += Triple(ent(m).id, id, c) }
        plan.links.forEach { (p, n) -> links += Link(ent(p.first).id, ent(p.second).id, id, n) }
        return this
    }

    private fun row(docId: String, chunk: Long, count: Int) = docs.getValue(docId).let { DocRow(it.id, it.title, "text", 0, it.folder, it.isPrivate, chunk, count) }

    override fun entity(type: String, key: String) = entities.firstOrNull { it.type == type && it.key == key }
    override fun entitiesLike(tokens: List<String>, limit: Int) = entities.filter { e -> tokens.all { it in e.key.lowercase() } }.take(limit)
    override fun mentionDocs(entityId: Long) = mentions.filter { it.first == entityId }.groupBy { it.second }
        .map { (d, ms) -> row(d, ms.minOf { it.third }, ms.size) }
    override fun linkDocs(entityId: Long) = links.filter { it.a == entityId || it.b == entityId }.map { l ->
        val other = entities.first { it.id == (if (l.a == entityId) l.b else l.a) }
        LinkRow(other, row(l.doc, mentions.filter { it.first == entityId && it.second == l.doc }.minOf { it.third }, l.n))
    }
    override fun commonDocs(a: Long, b: Long) = mentions.filter { it.first == a }.map { it.second }.distinct()
        .filter { d -> mentions.any { it.first == b && it.second == d } }.map { d ->
            row(d, mentions.filter { it.first == a && it.second == d }.minOf { it.third }, links.filter { it.doc == d && setOf(it.a, it.b) == setOf(a, b) }.sumOf { it.n })
        }
    override fun chunkText(chunkId: Long) = docs.values.flatMap { it.chunks }.firstOrNull { it.first == chunkId }?.second
}

class GraphAnswersTest {
    private val bill = "Invoice INV-2026-001 from Reliance Industries Ltd for Rs 1,200 dated 13 Mar 2026. Contact Priya Sharma."
    private val secret = "PAN ABCDE1234F belongs to Rahul Verma, mail rahul.verma@secret.example, paid Reliance Industries Ltd Rs 9,50,000 on 31 Mar 2026."
    private val secretWords = listOf("verma", "abcde1234f", "secret.example", "950", "tax-return", "31 mar", "rahul")

    private fun graph() = FakeGraph()
        .add("bill", "bill.pdf", "Documents/Shared", false, bill)
        .add("tax", "tax-return.pdf", "Documents/Tax", true, secret)

    private fun q(op: String, text: String = "", a: EntityRef? = null, b: EntityRef? = null) = GraphQuery("q1", op, text, a, b)
    private fun peer(g: GraphRows, query: GraphQuery) = GraphAnswers.answer(query, g, includePrivate = false)
    private fun owner(g: GraphRows, query: GraphQuery) = GraphAnswers.answer(query, g, includePrivate = true)

    private val reliance = EntityRef(EntityType.ORG, "reliance industries ltd")
    private val amountSecret = EntityRef(EntityType.AMOUNT, "INR:950000.00")
    private val amountBill = EntityRef(EntityType.AMOUNT, "INR:1200.00")

    // ---- the privacy rule ---------------------------------------------------------------------------------------------

    @Test fun aPrivateDocumentsEntitiesDoNotAppearInAPeerAnswer() {
        val g = graph()
        for (text in listOf("Verma", "Rahul Verma", "ABCDE1234F", "rahul.verma@secret.example", "Rs 9,50,000", "31 March 2026")) {
            val r = peer(g, q("match", text))
            assertTrue("$text: ${r.entities}", r.entities.isEmpty())
            assertEquals("$text is counted as withheld", 1, r.withheldPrivate)
        }
    }

    @Test fun theOwnerSeesTheSameEntitiesAndTheyAreMarkedPrivate() {
        val r = owner(graph(), q("match", "Rahul Verma"))
        assertTrue("rahul verma" in r.entities.map { it.key })
        assertTrue(r.entities.all { e -> e.docs.all { it.priv } })
        assertEquals(0, r.withheldPrivate)
    }

    @Test fun nothingOfAPrivateDocumentIsInAnyPeerAnswerOfAnyKind() {
        val g = graph()
        val answers = listOf(
            peer(g, q("match", "reliance")), peer(g, q("match", "mar 2026")), peer(g, q("match", "inr")),
            peer(g, q("neighbours", a = reliance)), peer(g, q("connect", a = reliance, b = amountSecret)),
            peer(g, q("connect", a = reliance, b = amountBill)), peer(g, q("linked", "What did Reliance Industries Ltd bill, and Rahul Verma pay on 31 March 2026?")),
        )
        for (r in answers) {
            val json = SwarmJson.encodeToString(r).lowercase()
            for (w in secretWords) assertFalse("'$w' in ${r.op}: $json", w in json)
        }
    }

    @Test fun anEntityBothFilesMentionCountsAndListsOnlyThePublicFile() {
        val g = graph()
        val r = peer(g, q("match", "Reliance Industries Ltd"))
        val e = r.entities.single { it.key == "reliance industries ltd" }
        assertEquals(1, e.count)
        assertEquals(listOf("bill.pdf"), e.docs.map { it.title })
        assertEquals(1, r.withheldPrivate)
        assertEquals(2, owner(g, q("match", "Reliance Industries Ltd")).entities.single().count)
    }

    @Test fun aLinkThatOnlyAPrivateDocumentMakesIsNotInAPeerAnswer() {
        val g = graph()
        val r = peer(g, q("neighbours", a = reliance))
        val keys = r.entities.map { it.key }
        assertTrue("INR:1200.00" in keys)
        assertFalse("INR:950000.00" in keys)
        assertFalse(keys.any { it.contains("rahul") || it.contains("pan:") })
        assertEquals(1, r.withheldPrivate)
        assertTrue(owner(g, q("neighbours", a = reliance)).entities.any { it.key == "INR:950000.00" })
    }

    @Test fun theDocumentsThatConnectTwoEntitiesLeaveOutAPrivateOne() {
        val g = graph()
        assertTrue(peer(g, q("connect", a = reliance, b = amountSecret)).docs.isEmpty())
        assertEquals(listOf("tax-return.pdf"), owner(g, q("connect", a = reliance, b = amountSecret)).docs.map { it.title })
        assertEquals(listOf("bill.pdf"), peer(g, q("connect", a = reliance, b = amountBill)).docs.map { it.title })
    }

    @Test fun anEntityOnlyAPrivateDocumentMentionsGivesNoNeighboursAndNoDocuments() {
        val r = peer(graph(), q("neighbours", a = EntityRef(EntityType.PERSON, "rahul verma")))
        assertTrue(r.entities.isEmpty() && r.docs.isEmpty())
        assertEquals(1, r.withheldPrivate)
    }

    @Test fun switchingAFolderToPrivateTakesItOutOfTheNextAnswer() {
        val g = graph()
        assertEquals(listOf("bill.pdf"), peer(g, q("match", "Sharma")).entities.single().docs.map { it.title })
        g.docs.getValue("bill").isPrivate = true
        val r = peer(g, q("match", "Sharma"))
        assertTrue(r.entities.isEmpty())
        assertEquals(1, r.withheldPrivate)
    }

    @Test fun aFolderNamedPrivateIsNeverSharedWhateverItsSwitchSays() {
        val g = FakeGraph().add("d", "diary.txt", "Documents/Private/notes", false, "I met Priya Sharma on 5 May 2026.")
        val r = peer(g, q("match", "Sharma"))
        assertTrue(r.entities.isEmpty())
        assertEquals(1, r.withheldPrivate)
    }

    @Test fun aPrivateDocumentIsCountedOnceHoweverManyOfItsEntitiesMatch() {
        val g = FakeGraph().add("t", "t.pdf", "Tax", true, secret)
        assertEquals(1, peer(g, q("match", "Rahul Verma ABCDE1234F rahul.verma@secret.example")).withheldPrivate)
    }

    @Test fun theTextShownForASharedEntityDoesNotComeFromThePrivateFileThatSpelledItDifferently() {
        val g = FakeGraph()
            .add("p", "p.pdf", "Private", true, "paid Rs.1,200/- on 13/03/2026 to Priya Sharma")
            .add("s", "s.pdf", "Shared", false, "paid INR 1200 on 13 March 2026 to Priya Sharma")
        val r = peer(g, q("match", "1200"))
        assertEquals(listOf("₹1,200"), r.entities.map { it.display })
        assertFalse(SwarmJson.encodeToString(r).contains("Rs.1,200/-"))
    }

    @Test fun aSnippetIsAChunkOfASharedFileAtMostFourHundredCharacters() {
        val long = "Priya Sharma " + "word ".repeat(300)
        val g = FakeGraph().add("s", "s.txt", "Shared", false, long)
        val d = peer(g, q("match", "Sharma")).entities.single().docs.single()
        assertTrue(d.snippet.isNotEmpty() && d.snippet.length <= Snippets.MAX)
    }

    // ---- the questions ------------------------------------------------------------------------------------------------

    @Test fun aTextFindsAnEntityWhateverTheSpelling() {
        val g = graph()
        assertEquals(listOf("INR:1200.00"), peer(g, q("match", "Rs.1200/-")).entities.map { it.key }.filter { it.startsWith("INR") })
        assertEquals(listOf("2026-03-13"), peer(g, q("match", "13/03/2026")).entities.filter { it.type == EntityType.DATE }.map { it.key })
        assertEquals(listOf("priya sharma"), peer(g, q("match", "sharma")).entities.map { it.key })
    }

    @Test fun anEntityCarriesTheTitleAndHashedUriOfItsDocuments() {
        val d = peer(graph(), q("match", "Priya Sharma")).entities.single().docs.single()
        assertEquals("bill.pdf", d.title)
        assertEquals("bill", d.uriHash)
        assertTrue("Priya Sharma" in d.snippet)
    }

    @Test fun neighboursAreEntitiesThatShareAChunkWithTheCountOfSharedChunks() {
        val g = FakeGraph()
            .add("a", "a.txt", "S", false, "Priya Sharma wrote to a@b.co", "Priya Sharma wrote to a@b.co again")
            .add("b", "b.txt", "S", false, "Priya Sharma called +91 98765 43210")
        val r = peer(g, q("neighbours", a = EntityRef(EntityType.PERSON, "priya sharma")))
        val byKey = r.entities.associateBy { it.key }
        assertEquals(2, byKey.getValue("a@b.co").count)
        assertEquals(listOf("a.txt"), byKey.getValue("a@b.co").docs.map { it.title })
        assertEquals(1, byKey.getValue("+919876543210").count)
        assertEquals("a@b.co", r.entities.first().key) // most shared first
    }

    @Test fun twoEntitiesInDifferentChunksOfOneDocumentAreConnectedByThatDocumentWithNoSharedChunk() {
        val g = FakeGraph().add("a", "a.txt", "S", false, "Priya Sharma wrote", "mail a@b.co")
        val r = peer(g, q("connect", a = EntityRef(EntityType.PERSON, "priya sharma"), b = EntityRef(EntityType.EMAIL, "a@b.co")))
        assertEquals(listOf("a.txt"), r.docs.map { it.title })
        assertEquals(0, r.docs.single().count)
    }

    @Test fun anUnknownEntityGivesAnEmptyAnswer() {
        val r = peer(graph(), q("neighbours", a = EntityRef(EntityType.PERSON, "nobody here")))
        assertTrue(r.entities.isEmpty() && r.docs.isEmpty())
        assertEquals(0, r.withheldPrivate)
    }

    @Test fun theAnswerCarriesThePhonesIndexVersion() {
        assertEquals(7L, GraphAnswers.answer(q("match", "x"), graph(), false, versions = VersionCounters(local = 40, shared = 7)).indexVersion)
    }

    // ---- what a question names ----------------------------------------------------------------------------------------

    @Test fun aQuestionNamesTheEntitiesItSpellsAndGetsTheDocumentsHoldingMostOfThem() {
        val g = FakeGraph()
            .add("one", "one.txt", "S", false, "Priya Sharma paid Rs 500 on 1 Jan 2026")
            .add("two", "two.txt", "S", false, "Priya Sharma wrote a letter")
            .add("three", "three.txt", "S", false, "Rs 500 was the fee")
        val r = peer(g, q("linked", "How much did Priya Sharma pay on 1 January 2026? Was it Rs 500?"))
        assertEquals(listOf("one.txt", "three.txt", "two.txt"), r.docs.map { it.title }) // 3 named entities, then 1 and 1 by title
    }

    @Test fun aQuestionInLowerCaseNamesNothing() {
        assertTrue(peer(graph(), q("linked", "what did priya sharma bill")).docs.isEmpty())
    }

    @Test fun aLinkedDocumentThatIsPrivateIsLeftOutForAPeerAndKeptForTheOwner() {
        val ask = q("linked", "Did Rahul Verma pay Reliance Industries Ltd?")
        assertEquals(listOf("bill.pdf"), peer(graph(), ask).docs.map { it.title })
        assertEquals(setOf("bill.pdf", "tax-return.pdf"), owner(graph(), ask).docs.map { it.title }.toSet())
    }

    @Test fun retrievalIsOffUntilItIsMeasuredOnPhones() {
        assertFalse(GraphRetrieval.ENABLED)
    }

    // ---- the wire -------------------------------------------------------------------------------------------------------

    @Test fun onlyKnownQuestionsWithTheirParametersAreServed() {
        assertTrue(GraphWire.valid(q("match", "x")))
        assertTrue(GraphWire.valid(q("neighbours", a = reliance)))
        assertFalse(GraphWire.valid(q("neighbours")))
        assertFalse(GraphWire.valid(q("connect", a = reliance)))
        assertTrue(GraphWire.valid(q("connect", a = reliance, b = amountBill)))
        assertFalse(GraphWire.valid(q("drop table")))
        assertFalse(GraphWire.valid(q("match", "x".repeat(GraphWire.MAX_TEXT + 1))))
    }

    @Test fun aPeersGraphAnswerIsCutToTheSizesAPhoneMaySend() {
        val doc = GraphDocOut("T".repeat(900), "h".repeat(400), "k".repeat(90), 0, "s".repeat(5_000), 1)
        val big = GraphResponse("q", "match", entities = (1..60).map { GraphEntityOut("t", "k".repeat(500), "d".repeat(500), 1, List(30) { doc }) }, docs = List(60) { doc })
        val c = big.capped()
        assertEquals(GraphWire.MAX_ENTITIES, c.entities.size)
        assertTrue(c.entities.all { it.docs.size == GraphWire.MAX_DOCS && it.key.length == 120 && it.display.length == 120 })
        assertEquals(GraphWire.MAX_ENTITIES, c.docs.size)
        assertTrue(c.docs.all { it.snippet.length == Snippets.MAX && it.title.length == 200 && it.uriHash.length == 64 && it.kind.length == 16 })
    }

    @Test fun anHonestAnswerIsNotChangedByTheCap() {
        val r = peer(graph(), q("match", "Priya Sharma"))
        assertEquals(r, r.capped())
    }

    @Test fun theAnswerReadsBackFromJson() {
        val r = peer(graph(), q("match", "Priya Sharma"))
        assertEquals(r, SwarmJson.decodeFromString(GraphResponse.serializer(), SwarmJson.encodeToString(r)))
    }

    // ---- merging the phones' answers -----------------------------------------------------------------------------------

    @Test fun theSameThingSpelledDifferentlyOnTwoPhonesIsOneEntity() {
        val a = FakeGraph().add("a1", "receipt.jpg", "Pictures", false, "Paid Rs 1,200 to Priya Sharma")
        val b = FakeGraph().add("b1", "invoice.pdf", "Documents", false, "Total INR 1200 for Priya Sharma")
        val merged = GraphMerge.merge(listOf(
            DeviceGraph("A", "Phone A", true, peer(a, q("match", "Rs 1200"))),
            DeviceGraph("B", "Phone B", false, peer(b, q("match", "INR 1200"))),
        ))
        val e = merged.entities.single()
        assertEquals("INR:1200.00", e.key)
        assertEquals(2, e.count)
        assertEquals(listOf("Phone A", "Phone B"), e.devices)
        assertEquals(setOf("receipt.jpg", "invoice.pdf"), e.docs.map { it.doc.title }.toSet())
        assertEquals(listOf(true, false), e.docs.map { it.local })
    }

    @Test fun anEntityOnMoreDevicesComesFirstAndTheSameDocumentIsListedOnce() {
        val a = FakeGraph().add("a1", "a.txt", "S", false, "Priya Sharma and Rahul Verma met", "Priya Sharma again")
        val b = FakeGraph().add("b1", "b.txt", "S", false, "Priya Sharma wrote")
        val ask = q("match", "Priya Sharma, Rahul Verma")
        val ans = listOf(
            DeviceGraph("A", "Phone A", true, peer(a, ask)),
            DeviceGraph("B", "Phone B", false, peer(b, ask)),
            DeviceGraph("A", "Phone A", true, peer(a, ask)), // the same answer twice
        )
        val merged = GraphMerge.merge(ans)
        assertEquals(listOf("priya sharma", "rahul verma"), merged.entities.map { it.key })
        assertEquals(2, merged.entities.first().docs.size) // A's document once, B's once
        assertEquals(1, merged.entities.last().docs.size)
    }

    @Test fun withheldCountsOfAllPhonesAreAdded() {
        val a = FakeGraph().add("a", "a", "P", true, "Priya Sharma")
        val b = FakeGraph().add("b", "b", "P", true, "Priya Sharma")
        val merged = GraphMerge.merge(listOf(
            DeviceGraph("A", "A", true, peer(a, q("match", "Sharma"))), DeviceGraph("B", "B", false, peer(b, q("match", "Sharma"))),
        ))
        assertTrue(merged.entities.isEmpty())
        assertEquals(2, merged.withheldPrivate)
    }

    @Test fun connectingDocumentsFromTwoPhonesAreMergedByDevice() {
        val a = FakeGraph().add("a", "a.txt", "S", false, "Priya Sharma wrote to a@b.co")
        val b = FakeGraph().add("a", "b.txt", "S", false, "Priya Sharma wrote to a@b.co")
        val ask = q("connect", a = EntityRef(EntityType.PERSON, "priya sharma"), b = EntityRef(EntityType.EMAIL, "a@b.co"))
        val merged = GraphMerge.merge(listOf(DeviceGraph("A", "A", true, peer(a, ask)), DeviceGraph("B", "B", false, peer(b, ask))))
        assertEquals(2, merged.docs.size) // same hashed uri "a" on two phones is two documents
        assertEquals(setOf("A", "B"), merged.docs.map { it.deviceId }.toSet())
    }

    @Test fun nothingAnsweredIsNothingMerged() {
        val m = GraphMerge.merge(emptyList())
        assertTrue(m.entities.isEmpty() && m.docs.isEmpty())
        assertEquals(0, m.withheldPrivate)
    }
}
