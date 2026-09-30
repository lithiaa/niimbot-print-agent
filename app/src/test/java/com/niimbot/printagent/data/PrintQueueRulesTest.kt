package com.niimbot.printagent.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintQueueRulesTest {
    @Test
    fun `only waiting print jobs can be cancelled`() {
        assertTrue(PrintQueueRules.canCancel(PrintStatus.PENDING))
        assertFalse(PrintQueueRules.canCancel(PrintStatus.PRINTING))
        assertFalse(PrintQueueRules.canCancel(PrintStatus.DONE))
        assertFalse(PrintQueueRules.canCancel(PrintStatus.FAILED))
        assertFalse(PrintQueueRules.canCancel(PrintStatus.CANCELLED))
    }
}
