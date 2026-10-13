package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsTest {
    private val cards = mapOf(
        "food_delivery" to "category: food_delivery\napps: in.swiggy.android, com.application.zomato\nlabels: swiggy, zomato\n---\nGeneral food hints.",
        "swiggy" to "category: food_delivery\napps: in.swiggy.android\nlabels: swiggy\n---\nSwiggy only hints.",
        "maps" to "category: maps_navigation\napps: com.google.android.apps.maps\nlabels: maps\n---\nMaps hints.",
        "headerless" to "just text, no header and no separator",
        "crlf" to "category: messaging\r\napps: com.whatsapp\r\nlabels: whatsapp\r\n---\r\nMessage hints.",
    )
    private val book = MapSkillBook(cards)

    @Test fun categoriesAreDistinctAndSorted() {
        assertEquals(listOf("food_delivery", "maps_navigation", "messaging"), book.categories())
    }

    @Test fun forAppMatchesByPackageAndPrefersTheMostSpecificCard() {
        assertEquals("Swiggy only hints.", book.forApp("in.swiggy.android"))
        assertEquals("General food hints.", book.forApp("com.application.zomato"))
        assertEquals("Maps hints.", book.forApp("com.google.android.apps.maps"))
        assertNull(book.forApp("com.unknown"))
    }

    @Test fun forCategoryPrefersTheCardThatListsMostApps() {
        assertEquals("General food hints.", book.forCategory("food_delivery"))
        assertEquals("Maps hints.", book.forCategory("MAPS_NAVIGATION"))
        assertNull(book.forCategory("nothing"))
    }

    @Test fun categoryOfUsesThePackageFirstThenALabelThatContainsAListedLabel() {
        assertEquals("food_delivery", book.categoryOf(AppInfo("in.swiggy.android", "Anything")))
        assertEquals("food_delivery", book.categoryOf(AppInfo("com.other.pkg", "Zomato: Food Delivery & Dining")))
        assertEquals("messaging", book.categoryOf(AppInfo("com.x", "WhatsApp Business")))
        assertNull(book.categoryOf(AppInfo("com.x", "Calculator")))
        assertNull(book.categoryOf(AppInfo("com.x", "")))
    }

    @Test fun crlfCardsParse() {
        assertEquals("Message hints.", book.forApp("com.whatsapp"))
    }

    @Test fun aCardWithoutHeaderHasNoCategoryAndNoApps() {
        assertEquals(3, book.categories().size)
        assertNull(book.forApp("headerless"))
    }

    @Test fun theBodyIsCutAtSixHundredCharacters() {
        val long = MapSkillBook(mapOf("c" to "category: x\napps: a.b\n---\n" + "y".repeat(2000)))
        assertEquals(600, long.forApp("a.b")!!.length)
        assertEquals(MapSkillBook.BODY_MAX, 600)
    }

    @Test fun anEmptyBookAnswersNothing() {
        val e = MapSkillBook(emptyMap())
        assertTrue(e.categories().isEmpty())
        assertNull(e.forApp("a"))
        assertNull(e.forCategory("a"))
        assertNull(e.categoryOf(AppInfo("a", "b")))
    }
}
