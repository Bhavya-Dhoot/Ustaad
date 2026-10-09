package com.swarmos.app.link

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

enum class LinkType { LAN, DIRECT }

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
