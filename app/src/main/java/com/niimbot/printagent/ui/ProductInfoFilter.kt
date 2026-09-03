package com.niimbot.printagent.ui

import com.niimbot.printagent.pos.PosProduct

internal enum class ProductStockFilter {
    ALL,
    SAFE,
    LOW,
    OUT_OF_STOCK
}

internal object ProductInfoFilter {
    fun apply(products: List<PosProduct>, filter: ProductStockFilter): List<PosProduct> =
        when (filter) {
            ProductStockFilter.ALL -> products
            ProductStockFilter.SAFE -> products.filter { product ->
                product.stok > product.stokMinimum.coerceAtLeast(0)
            }
            ProductStockFilter.LOW -> products.filter { product ->
                product.stok > 0 &&
                    product.stokMinimum > 0 &&
                    product.stok <= product.stokMinimum
            }
            ProductStockFilter.OUT_OF_STOCK -> products.filter { it.stok <= 0 }
        }
}
