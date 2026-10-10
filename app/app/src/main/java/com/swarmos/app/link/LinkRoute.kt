package com.swarmos.app.link

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

enum class LinkType { LAN, DIRECT, BT }

/** One IPv4 address on one local interface. */
data class LocalLink(val ifName: String, val address: String, val prefix: Int) {
    val type: LinkType get() = if (ifName.startsWith("p2p")) LinkType.DIRECT else LinkType.LAN
}

/**
 * Which local interface reaches a peer address, and sockets bound to it.
 * A peer on the p2p group subnet (interface `p2p-wlan0-N`) is DIRECT; anything else is LAN.
 */
object LinkRoute {
    /** All up, non-loopback IPv4 addresses with their prefix length. */
    fun localLinks(): List<LocalLink> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.interfaceAddresses
                    .filter { it.address is Inet4Address }
                    .map { LocalLink(nif.name, it.address.hostAddress!!, it.networkPrefixLength.toInt()) }
            }
    }.getOrDefault(emptyList())

    /** Interfaces worth advertising to a peer: Wi-Fi station, hotspot and p2p. Never cellular. */
    fun advertisable(links: List<LocalLink> = localLinks()): List<LocalLink> =
        links.filter { l -> listOf("wlan", "ap", "swlan", "p2p").any { l.ifName.startsWith(it) } }

    fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
        val x = ipv4ToInt(a) ?: return false
        val y = ipv4ToInt(b) ?: return false
        if (prefix <= 0) return false
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        return (x and mask) == (y and mask)
    }

    /** The local link whose subnet contains [peerIp], or null when the peer is not on a directly connected subnet. */
    fun findLocalFor(peerIp: String, links: List<LocalLink> = localLinks()): LocalLink? =
        links.firstOrNull { sameSubnet(it.address, peerIp, it.prefix) }

    /**
     * What pairing says when it adopted the secret and nothing answered: the host is on another network, this phone has
     * no Wi-Fi address, or the networks match and the host just did not reply. Unusable host addresses are skipped.
     * [hadDirectCreds] is carried for the caller's sake: a Wi-Fi Direct failure already leaves its own note, so this
     * text is only used when the code had no Direct credentials, and it does not change the wording.
     */
    fun unreachableHostText(hostName: String, hostIps: List<String>, ours: List<LocalLink>, hadDirectCreds: Boolean): String {
        // an address that nothing answered is remembered for PeerLinks.ENDPOINT_TTL_MS and then forgotten
        val trying = "Ustaad keeps trying for ${PeerLinks.ENDPOINT_TTL_MS / 60_000} minutes. After that, scan the code again."
        val mine = ours.firstOrNull { it.type == LinkType.LAN } ?: ours.firstOrNull()
            ?: return "secret adopted, but this phone has no Wi-Fi address. Connect it to the host's Wi-Fi. $trying"
        val valid = hostIps.filter { ipv4ToInt(it) != null }
        if (valid.isEmpty()) return "secret adopted, but the code holds no usable address for the host. Scan the code again." // nothing is remembered, so nothing is retried
        if (valid.any { ip -> findLocalFor(ip, ours) != null })
            return "secret adopted, host not reachable yet. Same network: check that Ustaad is open on $hostName. $trying"
        return "secret adopted, but $hostName is on another network (its address ${valid.first()}, this phone ${mine.address}). " +
            "Put both phones on the same Wi-Fi. $trying"
    }

    /**
     * Peers are only ever on the local network: RFC 1918 private ranges (the Wi-Fi Direct group 192.168.49.x is inside
     * 192.168/16) and IPv4 link-local. Anything else, WAN addresses included, is never contacted or remembered.
     */
    fun isLocalAddress(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return false
        val v = p.map { it.toIntOrNull() ?: return false }
        if (v.any { it !in 0..255 }) return false
        return when {
            v[0] == 10 -> true
            v[0] == 172 && v[1] in 16..31 -> true
            v[0] == 192 && v[1] == 168 -> true
            v[0] == 169 && v[1] == 254 -> true
            else -> false
        }
    }

    fun typeFor(peerIp: String, links: List<LocalLink> = localLinks()): LinkType =
        findLocalFor(peerIp, links)?.type ?: LinkType.LAN

    private fun ipv4ToInt(s: String): Int? {
        val p = s.split('.')
        if (p.size != 4) return null
        var r = 0
        for (part in p) {
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            r = (r shl 8) or v
        }
        return r
    }

    /**
     * Connected socket to [peerIp]:[port] bound to the interface that reaches the peer: through the
     * ConnectivityManager Network when one exists for that interface (not guaranteed for p2p), otherwise
     * by binding the local address on that subnet.
     */
    fun openSocket(ctx: Context?, peerIp: String, port: Int, connectTimeoutMs: Int = 5_000): Socket {
        val local = findLocalFor(peerIp)
        val s = Socket()
        try {
            LinkTuning.apply(s)
            if (local != null) {
                val net = ctx?.let { c ->
                    val cm = c.getSystemService(ConnectivityManager::class.java)
                    cm?.allNetworks?.firstOrNull { n -> cm.getLinkProperties(n)?.interfaceName == local.ifName }
                }
                if (net != null) net.bindSocket(s) else s.bind(InetSocketAddress(InetAddress.getByName(local.address), 0))
            }
            s.connect(InetSocketAddress(peerIp, port), connectTimeoutMs)
            return s
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
    }
}
