package com.swarmos.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.swarmos.app.agent.AgentAccess
import com.swarmos.app.busy.BusyMode
import com.swarmos.app.choir.ChoirModule
import com.swarmos.app.core.AuditLog
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.NodeRegistry
import com.swarmos.app.core.OrderedWriter
import com.swarmos.app.core.PairingSecret
import com.swarmos.app.core.SwarmPrefs
import com.swarmos.app.core.followSelf
import com.swarmos.app.core.localIpv4
import com.swarmos.app.core.toHex
import com.swarmos.app.kb.KbModule
import com.swarmos.app.link.LinkModule
import com.swarmos.app.link.NodeClient
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.llm.ModelsModule
import com.swarmos.app.memcloud.MemCloudModule
import com.swarmos.app.pdu.PduModule
import com.swarmos.app.sched.GatedLlmClient
import com.swarmos.app.sched.Sched
import com.swarmos.app.sched.SchedModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Manual service locator, no DI framework. Call [init] once from Application.onCreate. */
object AppGraph {
    /** Debug builds trust each other on this fixed secret so dev phones need no QR. */
    const val DEV_SECRET = PairingSecret.DEV

    lateinit var appContext: Context

    private lateinit var prefs: SwarmPrefs
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var storedName: String? = null

    // Until the stored secret has loaded this is a random value nobody knows, in every build: no request can be
    // signed with it. Debug builds switch to DEV_SECRET in init() only when nothing is stored.
    @Volatile
    private var secret: String = PairingSecret.placeholder(::randomSecret)

    /** HMAC key shared by paired nodes. Replace via [setPairingSecret] (QR pairing will call it). */
    val pairingSecret: String get() = secret

    /** False until the first-run setup is completed or skipped (stored in [SwarmPrefs]). */
    var setupDone by mutableStateOf(false)
        private set

    /**
     * True once the preferences, the audit log and the node identity are loaded (a few ms after the process starts,
     * off the main thread). The UI shows a skeleton until then; background work waits with [awaitReady].
     */
    var ready by mutableStateOf(false)
        private set
    private val readyGate = CompletableDeferred<Unit>()

    suspend fun awaitReady() = readyGate.await()

    /** True while a MainActivity is started. The service holds its wake and Wi-Fi locks only while this or a peer is online. */
    val foreground = MutableStateFlow(false)

    /** In-app "Remove animations" preference; `rememberAnimationsEnabled()` honours it besides the system setting. */
    var removeAnimations by mutableStateOf(false)
        private set

    val registry: NodeRegistry by lazy { NodeRegistry(buildSelf()) }
    val health: HealthMonitor by lazy { HealthMonitor(appContext) { registry.self.value.nodeId } }
    val audit: AuditLog by lazy { AuditLog(File(appContext.filesDir, "audit.jsonl")) }

    var llm: LlmClient by mutableStateOf<LlmClient>(FakeLlmClient())        // chat model on this phone
    var embedder: LlmClient by mutableStateOf<LlmClient>(FakeLlmClient())   // embedding model on this phone

    /**
     * The chat model for features that run on this phone (Choir, Data Union): every call takes a scheduler slot, so the
     * slot count stays right while they run. [llm] stays the raw client the model runtime installs and inspects.
     */
    val gatedLlm: LlmClient by lazy { GatedLlmClient({ llm }, Sched.node) }

    val nodeClient: NodeClient by lazy { NodeClient({ secret }, { registry.self.value.nodeId }) }

    val modules: List<SwarmModule> = listOf(LinkModule, ModelsModule, ChoirModule, PduModule, KbModule, MemCloudModule, SchedModule, com.swarmos.app.hands.ui.HandsModule)

    /** Starts loading everything that needs disk off the main thread; nothing here blocks Application.onCreate. */
    fun init(app: Application) {
        appContext = app.applicationContext
        prefs = SwarmPrefs(appContext)
        ioScope.launch {
            try {
                val p = runCatching { prefs.snapshot() }.getOrNull() // unreadable settings: start as a fresh install
                storedName = p?.name
                setupDone = p?.setupDone ?: false
                removeAnimations = p?.removeAnimations ?: false
                PairingSecret.afterLoad(p?.secret, BuildConfig.DEBUG, ::randomSecret).let { (chosen, mustSave) ->
                    // through the writer when it must be stored, so a secret changed by the user meanwhile is stored after it, not before
                    if (mustSave) secretWriter.set(chosen) else secret = chosen
                }
                AgentAccess.restore(p?.agentAccess ?: false, p?.agentKey, p?.agentFiles ?: false) { on, key, files -> prefs.setAgent(on, key, files) }
                audit.load()
                BusyMode.restore(p?.keepBusy ?: false, { on -> prefs.setKeepBusy(on) }) { kind, payload -> audit.append(kind, payload) }
                health.start()
                audit.append("app.init", JsonObject(mapOf("node" to JsonPrimitive(registry.self.value.nodeId))))
                // restore models and release lenders if a MemoryCloud session was killed mid-way
                runCatching { com.swarmos.app.memcloud.MemCloud.onAppStart() }
                // registry.self carries this phone's model names and address: follow the runtime and the network
                launch {
                    registry.followSelf(
                        snapshotFlow { llm.modelName to embedder.modelName },
                        health.state.map { localIpv4() },
                    )
                }
            } finally {
                ready = true
                readyGate.complete(Unit)
            }
        }
    }

    /** Current NodeInfo with live ip and model names. This is what /v1/hello returns. */
    fun selfInfo(): NodeInfo = registry.self.value.copy(
        ip = localIpv4(),
        chatModel = llm.modelName,
        embedModel = embedder.modelName,
    )

    fun setNodeName(name: String) {
        val clean = name.trim().take(32)
        if (clean.isEmpty()) return
        registry.updateSelf { it.copy(name = clean) }
        ioScope.launch { prefs.setName(clean) }
        audit.append("node.rename", JsonObject(mapOf("name" to JsonPrimitive(clean))))
    }

    /** The one writer of the pairing secret: it is published in memory and stored through this, so the last one set is the last one stored. */
    private val secretWriter by lazy { OrderedWriter<String>(ioScope, { secret = it }, { prefs.setSecret(it) }) }

    fun setPairingSecret(newSecret: String) {
        require(newSecret.isNotEmpty())
        secretWriter.set(newSecret)
        audit.append("pairing.secret_set") // the secret itself is never logged
    }

    /** New random secret; every paired phone has to pair again. */
    fun resetPairingSecret() = setPairingSecret(randomSecret())

    fun markSetupDone(done: Boolean) {
        setupDone = done
        ioScope.launch { prefs.setSetupDone(done) }
    }

    fun markAnimationsRemoved(remove: Boolean) {
        removeAnimations = remove
        ioScope.launch { prefs.setRemoveAnimations(remove) }
    }

    @SuppressLint("HardwareIds")
    private fun buildSelf(): NodeInfo {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val nodeId = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray()).toHex().take(8)
        return NodeInfo(
            nodeId = nodeId,
            name = storedName ?: "Phone ${nodeId.take(4).uppercase()}",
            serial = serialOrUnknown(),
            ip = localIpv4(),
            appVersion = BuildConfig.VERSION_NAME,
            chatModel = llm.modelName,
            embedModel = embedder.modelName,
        )
    }

    // Build.getSerial() needs a privileged permission on Android 10+, so apps normally just get "unknown".
    @SuppressLint("HardwareIds", "MissingPermission")
    private fun serialOrUnknown(): String = runCatching { Build.getSerial() }.getOrDefault("unknown")

    private fun randomSecret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
}
