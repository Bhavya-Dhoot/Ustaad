package com.swarmos.app.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

data class PeerState(
    val info: NodeInfo,
    val health: NodeHealth? = null,
    val away: Boolean = false,
    val lastSeenMs: Long = 0,
)

/**
 * Keeps [NodeRegistry.self] current for as long as it runs: every time the model names (chat, embedding) or the
 * address change, the registry's own entry is refreshed. Never returns; cancel the coroutine to stop it.
 */
suspend fun NodeRegistry.followSelf(models: Flow<Pair<String?, String?>>, ip: Flow<String>) {
    combine(models, ip) { m, address -> Triple(address, m.first, m.second) }
        .distinctUntilChanged()
        .collect { (address, chat, embed) -> refreshSelf(address, chat, embed) }
}

/** This node plus the peers it has seen. All state is observable. */
class NodeRegistry(initialSelf: NodeInfo) {
    // this node always advertises the protocol version of this build, whatever builds the NodeInfo
    private val _self = MutableStateFlow(initialSelf.copy(protocol = PROTOCOL_VERSION))
    val self: StateFlow<NodeInfo> = _self.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    val peers: StateFlow<List<PeerState>> = _peers.asStateFlow()

    fun updateSelf(transform: (NodeInfo) -> NodeInfo) = _self.update { transform(it).copy(protocol = PROTOCOL_VERSION) }

    /**
     * Brings this node's own ip and model names up to date: they change after start-up (a model finishes loading, the
     * Wi-Fi address changes) and everything that reads [self] (Choir, the strip) must not keep the start-up values.
     * Returns true when something changed.
     */
    fun refreshSelf(ip: String, chatModel: String?, embedModel: String?): Boolean {
        var changed = false
        _self.update { before ->
            before.copy(ip = ip, chatModel = chatModel, embedModel = embedModel).also { changed = it != before }
        }
        return changed
    }

    /** Insert or refresh a peer; clears its away flag. Ignores this node's own id. */
    fun add(info: NodeInfo, nowMs: Long = System.currentTimeMillis()) {
        if (info.nodeId == _self.value.nodeId) return
        _peers.update { list ->
            val old = list.firstOrNull { it.info.nodeId == info.nodeId }
            val fresh = PeerState(info, old?.health, away = false, lastSeenMs = nowMs)
            if (old == null) list + fresh else list.map { if (it.info.nodeId == info.nodeId) fresh else it }
        }
    }

    fun updateHealth(h: NodeHealth, nowMs: Long = System.currentTimeMillis()) {
        _peers.update { list ->
            list.map { if (it.info.nodeId == h.nodeId) it.copy(health = h, away = false, lastSeenMs = nowMs) else it }
        }
    }

    fun markAway(nodeId: String) {
        _peers.update { list -> list.map { if (it.info.nodeId == nodeId) it.copy(away = true) else it } }
    }

    fun remove(nodeId: String) {
        _peers.update { list -> list.filter { it.info.nodeId != nodeId } }
    }

    /**
     * Two entries with one address cannot both be there (a phone came back under a new node id, or its address went to
     * another phone). Keeps the entry that answers and removes the others; entries that merely are away and share no
     * address with another phone stay. Returns the removed node ids.
     */
    fun dropAddressConflicts(): List<String> {
        var dropped: Set<String> = emptySet()
        _peers.update { list ->
            dropped = addressLosers(list)
            if (dropped.isEmpty()) list else list.filter { it.info.nodeId !in dropped }
        }
        return dropped.toList()
    }
}

/**
 * Of the entries that share one address, all but the one that answers (not away, latest seen). When none of them
 * answers nothing is dropped: it cannot be told which one is gone.
 */
internal fun addressLosers(peers: List<PeerState>): Set<String> {
    val losers = mutableSetOf<String>()
    peers.filter { it.info.ip.isNotBlank() && it.info.ip != "0.0.0.0" && !it.info.ip.startsWith("bt:") }
        .groupBy { it.info.ip }.values.filter { it.size > 1 }
        .forEach { group ->
            val winner = group.filter { !it.away }.maxByOrNull { it.lastSeenMs } ?: return@forEach
            group.filter { it !== winner }.forEach { losers += it.info.nodeId }
        }
    return losers
}
