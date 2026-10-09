package com.swarmos.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.swarmStore by preferencesDataStore(name = "swarm")

/** DataStore-backed settings: node name, pairing secret, first-run setup flag and the "remove animations" preference. */
class SwarmPrefs(private val ctx: Context) {
    private val nameKey = stringPreferencesKey("node_name")
    private val secretKey = stringPreferencesKey("pairing_secret")
    private val setupDoneKey = booleanPreferencesKey("setup_done")
    private val removeAnimationsKey = booleanPreferencesKey("remove_animations")

    suspend fun name(): String? = ctx.swarmStore.data.first()[nameKey]
    suspend fun secret(): String? = ctx.swarmStore.data.first()[secretKey]
    suspend fun setName(v: String) {
        ctx.swarmStore.edit { it[nameKey] = v }
    }

    suspend fun setSecret(v: String) {
        ctx.swarmStore.edit { it[secretKey] = v }
    }

    /** True once the first-run setup was completed or skipped. */
    suspend fun setupDone(): Boolean = ctx.swarmStore.data.first()[setupDoneKey] ?: false
    suspend fun setSetupDone(v: Boolean) {
        ctx.swarmStore.edit { it[setupDoneKey] = v }
    }

    suspend fun removeAnimations(): Boolean = ctx.swarmStore.data.first()[removeAnimationsKey] ?: false
    suspend fun setRemoveAnimations(v: Boolean) {
        ctx.swarmStore.edit { it[removeAnimationsKey] = v }
    }
}
