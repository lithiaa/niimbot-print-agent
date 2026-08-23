package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
import org.junit.Assert.assertEquals
import org.junit.Test

class PosProductRulesTest {

    @Test
    fun `SKU lookup normalization trims and uppercases`() {
        assertEquals("ABC-123", PosProductRules.normalizeSku("  abc-123  "))
    }

    @Test
    fun `base URL normalization removes every trailing slash`() {
        assertEquals(
            "https://api.ijm.lithiaproject.site",
            PosProductRules.normalizeBaseUrl(" https://api.ijm.lithiaproject.site/// ")
        )
    }

    @Test
    fun `existing product with exact editable data prints without dialog`() {
        val form = LabelData("ABC-1", "Gula", 10_000L, 12_000L, 3, 5)
        val product = PosProduct(
            id = 7L,
            sku = "abc-1",
            nama = "Gula",
            hargaBeli = 10_000L,
            hargaJual = 12_000L,
            hargaBeliKode = "APP",
            stok = 99,
            satuan = "pcs"
        )

        assertEquals(PosLookupDecision.PRINT_POS, PosProductRules.decideExisting(form, product))
    }

    @Test
    fun `different existing editable data requires conflict choice`() {
        val form = LabelData("ABC-1", "Gula Premium", 10_000L, 13_000L, 1, 5)
        val product = PosProduct(
            id = 7L,
            sku = "ABC-1",
            nama = "Gula",
            hargaBeli = 10_000L,
            hargaJual = 12_000L,
            hargaBeliKode = null,
            stok = 2,
            satuan = "pak"
        )

        assertEquals(PosLookupDecision.SHOW_CONFLICT, PosProductRules.decideExisting(form, product))
    }

    @Test
    fun `qty stock unit id and purchase code do not make editable data different`() {
        val form = LabelData("SKU-9", "Barang", 500L, 700L, 8, 12)
        val product = PosProduct(
            id = 999L,
            sku = "SKU-9",
            nama = "Barang",
            hargaBeli = 500L,
            hargaJual = 700L,
            hargaBeliKode = "UP",
            stok = 0,
            satuan = "dus"
        )

        assertEquals(PosLookupDecision.PRINT_POS, PosProductRules.decideExisting(form, product))
    }
}
