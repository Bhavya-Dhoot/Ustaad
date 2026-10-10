package com.swarmos.app.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException

/** One way to reach a peer: the path key (for logging and health) and how to send a request on it. */
class PathAttempt(val key: String, val send: suspend (reqId: String) -> String)

/** The path was declared away while a request was in flight; the request is retried on the next path. */
class PathLostCancellation(val key: String) : CancellationException("path $key went away")

class AllPathsFailed(val causes: List<Pair<String, Throwable>>) :
    IOException("all paths failed: " + causes.joinToString("; ") { (k, t) -> "$k: ${t.message ?: t::class.simpleName}" })

/**
 * Send one logical request over an ordered list of paths with one request id. A transport failure (connection
 * refused or reset, timeout, path declared away) moves to the next path with the SAME [reqId]; the receiver's
 * dedup cache (see [RequestDedup]) then replays the stored answer if the first attempt had already been executed,
 * so the request runs once. An application-level answer ([NodeHttpException], any HTTP status) is final: another
 * path would give the same answer. Caller cancellation is never swallowed.
 */
suspend fun sendWithFailover(
    reqId: String,
    attempts: List<PathAttempt>,
    onFailure: (key: String, cause: Throwable) -> Unit = { _, _ -> },
): String {
    require(attempts.isNotEmpty())
    val causes = mutableListOf<Pair<String, Throwable>>()
    for (a in attempts) {
        try {
            return a.send(reqId)
        } catch (e: NodeHttpException) {
            throw e
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            causes += a.key to e
            onFailure(a.key, e)
        } catch (e: Exception) {
            causes += a.key to e
            onFailure(a.key, e)
        }
    }
    throw if (causes.size == 1) causes[0].second else AllPathsFailed(causes)
}
