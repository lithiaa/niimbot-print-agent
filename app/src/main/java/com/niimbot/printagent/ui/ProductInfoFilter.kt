package com.niimbot.printagent.ui

import com.niimbot.printagent.pos.PosProduct
import java.util.Locale

internal enum class ProductStockFilter(val apiValue: String?) {
    ALL(null),
    SAFE("aman"),
    LOW("menipis"),
    OUT_OF_STOCK("habis")
}

internal enum class ProductSort {
    NAME_ASC,
    NAME_DESC,
    STOCK_ASC,
    STOCK_DESC,
    SELLING_PRICE_ASC,
    SELLING_PRICE_DESC
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

internal object ProductInfoSort {
    fun apply(products: List<PosProduct>, sort: ProductSort): List<PosProduct> {
        val nameThenSku = compareBy<PosProduct>(
            { it.nama.lowercase(Locale.ROOT) },
            { it.sku.lowercase(Locale.ROOT) }
        )
        val comparator = when (sort) {
            ProductSort.NAME_ASC -> nameThenSku
            ProductSort.NAME_DESC -> nameThenSku.reversed()
            ProductSort.STOCK_ASC -> compareBy<PosProduct> { it.stok }.then(nameThenSku)
            ProductSort.STOCK_DESC -> compareByDescending<PosProduct> { it.stok }.then(nameThenSku)
            ProductSort.SELLING_PRICE_ASC -> compareBy<PosProduct> { it.hargaJual }.then(nameThenSku)
            ProductSort.SELLING_PRICE_DESC -> compareByDescending<PosProduct> { it.hargaJual }.then(nameThenSku)
        }
        return products.sortedWith(comparator)
    }
}
