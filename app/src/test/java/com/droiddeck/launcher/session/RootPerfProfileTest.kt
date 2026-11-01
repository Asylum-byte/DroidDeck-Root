package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootPerfProfileTest {
    private val table = listOf(1_200_000_000L, 1_100_000_000L, 1_000_000_000L, 900_000_000L, 826_000_000L, 700_000_000L, 500_000_000L)

    @Test
    fun capPicksTheFastestLevelAtOrBelowTheLimit() {
        assertEquals(2, RootPerfProfile.gpuCapLevel(table, 1000))
        assertEquals(4, RootPerfProfile.gpuCapLevel(table, 850))
        assertEquals(5, RootPerfProfile.gpuCapLevel(table, 700))
    }

    @Test
    fun capAboveTheTopClockLeavesTheGpuAlone() {
        assertNull(RootPerfProfile.gpuCapLevel(table, 1200))
        assertNull(RootPerfProfile.gpuCapLevel(table, 1500))
    }

    @Test
    fun capBelowEveryLevelUsesTheSlowest() {
        assertEquals(table.lastIndex, RootPerfProfile.gpuCapLevel(table, 300))
    }

    @Test
    fun missingTableMeansNoCap() {
        assertNull(RootPerfProfile.gpuCapLevel(emptyList(), 900))
    }
}
