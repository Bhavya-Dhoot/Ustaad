package com.swarmos.app.pdu

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import io.ktor.server.routing.Route

object PduModule : SwarmModule {
    override val id = "pdu"
    override val title = "Data"
    override fun Route.routes() = pduRoutes()
    @Composable override fun Screen() = PduScreen()
}
