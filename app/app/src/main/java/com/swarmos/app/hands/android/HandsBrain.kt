package com.swarmos.app.hands.android

import com.swarmos.app.hands.Brain
import com.swarmos.app.hands.Tier
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** What the brain needs from the swarm. The app's own implementation is in [HandsRuntime]; tests use a fake. */
interface SwarmSide {
    /** Chat model file names of the phones other than this one that take chat jobs now, from the last report (a plain read, no network). */
    fun peerModels(): List<String>

    /** Asks the phones for fresh reports. May take a second or two when a phone is slow. */
    suspend fun refresh()

    /** One chat job on a phone of the cluster that runs [pinModel] (any phone when null). */
    suspend fun chat(req: ChatRequest, pinModel: String?): String
}

/** No model can take this call. The message is for the person: the step loop and the run show it as it is. */
class NoModelException(message: String) : RuntimeException(message)

/**
 * The [Brain] of Hands over the app's model clients. Every call goes through [BrainRoute], the one rule about where screen text may go.
 *
 * [local] is this phone's model behind its slot gate; [localReady] is true only while that is a real model that answers JSON-schema
 * requests. [testClient] is the debug-only stand-in: when it is not null every call of both tiers goes there (see [HandsSettings]).
 * [modelBytes] gives the size of a model file by name (0 when unknown), which is how "stronger" is told: a model of unknown size is
 * never called stronger. [scope], when given, lets [has] start a refresh of the swarm reports in the background; [has] itself never waits.
 */
class HandsBrain(
    private val local: () -> LlmClient,
    private val localReady: () -> Boolean,
    private val localModel: () -> String,
    private val swarm: SwarmSide,
    private val shareScreens: () -> Boolean,
    private val testClient: () -> LlmClient?,
    private val modelBytes: (String) -> Long,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutBaseMs: Long = BASE_TIMEOUT_MS,
) : Brain {

    private class Look(val route: (Tier, Boolean) -> Route, val test: LlmClient?, val pin: String?, val sharing: Boolean)

    @Volatile private var refreshedAt = 0L
    private val refreshing = AtomicBoolean(false)

    override fun has(tier: Tier): Boolean {
        kickRefresh()
        val look = look()
        return when (tier) {
            Tier.FAST -> look.route(Tier.FAST, true)
            Tier.STRONG -> look.route(Tier.STRONG, false)
        } != Route.NONE
    }

    override suspend fun complete(tier: Tier, system: String, user: String, schema: JsonObject, maxTokens: Int, onPhoneOnly: Boolean): String {
        // the swarm is only asked about when this call could go there
        if (testClient() == null && !(onPhoneOnly && !shareScreens())) refreshIfStale()
        val look = look()
        val req = ChatRequest(
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
            maxTokens = maxTokens,
            temperature = TEMPERATURE,
            jsonSchema = schema.toString(),
            cachePrompt = true,
        )
        return when (look.route(tier, onPhoneOnly)) {
            Route.TEST -> timed(maxTokens) { look.test!!.chat(req).text }
            Route.LOCAL -> timed(maxTokens) { local().chat(req).text }
            Route.SWARM -> swarm.chat(req, look.pin)
            Route.NONE -> throw NoModelException(if (onPhoneOnly && !look.sharing) NO_LOCAL_MODEL else NO_MODEL_ANYWHERE)
        }.trim()
    }

    private fun look(): Look {
        val test = testClient()
        val ready = localReady()
        val share = shareScreens()
        val peers = swarm.peerModels()
        val best = peers.maxByOrNull { modelBytes(it) }
        val bestBytes = best?.let(modelBytes) ?: 0L
        val localBytes = if (ready) modelBytes(localModel()) else 0L
        // "stronger" needs a size we can read: a peer of unknown size is never called stronger
        val stronger = bestBytes > 0 && (!ready || (localBytes > 0 && bestBytes > localBytes))
        return Look(
            route = { tier, onPhoneOnly ->
                BrainRoute.pick(tier, onPhoneOnly, ready, peers.isNotEmpty(), stronger, share, test != null)
            },
            test = test,
            pin = best,
            sharing = share,
        )
    }

    /** Runs the call with a time limit that is an ordinary failure, not a cancellation: the step loop must see it as "the model could not answer". */
    private suspend fun timed(maxTokens: Int, block: suspend () -> String): String {
        val ms = timeoutBaseMs + maxTokens.coerceAtLeast(0) * MS_PER_TOKEN
        return withTimeoutOrNull(ms) { block() } ?: throw IllegalStateException("The model did not answer within ${ms / 1000} s.")
    }

    private fun kickRefresh() {
        val sc = scope ?: return
        if (clock() - refreshedAt < REFRESH_EVERY_MS || refreshing.get()) return
        sc.launch { refreshIfStale() }
    }

    private suspend fun refreshIfStale() {
        if (clock() - refreshedAt < REFRESH_EVERY_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            swarm.refresh()
            refreshedAt = clock()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a phone that does not answer is simply not in the list
        } finally {
            refreshing.set(false)
        }
    }

    companion object {
        const val NO_LOCAL_MODEL =
            "This phone has no model of its own for this step. Load a model, or turn on sharing the screen with your other devices in Hands."
        const val NO_MODEL_ANYWHERE = "No chat model is loaded on this phone or on another device of yours. Load a model in Models."
        const val TEMPERATURE = 0.2
        const val REFRESH_EVERY_MS = 4_000L
        const val BASE_TIMEOUT_MS = 60_000L
        const val MS_PER_TOKEN = 200L
    }
}
