package com.niimbot.printagent.ui

import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosSupplier
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelPrefillContractTest {

    @Test
    fun `product data is mapped into the label form`() {
        val product = PosProduct(
            sku = " sku-1 ",
            nama = "Kopi Susu",
            hargaBeli = 12_000,
            hargaJual = 18_000,
            hargaBeliKode = "KB12",
            supplier = PosSupplier(
                id = 7,
                namaSupplier = "Supplier Utama",
                kodeSupplier = "SUP-7"
            ),
            createdAt = "2026-09-04T08:30:00Z"
        )

        val result = LabelPrefillContract.fromProduct(product)

        assertEquals("SKU-1", result.sku)
        assertEquals("Kopi Susu", result.name)
        assertEquals(12_000, result.purchasePrice)
        assertEquals(18_000, result.salePrice)
        assertEquals("KB12", result.purchasePriceCode)
        assertEquals(7L, result.supplierId)
        assertEquals("SUP-7", result.supplierCode)
        assertEquals("Supplier Utama - SUP-7", result.supplierDisplay)
        assertEquals(product.createdAt, result.createdAt)
    }
}
