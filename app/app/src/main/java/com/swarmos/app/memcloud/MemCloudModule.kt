package com.swarmos.app.memcloud

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.ui.PlaceholderScreen
import io.ktor.server.routing.Route

object MemCloudModule : SwarmModule {
    override val id = "memcloud"
    override val title = "MemCloud"
    override fun Route.routes() {}
    @Composable override fun Screen() = PlaceholderScreen(title)
}
