package com.swarmos.app.cloud

internal fun Peer.handshake(
    discoveryKey: ByteArray, caps: Caps = Caps.BASELINE, maxPending: Int = 64, maxPerSource: Int = 4, pendingMs: Long = 10_000L,
) = SessionHandshake(chain.cloudId, identity, nodeId, { chain.state() }, { listOf(discoveryKey) }, caps, pendingMs, maxPending, maxPerSource)

/** An established session between two members of the same cloud: (initiator's view, responder's view). */
internal fun sessionBetween(
    a: Peer, b: Peer, discoveryKey: ByteArray, now: Long = T0, capsA: Caps = Caps.BASELINE, capsB: Caps = Caps.BASELINE,
): Pair<Session, Session> {
    val ini = a.handshake(discoveryKey, capsA).initiate(b.nodeId, b.id, now)
    val resp = b.handshake(discoveryKey, capsB).respond(ini.init, now)
    val done = ini.complete(resp.reply, now)
    val sb = resp.finish(done.finish, now)
    return done.session to sb
}
