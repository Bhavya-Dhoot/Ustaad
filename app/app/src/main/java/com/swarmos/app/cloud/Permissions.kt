package com.swarmos.app.cloud

/** What a request asks a device to do (docs/CLOUD-PROTOCOL.md section 9). */
enum class Action {
    /** Hello, health, capacity and the member list. */
    READ_STATUS,

    /** Chat, batch or Choir run: the receiver decides where it runs. */
    SUBMIT_TASK,

    /** Federated question over files. */
    ASK_QUESTION,

    /** List, read, write, delete in the cloud store. */
    USE_FILES,

    /** Run a unit of work that a peer's scheduler dispatched, and report its result. */
    WORK_FOR_PEER,

    /** MemoryCloud loan. */
    LEND_MEMORY,

    /** Hold chunks of other members' files. */
    STORE_FOR_PEER,

    /** Carry a join or removal request to the owner, pass the chain on (push). Writing the list is not an action: only the owner key writes. */
    MANAGE_MEMBERS,

    /**
     * Read-only synchronisation: pull the chain, pull the secrets of an epoch the asker is a member of. It changes nothing in the
     * list, so a client with a device key may do it (a client back after two rotations must be able to leave the resync path,
     * Codex section 19.9); a token client and a browser may not.
     */
    SYNC_READ,
}

object Permissions {
    /**
     * May a member of [kind] ask for [action]? [local] is a property of the link the request arrived on, [sealed] says the request
     * came in a session (or a token session), [loopback] that it came from the loopback address; all three are decided by the
     * transport, never by anything in the request. **An unsealed request is refused unless it came from loopback and its sender is a
     * client or browser (a token client in loopback mode).** A client or browser may use the cluster and never computes for it; the
     * three "for a peer" actions need a node at both ends, so a sender also asks this about the target's kind before it dispatches.
     * [deviceKey] is false for a token client (a session made with a token key); only [Action.SYNC_READ] looks at it.
     */
    fun allowed(kind: MemberKind, action: Action, local: Boolean, sealed: Boolean = true, loopback: Boolean = false, deviceKey: Boolean = true): Boolean {
        if (!sealed && !(loopback && kind != MemberKind.NODE)) return false
        return when (action) {
            Action.READ_STATUS, Action.SUBMIT_TASK, Action.ASK_QUESTION, Action.USE_FILES -> true
            Action.WORK_FOR_PEER, Action.LEND_MEMORY, Action.STORE_FOR_PEER -> kind == MemberKind.NODE && local
            Action.MANAGE_MEMBERS -> kind == MemberKind.NODE
            Action.SYNC_READ -> deviceKey && kind != MemberKind.BROWSER
        }
    }
}
