package com.swarmos.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.fail

internal const val T0 = 1_800_000_000_000L

/** Expects a [ProtocolError] with exactly this code. */
internal fun assertError(expected: CloudError, block: () -> Unit) {
    try {
        block()
    } catch (e: ProtocolError) {
        assertEquals(expected, e.error)
        return
    }
    fail("expected ${expected.code}")
}
