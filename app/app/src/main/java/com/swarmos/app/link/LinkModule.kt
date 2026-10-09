package com.swarmos.app.link

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.StateFlow

/** SwarmLink: discovery, pairing, heartbeat, Wi-Fi Direct and the throughput / RTT probe. */
object LinkModule : SwarmModule {
    override val id = "link"
    override val title = "Swarm"
    override fun Route.routes() = linkRoutes()
    @Composable override fun Screen() = LinkScreen()

    /** True while any network has validated internet. The StatsBar reads this for its WAN indicator. */
    val internetValidated: StateFlow<Boolean> get() = InternetState.validated

    /**
     * The peer's IP on the fastest bulk path (measured Mbps, Wi-Fi Direct before LAN until measured), or null when no Wi-Fi
     * path to it is up. Bluetooth is never returned: it carries control traffic only. For llama.cpp RPC (MemoryCloud) and
     * any other bulk socket the caller opens itself.
     */
    fun bestBulkAddress(peerNodeId: String): java.net.InetAddress? =
        PeerLinks.candidates(peerNodeId, Use.BULK).firstOrNull { it.link != LinkType.BT }
            ?.let { runCatching { java.net.InetAddress.getByName(it.ip) }.getOrNull() }

    /** `swarmos://pair?d=<base64url>` (or a raw payload): adopt the host's secret and reach it. */
    fun handleDeepLink(uri: String, confirmed: Boolean = false) = LinkRuntime.handleDeepLink(uri, confirmed)
}
