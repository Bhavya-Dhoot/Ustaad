package com.swarmos.app.link

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlderAndroidLinkTest {
    @Test fun theLowLatencyWifiLockIsAskedForOnlyFromAndroid10() {
        // WIFI_MODE_FULL_LOW_LATENCY is API 29; below it the high-performance lock is the one that can be held
        for (sdk in 26..28) assertEquals("sdk $sdk", LockMode.HIGH_PERF, WifiLocks.lockModeFor(LockMode.LOW_LATENCY, sdk))
        for (sdk in listOf(29, 30, 31, 34, 36)) assertEquals("sdk $sdk", LockMode.LOW_LATENCY, WifiLocks.lockModeFor(LockMode.LOW_LATENCY, sdk))
    }

    @Test fun theOtherLockModesAreTheSameOnEveryVersion() {
        for (sdk in listOf(26, 28, 29, 31, 36)) {
            assertEquals(LockMode.NONE, WifiLocks.lockModeFor(LockMode.NONE, sdk))
            assertEquals(LockMode.HIGH_PERF, WifiLocks.lockModeFor(LockMode.HIGH_PERF, sdk))
        }
    }

    @Test fun aWifiDirectGroupByNameIsRefusedBeforeAndroid10WithAPlainMessage() = runBlocking {
        // the stub android.jar of the unit tests reports SDK_INT 0, which is below 29: the refusal comes before anything touches Android
        assertEquals(0, android.os.Build.VERSION.SDK_INT)
        val host = runCatching { DirectLink.host() }.exceptionOrNull()
        assertNotNull(host)
        assertTrue(host is P2pException)
        assertTrue(host!!.message!!, "Android 10" in host.message!! && "current Wi-Fi" in host.message!!)
        val join = runCatching { DirectLink.join(P2pCreds("DIRECT-xy-test", "pass")) }.exceptionOrNull()
        assertTrue(join is P2pException)
        assertEquals("the host's state is untouched by the refusal", "idle", DirectLink.state.value.phase)
    }
}
