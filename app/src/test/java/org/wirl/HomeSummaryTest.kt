package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryTest {
    @Test fun everythingSetUpNeedsNoAttention() {
        val status = SetupChecklist.Item.entries.associateWith { true }
        assertEquals(0, HomeSummary.needingAttention(status))
    }

    @Test fun eachMissingItemCountsOnce() {
        val status = SetupChecklist.Item.entries.associateWith { it != SetupChecklist.Item.API_KEY && it != SetupChecklist.Item.MICROPHONE }
        assertEquals(2, HomeSummary.needingAttention(status))
    }
}
