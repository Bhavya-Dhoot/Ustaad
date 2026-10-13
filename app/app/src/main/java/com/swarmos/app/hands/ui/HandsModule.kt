package com.swarmos.app.hands.ui

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import io.ktor.server.routing.Route

/**
 * The Hands tab: give Ustaad a task, watch it, answer its questions, see what it needs to work.
 * No routes: another phone must not be able to start a task here, and the node API is not a way in for tasks.
 */
object HandsModule : SwarmModule {
    override val id = "hands"
    override val title = "Hands"
    override fun Route.routes() {}
    @Composable override fun Screen() = HandsScreen()
}
