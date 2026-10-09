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

    /** `swarmos://pair?d=<base64url>` (or a raw payload): adopt the host's secret and reach it. */
    fun handleDeepLink(uri: String) = LinkRuntime.handleDeepLink(uri)
}
