package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelDateTest {
    @Test
    fun `timestamp is normalized and displayed for Indonesian labels`() {
        assertEquals("2026-08-31", LabelDate.fromTimestamp("2026-08-31T14:15:00Z"))
        assertEquals("31/08/2026", LabelDate.display("2026-08-31"))
        assertEquals("31/08/2026", LabelGenerator.entryDateText("2026-08-31"))
    }

    @Test
    fun `invalid calendar date is rejected`() {
        assertFalse(LabelDate.isValid("2026-02-30"))
        assertTrue(LabelDate.display("2026-02-30") == null)
    }
}
