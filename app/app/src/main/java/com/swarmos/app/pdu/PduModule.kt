package com.swarmos.app.pdu

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.ui.PlaceholderScreen
import io.ktor.server.routing.Route

object PduModule : SwarmModule {
    override val id = "pdu"
    override val title = "PDU"
    override fun Route.routes() {}
    @Composable override fun Screen() = PlaceholderScreen(title)
}
