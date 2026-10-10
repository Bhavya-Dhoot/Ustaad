package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreachableHostTextTest {
    private val iqoo = LocalLink("wlan0", "192.168.113.47", 23)

    private fun text(hostIps: List<String>, ours: List<LocalLink>, direct: Boolean = false) =
        LinkRoute.unreachableHostText("Phone B", hostIps, ours, direct)

    @Test fun hostOnAnotherNetworkSaysSoWithBothAddresses() {
        // 192.168.113.47/23 covers 192.168.112.0 to 192.168.113.255; the host at 192.168.242.23 is outside it
        assertEquals(
            "secret adopted, but Phone B is on another network (its address 192.168.242.23, this phone 192.168.113.47). " +
                "Put both phones on the same Wi-Fi. Will keep trying.",
            text(listOf("192.168.242.23"), listOf(iqoo)),
        )
    }

    @Test fun phoneWithNoAddressSaysItHasNoWifi() {
        assertEquals(
            "secret adopted, but this phone has no Wi-Fi address. Connect it to the host's Wi-Fi. Will keep trying.",
            text(listOf("192.168.242.23"), emptyList()),
        )
    }

    @Test fun sameSubnetWithoutAnswerAsksToCheckTheHost() {
        assertEquals(
            "secret adopted, host not reachable yet. Same network: check that Ustaad is open on Phone B. Will keep trying.",
            text(listOf("192.168.113.200"), listOf(iqoo)),
        )
    }

    @Test fun oneHostAddressOnOurSubnetCountsAsTheSameNetwork() {
        assertEquals(
            "secret adopted, host not reachable yet. Same network: check that Ustaad is open on Phone B. Will keep trying.",
            text(listOf("192.168.242.23", "192.168.112.9"), listOf(iqoo)),
        )
    }

    @Test fun malformedHostAddressesAreIgnored() {
        val other = text(listOf("not-an-ip", "300.1.1.1", "192.168.242.23"), listOf(iqoo))
        assertEquals(
            "secret adopted, but Phone B is on another network (its address 192.168.242.23, this phone 192.168.113.47). " +
                "Put both phones on the same Wi-Fi. Will keep trying.",
            other,
        )
        assertEquals(
            "secret adopted, host not reachable yet (will keep trying)",
            text(listOf("not-an-ip", ""), listOf(iqoo)),
        )
    }
}
