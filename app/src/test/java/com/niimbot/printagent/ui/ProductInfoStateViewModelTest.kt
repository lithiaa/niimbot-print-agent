package com.niimbot.printagent.ui

import com.niimbot.printagent.pos.PosProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductInfoStateViewModelTest {
    @Test
    fun `deletion refresh skips restoration while adapter is empty despite cached products`() {
        val state = loadedState()
        state.products = listOf(product())

        // The cached page can still contain the deleted item when the result
        // listener clears the adapter to reload page one.
        assertNull(state.positionToRestore(itemCount = 0))

        state.products = emptyList()
        assertNull(state.positionToRestore(itemCount = 0))
    }

    @Test
    fun `deleting last visible item restores to remaining final item`() {
        val state = loadedState()
        state.firstVisiblePosition = 19

        assertEquals(18, state.positionToRestore(itemCount = 19))
    }

    @Test
    fun `filter with no displayed products skips restoration`() {
        val state = loadedState()
        state.products = listOf(product())
        val displayed = ProductInfoFilter.apply(state.products, ProductStockFilter.SAFE)

        assertNull(state.positionToRestore(displayed.size))
    }

    @Test
    fun `returning from detail preserves valid saved position and offset`() {
        val state = loadedState()
        state.firstVisiblePosition = 7
        state.firstVisibleOffset = -24

        assertEquals(7, state.positionToRestore(itemCount = 20))
        assertEquals(-24, state.firstVisibleOffset)
    }

    @Test
    fun `initial load has no saved position to restore`() {
        assertNull(ProductInfoStateViewModel().positionToRestore(itemCount = 20))
    }

    private fun loadedState() = ProductInfoStateViewModel().apply {
        initialized = true
    }

    private fun product() = PosProduct(
        sku = "TEST-ITEM",
        nama = "Barang uji",
        hargaBeli = 0,
        hargaJual = 0,
        stok = 0
    )
}
