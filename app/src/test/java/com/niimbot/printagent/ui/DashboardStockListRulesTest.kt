package com.niimbot.printagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardStockListRulesTest {

    @Test
    fun `dashboard limits stock warning rows to avoid inflating the full response`() {
        val items = (1..121).toList()

        val visible = DashboardStockListRules.visibleItems(items)

        assertEquals(5, visible.size)
        assertEquals(listOf(1, 2, 3, 4, 5), visible)
    }

    @Test
    fun `dashboard keeps every stock warning when response is already small`() {
        val items = listOf("A", "B", "C")

        assertEquals(items, DashboardStockListRules.visibleItems(items))
    }
}
