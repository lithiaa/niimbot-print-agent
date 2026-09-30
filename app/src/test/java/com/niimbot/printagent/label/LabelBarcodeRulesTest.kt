package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelBarcodeRulesTest {
    @Test
    fun `printable ASCII content remains unchanged`() {
        val content = "SKU-12 A_B/3"

        assertTrue(LabelBarcodeRules.isCode128Compatible(content))
        assertEquals(content, LabelBarcodeRules.safeCode128Content(content))
    }

    @Test
    fun `emoji content uses safe preview fallback`() {
        assertFalse(LabelBarcodeRules.isCode128Compatible("SKU-🧾-12"))
        assertEquals("000000", LabelBarcodeRules.safeCode128Content("SKU-🧾-12"))
    }

    @Test
    fun `empty and control character content use safe preview fallback`() {
        assertEquals("000000", LabelBarcodeRules.safeCode128Content(""))
        assertEquals("000000", LabelBarcodeRules.safeCode128Content("SKU\n12"))
    }
}
