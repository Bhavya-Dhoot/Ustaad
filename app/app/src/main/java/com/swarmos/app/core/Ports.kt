package com.swarmos.app.core

import com.swarmos.app.BuildConfig

/** All ports in one place. `-PportOffset=N` shifts NODE and TPUT so build variants can share a phone. */
object Ports {
    val NODE = 8787 + BuildConfig.PORT_OFFSET   // Node API (Ktor), all interfaces
    val TPUT = 8788 + BuildConfig.PORT_OFFSET   // raw TCP throughput probe, all interfaces
    const val LLM_CHAT = 8080                   // llama-server chat, 127.0.0.1
    const val LLM_EMBED = 8081                  // llama-server embeddings, 127.0.0.1
    const val RPC = 50052                       // llama.cpp rpc-server, link interface
}
