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
                qty = "0",
                jumlahBarangMasuk = "not-an-integer",
                addToPos = true
            )
        )

        assertEquals(
            setOf(
                LabelField.SKU,
                LabelField.NAMA,
                LabelField.HARGA_BELI,
                LabelField.HARGA_JUAL,
                LabelField.QTY,
                LabelField.JUMLAH_BARANG_MASUK
            ),
            result.errors.keys
        )
        assertEquals("Jumlah label minimal 1", result.errors[LabelField.QTY])
        assertEquals(
            "Jumlah barang masuk untuk stok POS harus berupa bilangan bulat nol atau lebih",
            result.errors[LabelField.JUMLAH_BARANG_MASUK]
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
                qty = "2",
                jumlahBarangMasuk = "7",
                addToPos = true
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(
            LabelData("AB-12", "Kopi Susu", 12000L, 18000L, 2, 7),
            result.data
        )
    }

    @Test
    fun `negative prices are rejected`() {
        val result = LabelFormRules.validate(
            LabelFormInput("SKU-1", "Barang", "-1", "-2", "1", "0", true)
        )

        assertEquals(setOf(LabelField.HARGA_BELI, LabelField.HARGA_JUAL), result.errors.keys)
    }

    @Test
    fun `incoming stock is distinct from label copies and zero is valid when POS is on`() {
        val result = LabelFormRules.validate(
            LabelFormInput("SKU-1", "Barang", "10", "12", "4", "0", true)
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(4, result.data?.qty)
        assertEquals(0, result.data?.jumlahBarangMasuk)
    }

    @Test
    fun `incoming stock must be a non-negative integer only when POS is on`() {
        listOf("", "-1", "1.5", "abc").forEach { incoming ->
            val result = LabelFormRules.validate(
                LabelFormInput("SKU-1", "Barang", "10", "12", "1", incoming, true)
            )

            assertEquals(
                setOf(LabelField.JUMLAH_BARANG_MASUK),
                result.errors.keys
            )
        }
    }

    @Test
    fun `POS off ignores malformed incoming stock and normalizes it to zero`() {
        val result = LabelFormRules.validate(
            LabelFormInput("SKU-1", "Barang", "10", "12", "3", "not-a-number", false)
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(3, result.data?.qty)
        assertEquals(0, result.data?.jumlahBarangMasuk)
    }
}
