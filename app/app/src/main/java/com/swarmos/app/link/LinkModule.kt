package com.swarmos.app.link

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.ui.PlaceholderScreen
import io.ktor.server.routing.Route

object LinkModule : SwarmModule {
    override val id = "link"
    override val title = "Link"
    override fun Route.routes() {}
    @Composable override fun Screen() = PlaceholderScreen(title)
}
