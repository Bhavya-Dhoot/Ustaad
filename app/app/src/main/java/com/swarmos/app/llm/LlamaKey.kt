package com.swarmos.app.llm

import java.security.SecureRandom
import java.util.Base64

/**
 * The API key of the llama-server processes this app starts. The servers listen on 127.0.0.1 where every app on the phone
 * can reach them, so each one is started with `--api-key` ([LlamaArgs.build]) and every HTTP call the app makes to one sends
 * it ([LlamaHttp]). One random key per app process start, kept in memory only: a server left over from an earlier process
 * (it has the old key) is not usable by this one and is killed at service start.
 */
object LlamaKey {
    val value: String by lazy { generate() }

    fun generate(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** The `Authorization` header value llama-server accepts. */
    fun bearer(key: String = value): String = "Bearer $key"
}
