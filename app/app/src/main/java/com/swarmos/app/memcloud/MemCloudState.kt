package com.swarmos.app.memcloud

import kotlinx.serialization.Serializable

@Serializable
data class RestoreItem(val role: String, val file: String, val mb: Long? = null)

/** PLANNING, STARTING_PEERS, LOADING, READY, DEGRADED(peer) and FAILED(reason) as in the brief; IDLE and BASELINE are the two resting states around them. */
@Serializable
enum class Phase { IDLE, PLANNING, STARTING_PEERS, LOADING, READY, DEGRADED, FAILED, BASELINE }

/** One phone's slice of the model as planned. */
@Serializable
data class ShareView(
    val nodeId: String,
    val name: String,
    val isDriver: Boolean,
    val firstLayer: Int,
    val layers: Int,
    val range: String,
    val weightMb: Long,
    val needMb: Long,
    val haveMb: Long,
    val fits: Boolean,
)

/** Live view of one phone while the split loads and runs. */
@Serializable
data class PhoneView(
    val nodeId: String,
    val name: String,
    val isDriver: Boolean,
    /** Plain words for what this phone is doing right now. */
    val step: String,
    val online: Boolean = true,
    /** Resident memory of the process serving the split: llama-server on the driver, rpc-server on a peer. */
    val rssMb: Long? = null,
    /** Highest resident memory of that process so far (VmHWM). */
    val peakMb: Long? = null,
    /** Peers: anonymous memory of rpc-server, which is the weights received so far. */
    val receivedMb: Long? = null,
    /** Peers: size of rpc-server's on-disk tensor cache. */
    val cachedMb: Long? = null,
    /** 0..1 share of this phone's planned weights that has arrived. */
    val loadFraction: Float = 0f,
    val thermalStatus: Int? = null,
    val ramAvailMb: Long? = null,
    /** Heartbeat round trip from the driver (an HTTP call), null for the driver itself and while the peer is away. */
    val rttMs: Double? = null,
    /** Median TCP connect time from the driver to this phone's rpc-server port: one network round trip, no HTTP on top. */
    val tcpRttMs: Double? = null,
    /** What was stopped here to make room, e.g. "chat Qwen3.5-4B-Q4_K_M, 5.3 GB". */
    val freed: List<String> = emptyList(),
    /** What comes back when the split is stopped. */
    val restore: List<String> = emptyList(),
)

@Serializable
data class GenerationView(
    val prompt: String,
    val text: String = "",
    val running: Boolean = true,
    /** Request sent to first streamed text. */
    val ttftMs: Long? = null,
    /** From the server's own timings once the answer is complete; the client's chunk rate while it streams. */
    val decodeTokPerSec: Double? = null,
    val tokens: Int = 0,
    val error: String? = null,
)

@Serializable
data class BaselineView(
    val running: Boolean,
    /** LOADING, FAILED, KILLED, TIMEOUT, OK. */
    val outcome: String,
    val detail: String,
    val elapsedMs: Long,
    val tokPerSec: Double? = null,
)

/** The small chat model that was serving on this phone before MemoryCloud stopped it, with a fresh decode measurement (for scale). */
@Serializable
data class SmallModelView(val name: String, val tokPerSec: Double, val tokens: Int)

@Serializable
data class LogView(
    val offloaded: Int?,
    val offloadableTotal: Int?,
    val buffers: List<String>,
    val listening: Boolean,
    val error: String?,
)

@Serializable
data class MemCloudState(
    val phase: Phase = Phase.IDLE,
    val file: String? = null,
    /** `general.name` of the model, or the file name. */
    val model: String? = null,
    val layers: Int? = null,
    val experts: Int? = null,
    val expertsUsed: Int? = null,
    val moeLayers: Int? = null,
    val fileBytes: Long? = null,
    val ctx: Int? = null,
    /** The llama-server flags the plan turned into. */
    val args: List<String> = emptyList(),
    val shares: List<ShareView> = emptyList(),
    val phones: List<PhoneView> = emptyList(),
    val boundariesPerToken: Int? = null,
    val warnings: List<String> = emptyList(),
    /** Name of the phone whose loss put the split in DEGRADED. */
    val lostPhone: String? = null,
    val failReason: String? = null,
    val loadStartedAtMs: Long = 0,
    val loadMs: Long? = null,
    val log: LogView? = null,
    /** Memory hygiene and start-up steps in order, newest last. */
    val steps: List<String> = emptyList(),
    val generation: GenerationView? = null,
    val baseline: BaselineView? = null,
    val smallModel: SmallModelView? = null,
    /** Model servers MemoryCloud stopped to make room and has not restored yet, e.g. "Phone A: chat Qwen3.5-4B-Q4_K_M.gguf". */
    val pendingRestore: List<String> = emptyList(),
    /** Mean heartbeat round trip (HTTP) over the peers in the split. */
    val rttMs: Double? = null,
    /** Mean TCP connect round trip to the peers' rpc-server ports; the number ExpertMesh uses. */
    val tcpRttMs: Double? = null,
    val updatedAtMs: Long = 0,
)

@Serializable data class StartRequest(
    val file: String,
    /** Node ids or names ("Phone B"); empty means every peer that is online. */
    val peers: List<String> = emptyList(),
    /** "equal" (default) or "ram". */
    val mode: String = "equal",
    /** One layer count per phone (driver first); overrides [mode]. */
    val layers: List<Int>? = null,
)

@Serializable data class ReplanRequest(val peers: List<String> = emptyList())

@Serializable data class AskRequest(val prompt: String, val maxTokens: Int = 200)

@Serializable data class BaselineRequest(val timeoutSec: Int = 120, val file: String? = null)

@Serializable data class RpcStartRequest(
    val port: Int = 50052,
    val cache: Boolean = true,
    /** Link address to listen on; must belong to this phone's Wi-Fi or Direct interface. Never 0.0.0.0. Default: the address this request arrived on. */
    val bind: String? = null,
    /** Compute threads; null = one per core the app may use right now (see [CpuSet]). */
    val threads: Int? = null,
    /** Model servers the driver stopped on this phone; they are restarted when rpc-server stops, whoever stops it. */
    val restore: List<RestoreItem> = emptyList(),
    /**
     * True: this phone stops its own chat and embedding servers itself, as part of accepting the loan, after it has
     * recorded [restore] and the lease. False (older drivers send no field): the driver has already stopped them.
     */
    val stopOwn: Boolean = false,
)

@Serializable
data class RpcStateDto(
    val state: String,
    val port: Int,
    val bind: String?,
    val pid: Long?,
    val rssMb: Long?,
    /** Highest resident memory of rpc-server since it started (VmHWM). */
    val peakMb: Long? = null,
    /** Anonymous resident memory: the tensors the driver has sent so far. */
    val anonMb: Long?,
    val cachedBytes: Long,
    val cachedFiles: Int,
    val reason: String?,
    val lastLog: List<String>,
    val thermalStatus: Int,
    val ramAvailMb: Long,
    val args: List<String>,
    /** Name of the phone this one is lending memory to, null when not lending. */
    val lendingTo: String? = null,
    /** Milliseconds until the loan ends unless the driver renews it, null when not lending. */
    val leaseMsLeft: Long? = null,
    /**
     * Only in the answer to /rpc/start from a phone that understood [RpcStartRequest.stopOwn]: the servers it stopped itself
     * (empty when none were running). Null from an older phone, which stops nothing and leaves that to the driver.
     */
    val stoppedOwn: List<RestoreItem>? = null,
)
