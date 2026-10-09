package com.swarmos.app.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.swarmStore by preferencesDataStore(name = "swarm")

/** DataStore-backed settings: node name and pairing secret. */
class SwarmPrefs(private val ctx: Context) {
    private val nameKey = stringPreferencesKey("node_name")
    private val secretKey = stringPreferencesKey("pairing_secret")

    suspend fun name(): String? = ctx.swarmStore.data.first()[nameKey]
    suspend fun secret(): String? = ctx.swarmStore.data.first()[secretKey]
    suspend fun setName(v: String) {
        ctx.swarmStore.edit { it[nameKey] = v }
    }

    suspend fun setSecret(v: String) {
        ctx.swarmStore.edit { it[secretKey] = v }
    }
}
