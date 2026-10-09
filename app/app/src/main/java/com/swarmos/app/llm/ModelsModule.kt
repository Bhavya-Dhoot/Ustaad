package com.swarmos.app.llm

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.ui.PlaceholderScreen
import io.ktor.server.routing.Route

object ModelsModule : SwarmModule {
    override val id = "models"
    override val title = "Models"
    override fun Route.routes() {}
    @Composable override fun Screen() = PlaceholderScreen(title)
}
