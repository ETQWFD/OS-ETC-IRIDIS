package com.iris.localai.v203

import com.iris.localai.engine.CpuTopology
import com.iris.localai.engine.ResourceBackendKind
import com.iris.localai.engine.ResourceTier
import com.iris.localai.data.TierProfile
import com.iris.localai.engine.TierProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class V203SpeedTest {
    private val elite = CpuTopology(listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7)))

    @Test fun highTierUsesSixThreadsOnElite() {
        assertEquals(TierProfile(6, listOf(2, 3, 4, 5, 6, 7), -4), TierProfiles.defaults(ResourceBackendKind.CPU, ResourceTier.HIGH, elite))
        // 省电 / 中 unchanged
        assertEquals(TierProfile(1, listOf(5), 10), TierProfiles.defaults(ResourceBackendKind.CPU, ResourceTier.LOW, elite))
    }
}
