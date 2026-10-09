package com.swarmos.app.pdu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigTest {
    @After fun reset() {
        PduEmbedConfig.queryPrefix = ""
        PduEmbedConfig.documentPrefix = ""
    }

    @Test fun prefixesAreEmptyByDefaultSoTextGoesInUnchanged() {
        assertEquals("where is the bill", PduEmbedConfig.query("where is the bill"))
        assertEquals("IMG_2231.jpg\nbody", PduEmbedConfig.document("IMG_2231.jpg", "body"))
    }

    @Test fun prefixesApplyToQueriesAndDocumentsSeparately() {
        PduEmbedConfig.queryPrefix = "task: search result | query: "
        PduEmbedConfig.documentPrefix = "title: none | text: "
        assertEquals("task: search result | query: bill", PduEmbedConfig.query("bill"))
        assertEquals("title: none | text: a.txt\nbody", PduEmbedConfig.document("a.txt", "body"))
    }

    @Test fun onlyTheDocumentPrefixAndModelChangeTheIndexKey() {
        val before = PduEmbedConfig.indexKey("embeddinggemma-300M")
        PduEmbedConfig.queryPrefix = "q: "
        assertEquals(before, PduEmbedConfig.indexKey("embeddinggemma-300M")) // query prefix does not touch stored vectors
        PduEmbedConfig.documentPrefix = "d: "
        assertNotEquals(before, PduEmbedConfig.indexKey("embeddinggemma-300M"))
        assertNotEquals(PduEmbedConfig.indexKey("FAKE"), PduEmbedConfig.indexKey("embeddinggemma-300M"))
    }
}
