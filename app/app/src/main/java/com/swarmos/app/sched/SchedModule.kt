package com.swarmos.app.sched

import androidx.compose.runtime.Composable
import com.swarmos.app.AppGraph
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.NodeInfo
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Wiring of the scheduler to [AppGraph]. Everything is lazy so nothing runs before AppGraph.init. */
object Sched {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private val remotes = ConcurrentHashMap<String, RemoteLlmClient>()

    private fun remoteFor(peer: NodeInfo): RemoteLlmClient =
        remotes.compute(peer.nodeId) { _, old -> if (old != null && old.peer.ip == peer.ip) old else RemoteLlmClient(peer, AppGraph.nodeClient) }!!

    /** This phone first, then every peer the registry has not marked away. */
    private fun allEndpoints(): List<PhoneEndpoint> =
        listOf<PhoneEndpoint>(local) + AppGraph.registry.peers.value.filter { !it.away }.map { remoteFor(it.info) }

    /** Every phone as one client. */
    val swarm: SwarmLlm by lazy { SwarmLlm(::allEndpoints, scope) }

    /** The same scheduler restricted to this phone, for the "this phone only" baseline. */
    val localOnly: SwarmLlm by lazy { SwarmLlm({ listOf(local) }, scope) }

    val batches: BatchStore by lazy { BatchStore(scope, { swarm }, { localOnly }) }

    /** Parallel chat jobs this phone accepts at once; set to the model server's slot count when it has more than one. */
    fun setCapacity(n: Int) {
        node.capacity = n
    }

    /** Asks each address for /v1/hello and adds the phone to the registry. */
    suspend fun registerPeers(ips: List<String>): PeersResponse = coroutineScope {
        PeersResponse(
            ips.map { ip ->
                async {
                    try {
                        val info = withTimeoutOrNull(3_000) { AppGraph.nodeClient.get<NodeInfo>(ip, "/hello") }
                        if (info == null) {
                            PeerResult(ip, false, error = "no answer within 3 s")
                        } else {
                            AppGraph.registry.add(info.copy(ip = ip))
                            PeerResult(ip, true, info.nodeId, info.name)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        PeerResult(ip, false, error = e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll(),
        )
    }
}

/** The scheduler's tab: swarm load, with the routes under /v1/sched/. */
object SchedModule : SwarmModule {
    override val id = "sched"
    override val title = "Swarm load"

    override fun Route.routes() {
        schedNodeRoutes(Sched.node)
        schedBatchRoutes(Sched.batches)
        schedPeersRoute(Sched::registerPeers)
    }

    @Composable
    override fun Screen() = ThroughputPanel()
}
