package com.swarmos.app.cloud

internal fun Dev.handshake(discoveryKey: ByteArray) =
    SessionHandshake(log.cloudId, identity, nodeId, { log.state() }, { discoveryKey })

/** An established session between two devices of the same cloud: (initiator's view, responder's view). */
internal fun sessionBetween(a: Dev, b: Dev, discoveryKey: ByteArray, now: Long = T0): Pair<Session, Session> {
    val ha = a.handshake(discoveryKey)
    val hb = b.handshake(discoveryKey)
    val ini = ha.initiate(b.nodeId, now)
    val resp = hb.respond(ini.init, now)
    val done = ini.complete(resp.reply, now)
    val sb = resp.finish(done.finish, now)
    return done.session to sb
}
