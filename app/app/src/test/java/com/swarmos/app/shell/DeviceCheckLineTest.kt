package com.swarmos.app.shell

import com.swarmos.app.core.DeviceFixtures
import com.swarmos.app.core.DeviceProfile
import com.swarmos.app.core.DeviceState
import com.swarmos.app.core.GpuProbe
import com.swarmos.app.core.ProbeVerdict
import com.swarmos.app.core.ProfilePlan
import com.swarmos.app.llm.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCheckLineTest {
    private fun line(p: DeviceProfile) = deviceCheckLine(DeviceState(p, ProfilePlan.plan(p, ModelCatalog.entries)), checking = false)

    @Test fun theIqoo15ReadsAsTheExampleOfTheBrief() {
        assertEquals("16 GB memory, GPU ready: runs the Qwen3.5-4B model and can lend memory.", line(DeviceFixtures.iqoo15))
    }

    @Test fun aPhoneWhoseProbeSawNoGpuButHasTheLibraryIsToldItWillBeTried() {
        val p = DeviceFixtures.phone8gb.copy(gpuProbe = GpuProbe(ProbeVerdict.NO_GPU, "none"))
        assertEquals("8 GB memory, GPU not confirmed, will be tried: runs the Qwen3.5-4B model and can lend memory.", line(p))
    }

    @Test fun aPhoneWhoseProbeSawNoGpuAndHasNoLibraryIsToldItIsNotUsable() {
        val p = DeviceFixtures.phone8gb.copy(openClPresent = false, gpuProbe = GpuProbe(ProbeVerdict.NO_GPU, "none"))
        assertEquals("8 GB memory, GPU not usable: runs the Qwen3.5-4B model and can lend memory.", line(p))
    }

    @Test fun aDesktopWithoutAGpuBuildSaysNoGpu() {
        assertEquals("32 GB memory, no GPU: runs the Qwen3.5-4B model and can lend memory.", line(DeviceFixtures.desktop32))
    }

    @Test fun aPhoneWithTooLittleMemoryForChatTellsWhatItWillDoInstead() {
        assertEquals(
            "4 GB memory: too little for a chat model, so this phone asks the other phones to compute and still stores and searches files.",
            line(DeviceFixtures.phone4gb),
        )
    }

    @Test fun aPhoneTooSmallEvenForTheSearchModelSaysSo() {
        assertEquals("3 GB memory: too little to run any model here, so this phone uses the other phones and stores files.", line(DeviceFixtures.phone2gb))
    }

    @Test fun beforeTheCheckHasAnswered() {
        assertEquals("Checking this phone: memory, processor and GPU.", deviceCheckLine(null, checking = true))
        assertEquals("Not checked yet.", deviceCheckLine(null, checking = false))
    }
}
