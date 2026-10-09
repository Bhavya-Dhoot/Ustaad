package com.swarmos.app.choir

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.ui.PlaceholderScreen
import io.ktor.server.routing.Route

object ChoirModule : SwarmModule {
    override val id = "choir"
    override val title = "Choir"
    override fun Route.routes() {}
    @Composable override fun Screen() = PlaceholderScreen(title)
}
