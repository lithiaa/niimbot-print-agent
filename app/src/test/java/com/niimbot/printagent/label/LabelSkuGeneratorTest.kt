package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSkuGeneratorTest {
    @Test
    fun `generated SKU contains compact product name and suffix`() {
        assertEquals("KOPISUS-A1B2", LabelSkuGenerator.generate("Kopi Susu Gula Aren", "a1b2"))
    }

    @Test
    fun `generated SKU removes accents and symbols`() {
        val sku = LabelSkuGenerator.generate("Crème brûlée 250g!", "z-9")

        assertTrue(sku.startsWith("CREMEBRUL-"))
        assertTrue(sku.endsWith("Z9"))
        assertTrue(sku.length <= LabelSkuGenerator.MAX_LENGTH)
    }

    @Test
    fun `generated SKU never exceeds twelve characters`() {
        val sku = LabelSkuGenerator.generate("Nama barang yang sangat panjang sekali", "abcd")

        assertEquals(LabelSkuGenerator.MAX_LENGTH, sku.length)
        assertEquals("NAMABAR-ABCD", sku)
    }
}
