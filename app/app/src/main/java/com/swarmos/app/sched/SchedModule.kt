package com.swarmos.app.sched

import androidx.compose.runtime.Composable
import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.Ports
import com.swarmos.app.link.ConnectResult
import com.swarmos.app.link.PeerLinks
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** Wiring of the scheduler to [AppGraph]. Everything is lazy so nothing runs before AppGraph.init. */
object Sched {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Slots and result memory of this phone. Reads the raw `AppGraph.llm`: the gate is this node's own executor. */
    val node: SchedNode by lazy {
        SchedNode(
            scope,
            selfId = { AppGraph.registry.self.value.nodeId },
            llm = { AppGraph.llm },
            embedder = { AppGraph.embedder },
            health = { AppGraph.health.state.value },
        )
    }

    private val local: LocalEndpoint by lazy {
        LocalEndpoint(node, { AppGraph.llm }, { AppGraph.embedder }, { AppGraph.registry.self.value.nodeId })
    }

    private val streams: StreamClient by lazy {
        StreamClient({ AppGraph.pairingSecret }, { AppGraph.registry.self.value.nodeId })
    }

    private val remotes = ConcurrentHashMap<String, RemoteLlmClient>()

    private fun remoteFor(peer: NodeInfo): RemoteLlmClient =
        remotes.compute(peer.nodeId) { _, old -> if (old != null && old.peer.ip == peer.ip) old else RemoteLlmClient(peer, AppGraph.nodeClient, streams) }!!

    /** This phone first, then every peer the registry has not marked away (the link module keeps that flag from its heartbeats). */
    private fun allEndpoints(): List<PhoneEndpoint> =
        listOf<PhoneEndpoint>(local) + AppGraph.registry.peers.value.filter { !it.away }.map { remoteFor(it.info) }

    /** Every phone as one client. */
    val swarm: SwarmLlm by lazy { SwarmLlm(::allEndpoints, scope) }

    /** The same scheduler restricted to this phone, for the "this phone only" baseline. */
    val localOnly: SwarmLlm by lazy { SwarmLlm({ listOf(local) }, scope) }

    val batches: BatchStore by lazy { BatchStore(scope, { swarm }, { localOnly }) }

    /** Parallel chat jobs this phone accepts at once. The model runtime sets the chat server's slot count, 0 while it is not running. */
    fun setCapacity(n: Int) {
        node.capacity = n
    }

    /** Debug builds only: hands each address ("ip" or "ip:port") to the link module, which trusts it and starts its heartbeat. */
    suspend fun registerPeers(ips: List<String>): PeersResponse = coroutineScope {
        PeersResponse(
            ips.map { raw ->
                async {
                    val host = raw.substringBefore(':')
                    val port = raw.substringAfter(':', "").toIntOrNull() ?: Ports.NODE
                    PeerLinks.remember(host, port)
                    when (val r = PeerLinks.connect(host, port)) {
                        is ConnectResult.Ok -> PeerResult(raw, true, r.status.nodeId, r.status.name)
                        is ConnectResult.Untrusted -> PeerResult(raw, false, r.nodeId, r.name, "different pairing secret")
                        is ConnectResult.Failed -> PeerResult(raw, false, error = r.reason)
                    }
                }
            }.awaitAll(),
        )
    }
}

/** The scheduler: swarm load, with the routes under /v1/sched/. Shown through [ThroughputPanel] inside the Swarm screen. */
object SchedModule : SwarmModule {
    override val id = "sched"
    override val title = "Swarm load"
    override val showInNav = false   // embedded in the Swarm screen through ThroughputPanel()

    override fun Route.routes() {
        schedNodeRoutes(Sched.node)
        schedBatchRoutes(Sched.batches)
        schedPhonesRoute { Sched.swarm }
        if (BuildConfig.DEBUG) {
            schedPeersRoute(Sched::registerPeers)
            schedCapacityRoute(Sched::setCapacity) { Sched.node.caps() }
        }
    }

    @Composable
    override fun Screen() = ThroughputPanel()
}
