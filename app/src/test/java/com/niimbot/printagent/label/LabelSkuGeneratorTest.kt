package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSkuGeneratorTest {
    @Test
    fun `generated SKU contains normalized product name and suffix`() {
        assertEquals("KOPI-SUSU-GULA-A1B2", LabelSkuGenerator.generate("Kopi Susu Gula Aren", "a1b2"))
    }

    @Test
    fun `generated SKU removes accents and symbols`() {
        val sku = LabelSkuGenerator.generate("Crème brûlée 250g!", "z-9")

        assertTrue(sku.startsWith("CREME-BRULEE-250G-"))
        assertTrue(sku.endsWith("Z9"))
    }
}
