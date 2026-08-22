package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFormRulesTest {

    @Test
    fun `manual SKU name numeric prices and positive qty are required`() {
        val result = LabelFormRules.validate(
            LabelFormInput(
                sku = "  ",
                nama = "",
                hargaBeli = "abc",
                hargaJual = "",
                qty = "0"
            )
        )

        assertEquals(
            setOf(
                LabelField.SKU,
                LabelField.NAMA,
                LabelField.HARGA_BELI,
                LabelField.HARGA_JUAL,
                LabelField.QTY
            ),
            result.errors.keys
        )
    }

    @Test
    fun `valid form is converted to normalized label data`() {
        val result = LabelFormRules.validate(
            LabelFormInput(
                sku = " ab-12 ",
                nama = "  Kopi Susu  ",
                hargaBeli = "12000",
                hargaJual = "18000",
                qty = "2"
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(
            LabelData("AB-12", "Kopi Susu", 12000L, 18000L, 2),
            result.data
        )
    }

    @Test
    fun `negative prices are rejected`() {
        val result = LabelFormRules.validate(
            LabelFormInput("SKU-1", "Barang", "-1", "-2", "1")
        )

        assertEquals(setOf(LabelField.HARGA_BELI, LabelField.HARGA_JUAL), result.errors.keys)
    }
}
