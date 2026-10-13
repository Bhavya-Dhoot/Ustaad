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

    /** Sync the member list, add and remove members, issue token clients. */
    MANAGE_MEMBERS,
}

object Permissions {
    /**
     * May a member of [kind] ask for [action]? [local] is a property of the link the request arrived on, decided by the
     * transport (never by anything in the request). A client or browser may use the cluster and never computes for it;
     * the three "for a peer" actions need a node at both ends, so a sender also asks this about the target's kind before
     * it dispatches anything.
     */
    fun allowed(kind: MemberKind, action: Action, local: Boolean): Boolean = when (action) {
        Action.READ_STATUS, Action.SUBMIT_TASK, Action.ASK_QUESTION, Action.USE_FILES -> true
        Action.WORK_FOR_PEER, Action.LEND_MEMORY, Action.STORE_FOR_PEER -> kind == MemberKind.NODE && local
        Action.MANAGE_MEMBERS -> kind == MemberKind.NODE
    }
}
