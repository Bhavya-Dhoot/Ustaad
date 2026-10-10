package com.swarmos.app.agent

import com.swarmos.app.agent.FileFakes.candidate
import com.swarmos.app.agent.FileFakes.offline
import com.swarmos.app.agent.FileFakes.peer
import com.swarmos.app.agent.FileFakes.peerHit
import com.swarmos.app.pdu.MergedHit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/** The file search: the request, the share rule it goes through, the merge, and the shape of what another app gets. */
class AgentFilesTest {
    private val bill = candidate(1, "hash-bill", "Electricity bill", "Electricity bill for September 1,240 rupees", folder = "Pictures/Shared")
    private val salary = candidate(2, "hash-salary", "Salary slip", "Salary slip net pay 85,000", folder = "Documents/Salary", isPrivate = true, kind = "document")
    private val tax = candidate(3, "hash-tax", "Tax return", "Tax return PAN ABCDE1234F", folder = "Documents/Private", isPrivate = false, kind = "document")

    private fun call(query: String = "bill", scope: FileScope = FileScope.PHONE, max: Int = 5) = FileSearchCall(query, scope, max)

    private fun search(source: FileSource, c: FileSearchCall = call()) = runBlocking { AgentFiles.search(c, source, "q1", ZoneId.of("UTC")) }

    private fun refused(status: Int, mention: String, block: () -> Unit) {
        try {
            block()
            fail("expected a $status refusal mentioning $mention")
        } catch (r: AgentRefusal) {
            assertEquals(r.message, status, r.status)
            assertTrue("'${r.message}' should name $mention", r.message!!.contains(mention))
        }
    }

    // ---- the request -----------------------------------------------------------------------------------------

    @Test fun aSearchHasDefaults() {
        val c = AgentRequests.parseFileSearch("""{"query":"electricity bill"}""")
        assertEquals("electricity bill", c.query)
        assertEquals(FileScope.PHONE, c.scope)
        assertEquals(5, c.maxResults)
    }

    @Test fun scopeAndMaxResultsAreRead() {
        val c = AgentRequests.parseFileSearch("""{"query":"x","scope":"swarm","max_results":10,"extra":1}""")
        assertEquals(FileScope.SWARM, c.scope)
        assertEquals(10, c.maxResults)
        assertEquals(FileScope.PHONE, AgentRequests.parseFileSearch("""{"query":"x","scope":null,"max_results":null}""").scope)
        assertEquals(1, AgentRequests.parseFileSearch("""{"query":"x","max_results":1}""").maxResults)
    }

    @Test fun badSearchesAreRefusedNamingTheField() {
        refused(400, "query") { AgentRequests.parseFileSearch("""{}""") }
        refused(400, "query") { AgentRequests.parseFileSearch("""{"query":5}""") }
        refused(400, "query") { AgentRequests.parseFileSearch("""{"query":""}""") }
        refused(400, "query") { AgentRequests.parseFileSearch("""{"query":"   "}""") }
        refused(400, "scope") { AgentRequests.parseFileSearch("""{"query":"x","scope":"everyone"}""") }
        refused(400, "scope") { AgentRequests.parseFileSearch("""{"query":"x","scope":1}""") }
        refused(400, "max_results") { AgentRequests.parseFileSearch("""{"query":"x","max_results":0}""") }
        refused(400, "max_results") { AgentRequests.parseFileSearch("""{"query":"x","max_results":11}""") }
        refused(400, "max_results") { AgentRequests.parseFileSearch("""{"query":"x","max_results":"3"}""") }
        refused(400, "JSON") { AgentRequests.parseFileSearch("nope") }
    }

    @Test fun theQueryLengthLimitIsExact() {
        assertEquals(1000, AgentRequests.parseFileSearch("""{"query":"${"q".repeat(1000)}"}""").query.length)
        refused(400, "query") { AgentRequests.parseFileSearch("""{"query":"${"q".repeat(1001)}"}""") }
    }

    // ---- this phone: the peer rule, and nothing else ----------------------------------------------------------

    @Test fun onlyWhatAPairedPhoneWouldGetComesBack() {
        val found = search(FileFakes.source(listOf(salary, bill, tax)))
        assertEquals(1, found.results)
        val raw = found.body.toString()
        assertTrue(raw.contains("Electricity bill"))
        for (secret in listOf("Salary", "85,000", "Tax return", "ABCDE", "hash-", "uriHash", "Documents", "Pictures", "Private", "withheld")) {
            assertFalse("'$secret' must not appear in $raw", raw.contains(secret))
        }
    }

    @Test fun aPrivateFolderIsNotSharedWhateverItsStoredChoice() {
        // tax: stored as shared, but the folder is named Private; salary: stored as private
        val found = search(FileFakes.source(listOf(tax, salary)))
        assertEquals(0, found.results)
        assertEquals(0, found.body.getValue("data").jsonArray.size)
    }

    @Test fun theSnippetIsTheShortWindowTheDataUnionPicks() {
        val long = candidate(4, "h", "Long", "word ".repeat(500) + "needle " + "word ".repeat(500))
        val hit = search(FileFakes.source(listOf(long)), call("needle")).body.getValue("data").jsonArray.single().jsonObject
        val snippet = hit.getValue("snippet").jsonPrimitive.content
        assertTrue(snippet.length <= 400)
        assertTrue(snippet.contains("needle"))
    }

    @Test fun maxResultsCutsTheList() {
        val many = (1L..8L).map { candidate(it, "h$it", "File $it", "text $it", folder = "Pictures/Shared$it") }
        assertEquals(3, search(FileFakes.source(many), call(max = 3)).results)
        assertEquals(8, search(FileFakes.source(many), call(max = 10)).results)
    }

    @Test fun theAnswerHasOnlyThePublicFields() {
        val found = search(FileFakes.source(listOf(bill), name = "Phone A"))
        assertEquals(setOf("object", "data", "scope", "incomplete", "phones"), found.body.keys)
        assertEquals("list", found.body.getValue("object").jsonPrimitive.content)
        assertEquals("phone", found.body.getValue("scope").jsonPrimitive.content)
        assertEquals("false", found.body.getValue("incomplete").jsonPrimitive.content)
        val hit = found.body.getValue("data").jsonArray.single().jsonObject
        assertEquals(setOf("phone", "title", "date", "kind", "snippet"), hit.keys)
        assertEquals("Phone A", hit.getValue("phone").jsonPrimitive.content)
        assertEquals("Electricity bill", hit.getValue("title").jsonPrimitive.content)
        assertEquals("2026-09-03", hit.getValue("date").jsonPrimitive.content)
        assertEquals("photo", hit.getValue("kind").jsonPrimitive.content)
        assertFalse("no score: a phone ranks over its private chunks too", found.body.toString().contains("score"))
        val phones = found.body.getValue("phones").jsonArray.map { it.jsonObject }
        assertEquals(listOf(setOf("name", "status")), phones.map { it.keys })
        assertEquals("ok", phones.single().getValue("status").jsonPrimitive.content)
    }

    @Test fun aScopePhoneSearchNeverAsksThePeers() {
        val calls = FileFakes.Calls()
        val found = search(FileFakes.source(listOf(bill), calls, peers = listOf(peer("Phone B", peerHit("Certificate", "course certificate")))))
        assertEquals(0, calls.peers.get())
        assertEquals(1, calls.local.get())
        assertEquals(listOf("Phone A"), found.body.getValue("phones").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    // ---- the swarm ----------------------------------------------------------------------------------------------

    @Test fun aSwarmSearchMergesWhatPairedPhonesAnswer() {
        val calls = FileFakes.Calls()
        val b = peer("Phone B", peerHit("Course certificate", "Certificate of completion, Kubernetes", hash = "b-hash", kind = "scan"), withheld = 7)
        val found = search(FileFakes.source(listOf(bill), calls, peers = listOf(b)), call("bill", FileScope.SWARM))
        assertEquals(1, calls.peers.get())
        val q = calls.queries.single()
        assertEquals("bill", q.text)
        assertEquals(5, q.k)
        assertEquals("q1", q.qid)
        val data = found.body.getValue("data").jsonArray.map { it.jsonObject }
        assertEquals(setOf("Phone A", "Phone B"), data.map { it.getValue("phone").jsonPrimitive.content }.toSet())
        assertEquals("swarm", found.body.getValue("scope").jsonPrimitive.content)
        val raw = found.body.toString()
        assertFalse(raw.contains("b-hash") || raw.contains("withheld") || raw.contains("uriHash") || raw.contains("indexedChunks"))
        assertEquals(2, found.results)
        assertEquals(listOf(1, 1), found.phones.map { it.results })
    }

    @Test fun anOfflinePairedPhoneIsReportedAndTheAnswerIsIncomplete() {
        val found = search(
            FileFakes.source(listOf(bill), peers = listOf(peer("Phone B", peerHit("Certificate", "certificate text")), offline("Phone C"))),
            call("bill", FileScope.SWARM),
        )
        assertEquals("true", found.body.getValue("incomplete").jsonPrimitive.content)
        val phones = found.body.getValue("phones").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("status").jsonPrimitive.content }
        assertEquals(listOf("Phone A" to "ok", "Phone B" to "ok", "Phone C" to "offline"), phones)
        assertEquals(0, found.phones.last().results)
    }

    @Test fun aPhoneThatFailedIsReportedAsAnErrorAndAddsNothing() {
        val found = search(FileFakes.source(listOf(bill), peers = listOf(PeerFiles("Phone B", AgentFiles.ERROR, null))), call("bill", FileScope.SWARM))
        assertEquals(1, found.results)
        assertEquals("error", found.body.getValue("phones").jsonArray[1].jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("true", found.body.getValue("incomplete").jsonPrimitive.content)
    }

    @Test fun aSwarmSearchWithNothingOnThisPhoneStillReturnsThePeersFiles() {
        val found = search(FileFakes.source(listOf(salary), peers = listOf(peer("Phone B", peerHit("Bill photo", "electricity bill photo")))), call("bill", FileScope.SWARM))
        assertEquals(listOf("Phone B"), found.body.getValue("data").jsonArray.map { it.jsonObject.getValue("phone").jsonPrimitive.content })
    }

    @Test fun swarmResultsAreCutToMaxResults() {
        val hits = (1..6).map { peerHit("File $it", "text number $it is different", rank = it) }
        val found = search(FileFakes.source(emptyList(), peers = listOf(peer("Phone B", *hits.toTypedArray()))), call("x", FileScope.SWARM, max = 2))
        assertEquals(2, found.results)
    }

    @Test fun textFromAPairedPhoneIsCutToSize() {
        val hostile = peerHit("T".repeat(5000), "s".repeat(5000), kind = "k".repeat(500))
        val hit = search(FileFakes.source(emptyList(), peers = listOf(peer("Phone B", hostile))), call("x", FileScope.SWARM)).body
            .getValue("data").jsonArray.single().jsonObject
        assertEquals(200, hit.getValue("title").jsonPrimitive.content.length)
        assertEquals(400, hit.getValue("snippet").jsonPrimitive.content.length)
        assertEquals(32, hit.getValue("kind").jsonPrimitive.content.length)
    }

    @Test fun phoneNamesAreCutToSixtyFourCharacters() {
        val long = "N".repeat(300)
        val found = search(
            FileFakes.source(listOf(bill), name = long, peers = listOf(peer("P".repeat(300), peerHit("Certificate", "certificate of completion")), offline("O".repeat(300)))),
            call("bill", FileScope.SWARM),
        )
        val phonesInData = found.body.getValue("data").jsonArray.map { it.jsonObject.getValue("phone").jsonPrimitive.content }
        val phonesListed = found.body.getValue("phones").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(setOf("N".repeat(64), "P".repeat(64)), phonesInData.toSet())
        assertEquals(listOf("N".repeat(64), "P".repeat(64), "O".repeat(64)), phonesListed)
        assertTrue(found.phones.all { it.name.length == 64 })
    }

    @Test fun theAuditCountsThePhonesAskedAndTheBytesSent() {
        val asked = peer("Phone B", peerHit("Certificate", "certificate text"))
        val notCalled = PeerFiles("Phone C", AgentFiles.OFFLINE, null, asked = false)
        val calls = FileFakes.Calls()
        val swarm = search(FileFakes.source(listOf(bill), calls, peers = listOf(asked, offline("Phone D"), notCalled)), call("electricity bill", FileScope.SWARM))
        assertEquals(2, swarm.asked)
        val oneQuery = com.swarmos.app.core.SwarmJson.encodeToString(calls.queries.single()).toByteArray().size
        assertEquals(2 * oneQuery, swarm.sentBytes)
        assertTrue(oneQuery > "electricity bill".length)
        // a search on this phone alone sends nothing anywhere
        val local = search(FileFakes.source(listOf(bill), peers = listOf(asked)), call("electricity bill", FileScope.PHONE))
        assertEquals(0, local.asked)
        assertEquals(0, local.sentBytes)
    }

    @Test fun anEmptyIndexIsNotAReasonToRefuse() {
        // only a missing embedder or an index to rebuild stop a search; the number of files indexed is not asked about at all
        assertEquals(null, FileSource.whyUnavailable(dense = true, embedderReady = true))
        assertEquals(FileSource.REINDEX, FileSource.whyUnavailable(dense = false, embedderReady = true))
        assertEquals(FileSource.NO_EMBEDDER, FileSource.whyUnavailable(dense = false, embedderReady = false))
        val found = search(FileFakes.source(emptyList(), chunks = 0))
        assertEquals(0, found.results)
        assertEquals("ok", found.body.getValue("phones").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
    }

    // ---- small pieces -------------------------------------------------------------------------------------------

    @Test fun datesAreDaysInTheGivenZoneOrNull() {
        assertEquals("2026-09-03", AgentFiles.dateOf(FileFakes.SEPT_3, ZoneId.of("UTC")))
        assertEquals("2026-09-04", AgentFiles.dateOf(FileFakes.SEPT_3, ZoneId.of("Pacific/Kiritimati")))
        assertEquals(null, AgentFiles.dateOf(0, ZoneId.of("UTC")))
        assertEquals(null, AgentFiles.dateOf(-5, ZoneId.of("UTC")))
        val m = MergedHit("0", "Phone A", true, peerHit("Undated", "text", dateMs = 0), 0.1)
        assertEquals(JsonNull, AgentFiles.publicHit(m, ZoneId.of("UTC")).getValue("date"))
    }

    @Test fun peerStatusesAreMappedToThePublicOnes() {
        assertEquals("ok", AgentFiles.publicStatus("ok"))
        assertEquals("offline", AgentFiles.publicStatus("unreachable"))
        assertEquals("offline", AgentFiles.publicStatus("offline"))
        assertEquals("error", AgentFiles.publicStatus("error"))
        assertEquals("error", AgentFiles.publicStatus("waiting"))
    }

    @Test fun aHitNamesNoDeviceIdOrRank() {
        val m = MergedHit("7", "Phone Z", false, peerHit("T", "S", rank = 3), 0.5)
        val h: JsonObject = AgentFiles.publicHit(m, ZoneId.of("UTC"))
        assertEquals(setOf("phone", "title", "date", "kind", "snippet"), h.keys)
    }
}
