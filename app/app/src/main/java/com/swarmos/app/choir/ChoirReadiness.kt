package com.swarmos.app.choir

import com.swarmos.app.llm.LlmClient

/**
 * Whether a phone has a real chat model, in one place. The model runtime does not expose a readiness flag yet
 * (`LlamaRuntime.realChatReady` is not on master), so this reads the client's name: the placeholder client is "FAKE".
 * When the flag lands, change [realChatReady] and nothing else.
 *
 * In a demo (non-debug) build nothing runs on the placeholder: a run needs a real chat model on the coordinator, and a
 * peer that only has the placeholder is listed as a lane that was not asked. Debug builds keep the fake path (its
 * answers carry FAKE stamps) for tests and adb-driven checks.
 */
object ChoirReadiness {
    const val NO_MODEL_REASON = "No chat model is loaded on this phone. Open Models to load one."
    const val PEER_NO_MODEL = "no model loaded, not asked"

    fun realChatReady(llm: LlmClient): Boolean = isRealModel(llm.modelName)

    /** A peer's `chatModel` as it announces itself (NodeInfo): null or "FAKE" means it has no real model. */
    fun peerHasRealModel(chatModel: String?): Boolean = !chatModel.isNullOrBlank() && isRealModel(chatModel)

    private fun isRealModel(name: String) = name != "FAKE"

    /** Why Run and Run all must stay disabled, or null. Only demo builds refuse; debug builds run on the fake path. */
    fun refusal(debugBuild: Boolean, coordinatorReady: Boolean): String? =
        if (!debugBuild && !coordinatorReady) NO_MODEL_REASON else null
}
