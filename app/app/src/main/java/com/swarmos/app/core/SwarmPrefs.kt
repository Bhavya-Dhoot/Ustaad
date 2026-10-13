package com.swarmos.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val agentFiles: Boolean = false,
    val keepBusy: Boolean = false,
)

/** DataStore-backed settings: node name, pairing secret, first-run setup flag, the "remove animations" preference, the agent endpoint switches and key and the "Keep busy" switch. */
class SwarmPrefs(private val ctx: Context) {
    private val nameKey = stringPreferencesKey("node_name")
    private val secretKey = stringPreferencesKey("pairing_secret")
    private val setupDoneKey = booleanPreferencesKey("setup_done")
    private val removeAnimationsKey = booleanPreferencesKey("remove_animations")
    private val agentAccessKey = booleanPreferencesKey("agent_access")
    private val agentKeyKey = stringPreferencesKey("agent_key")
    private val agentFilesKey = booleanPreferencesKey("agent_files")
    private val keepBusyKey = booleanPreferencesKey("keep_busy")

    /** One read of all settings. Suspends; never call it from the main thread's startup path with runBlocking. */
    suspend fun snapshot(): PrefsSnapshot {
        val p = ctx.swarmStore.data.first()
        return PrefsSnapshot(p[nameKey], p[secretKey], p[setupDoneKey] ?: false, p[removeAnimationsKey] ?: false, p[agentAccessKey] ?: false, p[agentKeyKey], p[agentFilesKey] ?: false, p[keepBusyKey] ?: false)
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

    /** The "Keep busy" switch (off by default). */
    suspend fun setKeepBusy(v: Boolean) {
        ctx.swarmStore.edit { it[keepBusyKey] = v }
    }

    private val pduLeanSourcesKey = booleanPreferencesKey("pdu_lean_sources")
    private val choirMajorityKey = booleanPreferencesKey("choir_majority_skips_round2")

    /** Data Union: give the model fewer, better sources. Off by default; changes what the model is given, so it waits for a phone measurement. */
    suspend fun pduLeanSources(): Boolean = ctx.swarmStore.data.first()[pduLeanSourcesKey] ?: false

    suspend fun setPduLeanSources(v: Boolean) {
        ctx.swarmStore.edit { it[pduLeanSourcesKey] = v }
    }

    /** Agent Choir: when every phone answered round 1 and a majority agrees, skip round 2. Off by default. */
    suspend fun choirMajoritySkipsRound2(): Boolean = ctx.swarmStore.data.first()[choirMajorityKey] ?: false

    suspend fun setChoirMajoritySkipsRound2(v: Boolean) {
        ctx.swarmStore.edit { it[choirMajorityKey] = v }
    }

    private val handsShareScreensKey = booleanPreferencesKey("hands_share_screens")
    private val handsTestBrainPortKey = intPreferencesKey("hands_test_brain_port")

    /** Hands: let the owner's other devices read the screen text during a task. Off by default. */
    suspend fun handsShareScreens(): Boolean = ctx.swarmStore.data.first()[handsShareScreensKey] ?: false

    suspend fun setHandsShareScreens(v: Boolean) {
        ctx.swarmStore.edit { it[handsShareScreensKey] = v }
    }

    private val handsFewerQuestionsKey = booleanPreferencesKey("hands_fewer_questions")

    /** Hands: ask only when a step is recognised as committing, not before every tap on a control not known to be harmless. Off by default. */
    suspend fun handsFewerQuestions(): Boolean = ctx.swarmStore.data.first()[handsFewerQuestionsKey] ?: false

    suspend fun setHandsFewerQuestions(v: Boolean) {
        ctx.swarmStore.edit { it[handsFewerQuestionsKey] = v }
    }

    /** Hands, debug builds only: port of a llama-server on 127.0.0.1 that both model tiers use. 0 = off. */
    suspend fun handsTestBrainPort(): Int = ctx.swarmStore.data.first()[handsTestBrainPortKey] ?: 0

    suspend fun setHandsTestBrainPort(v: Int) {
        ctx.swarmStore.edit { it[handsTestBrainPortKey] = v }
    }

    /** The agent endpoint switch, its key and the file search switch, written together; a null [key] removes the stored one. */
    suspend fun setAgent(on: Boolean, key: String?, files: Boolean) {
        ctx.swarmStore.edit {
            it[agentAccessKey] = on
            it[agentFilesKey] = files
            if (key == null) it.remove(agentKeyKey) else it[agentKeyKey] = key
        }
    }
}
