package com.swarmos.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PeerState(
    val info: NodeInfo,
    val health: NodeHealth? = null,
    val away: Boolean = false,
    val lastSeenMs: Long = 0,
)

/** This node plus the peers it has seen. All state is observable. */
class NodeRegistry(initialSelf: NodeInfo) {
    private val _self = MutableStateFlow(initialSelf)
    val self: StateFlow<NodeInfo> = _self.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    val peers: StateFlow<List<PeerState>> = _peers.asStateFlow()

    fun updateSelf(transform: (NodeInfo) -> NodeInfo) = _self.update(transform)

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
}
