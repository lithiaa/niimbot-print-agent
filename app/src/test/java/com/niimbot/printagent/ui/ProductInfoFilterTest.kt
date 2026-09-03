package com.niimbot.printagent.ui

import com.niimbot.printagent.pos.PosProduct
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductInfoFilterTest {
    private val products = listOf(
        product("AMAN", stock = 11, minimum = 5),
        product("TIPIS", stock = 5, minimum = 5),
        product("HABIS", stock = 0, minimum = 5)
    )

    @Test
    fun `all filter keeps every loaded product`() {
        assertEquals(products, ProductInfoFilter.apply(products, ProductStockFilter.ALL))
    }

    @Test
    fun `stock filters are mutually exclusive`() {
        assertEquals(listOf("AMAN"), filteredSkus(ProductStockFilter.SAFE))
        assertEquals(listOf("TIPIS"), filteredSkus(ProductStockFilter.LOW))
        assertEquals(listOf("HABIS"), filteredSkus(ProductStockFilter.OUT_OF_STOCK))
    }

    private fun filteredSkus(filter: ProductStockFilter): List<String> =
        ProductInfoFilter.apply(products, filter).map { it.sku }

    private fun product(sku: String, stock: Int, minimum: Int) = PosProduct(
        sku = sku,
        nama = sku,
        hargaBeli = 0,
        hargaJual = 0,
        stok = stock,
        stokMinimum = minimum
    )
}
