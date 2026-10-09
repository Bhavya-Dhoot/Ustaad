package com.swarmos.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.swarmos.app.choir.ChoirModule
import com.swarmos.app.core.AuditLog
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.NodeRegistry
import com.swarmos.app.core.SwarmPrefs
import com.swarmos.app.core.localIpv4
import com.swarmos.app.core.toHex
import com.swarmos.app.link.LinkModule
import com.swarmos.app.link.NodeClient
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.llm.ModelsModule
import com.swarmos.app.memcloud.MemCloudModule
import com.swarmos.app.pdu.PduModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Manual service locator, no DI framework. Call [init] once from Application.onCreate. */
object AppGraph {
    /** Debug builds trust each other on this fixed secret so dev phones need no QR. */
    const val DEV_SECRET = "swarmos-dev-secret"

    lateinit var appContext: Context

    private lateinit var prefs: SwarmPrefs
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var storedName: String? = null

    @Volatile
    private var secret: String = DEV_SECRET

    /** HMAC key shared by paired nodes. Replace via [setPairingSecret] (QR pairing will call it). */
    val pairingSecret: String get() = secret

    val registry: NodeRegistry by lazy { NodeRegistry(buildSelf()) }
    val health: HealthMonitor by lazy { HealthMonitor(appContext) { registry.self.value.nodeId } }
    val audit: AuditLog by lazy { AuditLog(File(appContext.filesDir, "audit.jsonl")) }

    var llm: LlmClient by mutableStateOf<LlmClient>(FakeLlmClient())        // chat model on this phone
    var embedder: LlmClient by mutableStateOf<LlmClient>(FakeLlmClient())   // embedding model on this phone

    val nodeClient: NodeClient by lazy { NodeClient({ secret }, { registry.self.value.nodeId }) }

    val modules: List<SwarmModule> = listOf(LinkModule, ModelsModule, ChoirModule, PduModule, MemCloudModule)

    fun init(app: Application) {
        appContext = app.applicationContext
        prefs = SwarmPrefs(appContext)
        val (name, savedSecret) = runBlocking(Dispatchers.IO) { prefs.name() to prefs.secret() }
        storedName = name
        secret = savedSecret ?: if (BuildConfig.DEBUG) DEV_SECRET else randomSecret().also {
            ioScope.launch { prefs.setSecret(it) }
        }
        health.start()
        audit.append("app.init", JsonObject(mapOf("node" to JsonPrimitive(registry.self.value.nodeId))))
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

    fun setPairingSecret(newSecret: String) {
        require(newSecret.isNotEmpty())
        secret = newSecret
        ioScope.launch { prefs.setSecret(newSecret) }
        audit.append("pairing.secret_set") // the secret itself is never logged
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
