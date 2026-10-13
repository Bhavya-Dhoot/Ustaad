package com.swarmos.app.cloud

/** Kind of member (docs/CLOUD-PROTOCOL.md 3.2). Only a node computes for others, lends memory or hosts an invite. */
enum class MemberKind(val code: Int) {
    NODE(1), CLIENT(2), BROWSER(3);

    companion object {
        fun of(code: Int): MemberKind? = values().firstOrNull { it.code == code }
    }
}
