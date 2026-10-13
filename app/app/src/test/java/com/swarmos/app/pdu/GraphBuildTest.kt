package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What storing a document's chunks adds to the graph: mentions per chunk, and links between entities that share a chunk. */
class GraphBuildTest {
    private val person = Mention(EntityType.PERSON, "priya sharma")
    private val amount = Mention(EntityType.AMOUNT, "INR:1200.00")
    private val date = Mention(EntityType.DATE, "2026-03-13")
    private val email = Mention(EntityType.EMAIL, "a@b.co")

    @Test fun everyEntityOfEveryChunkIsAMentionOfThatChunk() {
        val plan = GraphBuild.plan(listOf(10L to "Priya Sharma paid Rs 1,200 on 13 Mar 2026", 11L to "Priya Sharma wrote to a@b.co"))
        assertEquals(
            setOf(amount to 10L, date to 10L, person to 10L, email to 11L, person to 11L),
            plan.mentions.toSet(),
        )
    }

    @Test fun twoEntitiesInOneChunkAreLinkedOnceWithTheirTypeOrderedPair() {
        val plan = GraphBuild.plan(listOf(1L to "Priya Sharma paid Rs 1,200 on 13 Mar 2026"))
        assertEquals(mapOf(amount to date to 1, amount to person to 1, date to person to 1), plan.links)
    }

    @Test fun theCountIsTheNumberOfChunksTheTwoShare() {
        val plan = GraphBuild.plan(listOf(1L to "Priya Sharma wrote to a@b.co", 2L to "Priya Sharma wrote to a@b.co again", 3L to "Priya Sharma alone"))
        assertEquals(mapOf(email to person to 2), plan.links)
    }

    @Test fun anEntityOnItsOwnInAChunkMakesNoLink() {
        assertTrue(GraphBuild.plan(listOf(1L to "mail a@b.co today")).links.isEmpty())
    }

    @Test fun anEntityRepeatedInOneChunkIsOneMention() {
        val plan = GraphBuild.plan(listOf(1L to "a@b.co and again a@b.co"))
        assertEquals(listOf(email to 1L), plan.mentions)
    }

    @Test fun chunksWithNothingInThemAddNothing() {
        val plan = GraphBuild.plan(listOf(1L to "nothing to see", 2L to ""))
        assertTrue(plan.mentions.isEmpty() && plan.links.isEmpty())
    }

    @Test fun anEmptyDocumentAddsNothing() {
        val plan = GraphBuild.plan(emptyList())
        assertTrue(plan.mentions.isEmpty() && plan.links.isEmpty())
    }
}
