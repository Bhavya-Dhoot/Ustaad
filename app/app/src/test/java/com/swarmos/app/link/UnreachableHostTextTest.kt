package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreachableHostTextTest {
    private val iqoo = LocalLink("wlan0", "192.168.113.47", 23)
    private val trying = "Ustaad keeps trying for 10 minutes. After that, scan the code again."

    private fun text(hostIps: List<String>, ours: List<LocalLink>, direct: Boolean = false) =
        LinkRoute.unreachableHostText("Phone B", hostIps, ours, direct)

    @Test fun hostOnAnotherNetworkSaysSoWithBothAddresses() {
        // 192.168.113.47/23 covers 192.168.112.0 to 192.168.113.255; the host at 192.168.242.23 is outside it
        assertEquals(
            "secret adopted, but Phone B is on another network (its address 192.168.242.23, this phone 192.168.113.47). " +
                "Put both phones on the same Wi-Fi. $trying",
            text(listOf("192.168.242.23"), listOf(iqoo)),
        )
    }

    @Test fun phoneWithNoAddressSaysItHasNoWifi() {
        assertEquals(
            "secret adopted, but this phone has no Wi-Fi address. Connect it to the host's Wi-Fi. $trying",
            text(listOf("192.168.242.23"), emptyList()),
        )
    }

    @Test fun sameSubnetWithoutAnswerAsksToCheckTheHost() {
        assertEquals(
            "secret adopted, host not reachable yet. Same network: check that Ustaad is open on Phone B. $trying",
            text(listOf("192.168.113.200"), listOf(iqoo)),
        )
    }

    @Test fun oneHostAddressOnOurSubnetCountsAsTheSameNetwork() {
        assertEquals(
            "secret adopted, host not reachable yet. Same network: check that Ustaad is open on Phone B. $trying",
            text(listOf("192.168.242.23", "192.168.112.9"), listOf(iqoo)),
        )
    }

    @Test fun malformedHostAddressesAreIgnored() {
        val other = text(listOf("not-an-ip", "300.1.1.1", "192.168.242.23"), listOf(iqoo))
        assertEquals(
            "secret adopted, but Phone B is on another network (its address 192.168.242.23, this phone 192.168.113.47). " +
                "Put both phones on the same Wi-Fi. $trying",
            other,
        )
        // nothing is remembered from such a code, so the text does not promise a retry
        val none = text(listOf("not-an-ip", ""), listOf(iqoo))
        assertEquals("secret adopted, but the code holds no usable address for the host. Scan the code again.", none)
        assertFalse(none.contains("keeps trying"))
    }

    @Test fun theMinutesInTheTextAreTheLifetimeOfARememberedAddress() {
        assertEquals(10 * 60_000L, PeerLinks.ENDPOINT_TTL_MS)
        assertTrue(text(listOf("192.168.242.23"), listOf(iqoo)).contains("keeps trying for ${PeerLinks.ENDPOINT_TTL_MS / 60_000} minutes"))
    }
}
