package com.swarmos.app.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** This phone's IPv4 on the local link: prefers wlan, then hotspot (ap or swlan), then any non-loopback. */
fun localIpv4(): String {
    val candidates = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress!! }
            }
    }.getOrDefault(emptyList())

    fun rank(n: String) = when {
        n.startsWith("wlan") -> 0
        n.startsWith("ap") || n.startsWith("swlan") -> 1
        else -> 2
    }
    return candidates.minByOrNull { rank(it.first) }?.second ?: "0.0.0.0"
}
