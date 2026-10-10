package com.swarmos.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.swarmStore by preferencesDataStore(name = "swarm")

/** Everything [SwarmPrefs.snapshot] reads at start, in one read. */
data class PrefsSnapshot(
    val name: String?,
    val secret: String?,
    val setupDone: Boolean,
    val removeAnimations: Boolean,
    val agentAccess: Boolean = false,
    val agentKey: String? = null,
)

/** DataStore-backed settings: node name, pairing secret, first-run setup flag, the "remove animations" preference and the agent endpoint switch and key. */
class SwarmPrefs(private val ctx: Context) {
    private val nameKey = stringPreferencesKey("node_name")
    private val secretKey = stringPreferencesKey("pairing_secret")
    private val setupDoneKey = booleanPreferencesKey("setup_done")
    private val removeAnimationsKey = booleanPreferencesKey("remove_animations")
    private val agentAccessKey = booleanPreferencesKey("agent_access")
    private val agentKeyKey = stringPreferencesKey("agent_key")

    /** One read of all settings. Suspends; never call it from the main thread's startup path with runBlocking. */
    suspend fun snapshot(): PrefsSnapshot {
        val p = ctx.swarmStore.data.first()
        return PrefsSnapshot(p[nameKey], p[secretKey], p[setupDoneKey] ?: false, p[removeAnimationsKey] ?: false, p[agentAccessKey] ?: false, p[agentKeyKey])
    }

    suspend fun setName(v: String) {
        ctx.swarmStore.edit { it[nameKey] = v }
    }

    suspend fun setSecret(v: String) {
        ctx.swarmStore.edit { it[secretKey] = v }
    }

    /** True once the first-run setup was completed or skipped. */
    suspend fun setSetupDone(v: Boolean) {
        ctx.swarmStore.edit { it[setupDoneKey] = v }
    }

    suspend fun setRemoveAnimations(v: Boolean) {
        ctx.swarmStore.edit { it[removeAnimationsKey] = v }
    }

    /** The agent endpoint switch and its key, written together; a null [key] removes the stored one. */
    suspend fun setAgent(on: Boolean, key: String?) {
        ctx.swarmStore.edit {
            it[agentAccessKey] = on
            if (key == null) it.remove(agentKeyKey) else it[agentKeyKey] = key
        }
    }
}
