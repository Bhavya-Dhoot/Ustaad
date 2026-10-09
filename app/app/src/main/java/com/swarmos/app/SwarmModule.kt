package com.swarmos.app

import androidx.compose.runtime.Composable
import io.ktor.server.routing.Route

/** One feature module (tab + node API routes). Each package exposes exactly one `object XxxModule`. */
interface SwarmModule {
    val id: String                // "link", "models", "choir", "pdu", "memcloud"
    val title: String             // tab label
    fun Route.routes()            // Ktor routes mounted under /v1/<id>/ on the node server
    @Composable fun Screen()      // tab content
}
