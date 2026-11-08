package com.niimbot.printagent.pos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosSupplierOrderRulesTest {
    @Test
    fun `name must not be blank or longer than backend limit`() {
        assertNull(PosSupplierOrderRules.normalizeName("   "))
        assertNull(PosSupplierOrderRules.normalizeName("x".repeat(201)))
        assertEquals("Kabel USB", PosSupplierOrderRules.normalizeName("  Kabel USB  "))
    }

    @Test
    fun `quantity must be a positive integer`() {
        assertNull(PosSupplierOrderRules.parsePositiveQuantity(""))
        assertNull(PosSupplierOrderRules.parsePositiveQuantity("0"))
        assertNull(PosSupplierOrderRules.parsePositiveQuantity("-2"))
        assertNull(PosSupplierOrderRules.parsePositiveQuantity("1.5"))
        assertEquals(12, PosSupplierOrderRules.parsePositiveQuantity(" 12 "))
    }

    @Test
    fun `status accepts exactly the four API values`() {
        assertEquals("pending", PosSupplierOrderRules.normalizeStatus("PENDING"))
        assertEquals("dipesan", PosSupplierOrderRules.normalizeStatus("dipesan"))
        assertEquals("diterima", PosSupplierOrderRules.normalizeStatus("diterima"))
        assertEquals("dibatalkan", PosSupplierOrderRules.normalizeStatus("dibatalkan"))
        assertNull(PosSupplierOrderRules.normalizeStatus("selesai"))
    }

    @Test
    fun `search filters by name case insensitively`() {
        val order = PosSupplierOrder(1, "Kabel USB Type C", 3, "pending")

        assertTrue(PosSupplierOrderRules.matchesName(order, "usb"))
        assertTrue(PosSupplierOrderRules.matchesName(order, "  TYPE c "))
        assertTrue(PosSupplierOrderRules.matchesName(order, ""))
        assertFalse(PosSupplierOrderRules.matchesName(order, "charger"))
    }
}
