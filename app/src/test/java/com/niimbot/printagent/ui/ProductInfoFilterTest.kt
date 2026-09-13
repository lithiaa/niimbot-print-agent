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

    @Test
    fun `stock filters map to backend values`() {
        assertEquals(null, ProductStockFilter.ALL.apiValue)
        assertEquals("aman", ProductStockFilter.SAFE.apiValue)
        assertEquals("menipis", ProductStockFilter.LOW.apiValue)
        assertEquals("habis", ProductStockFilter.OUT_OF_STOCK.apiValue)
    }

    @Test
    fun `sort supports name stock and selling price in both directions`() {
        val unordered = listOf(
            product("B", stock = 5, minimum = 1, name = "Beta", sellingPrice = 300),
            product("C", stock = 1, minimum = 1, name = "Charlie", sellingPrice = 200),
            product("A", stock = 9, minimum = 1, name = "Alpha", sellingPrice = 100)
        )

        assertEquals(listOf("A", "B", "C"), sortedSkus(unordered, ProductSort.NAME_ASC))
        assertEquals(listOf("C", "B", "A"), sortedSkus(unordered, ProductSort.NAME_DESC))
        assertEquals(listOf("C", "B", "A"), sortedSkus(unordered, ProductSort.STOCK_ASC))
        assertEquals(listOf("A", "B", "C"), sortedSkus(unordered, ProductSort.STOCK_DESC))
        assertEquals(listOf("A", "C", "B"), sortedSkus(unordered, ProductSort.SELLING_PRICE_ASC))
        assertEquals(listOf("B", "C", "A"), sortedSkus(unordered, ProductSort.SELLING_PRICE_DESC))
    }

    private fun filteredSkus(filter: ProductStockFilter): List<String> =
        ProductInfoFilter.apply(products, filter).map { it.sku }

    private fun sortedSkus(items: List<PosProduct>, sort: ProductSort): List<String> =
        ProductInfoSort.apply(items, sort).map { it.sku }

    private fun product(
        sku: String,
        stock: Int,
        minimum: Int,
        name: String = sku,
        sellingPrice: Long = 0
    ) = PosProduct(
        sku = sku,
        nama = name,
        hargaBeli = 0,
        hargaJual = sellingPrice,
        stok = stock,
        stokMinimum = minimum
    )
}
