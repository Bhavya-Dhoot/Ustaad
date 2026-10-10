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
)

/** DataStore-backed settings: node name, pairing secret, first-run setup flag and the "remove animations" preference. */
class SwarmPrefs(private val ctx: Context) {
    private val nameKey = stringPreferencesKey("node_name")
    private val secretKey = stringPreferencesKey("pairing_secret")
    private val setupDoneKey = booleanPreferencesKey("setup_done")
    private val removeAnimationsKey = booleanPreferencesKey("remove_animations")

    /** One read of all settings. Suspends; never call it from the main thread's startup path with runBlocking. */
    suspend fun snapshot(): PrefsSnapshot {
        val p = ctx.swarmStore.data.first()
        return PrefsSnapshot(p[nameKey], p[secretKey], p[setupDoneKey] ?: false, p[removeAnimationsKey] ?: false)
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
}
