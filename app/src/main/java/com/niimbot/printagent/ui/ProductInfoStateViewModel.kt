package com.niimbot.printagent.ui

import androidx.lifecycle.ViewModel
import com.niimbot.printagent.pos.PosProduct

internal class ProductInfoStateViewModel : ViewModel() {
    var query: String = ""
    var currentPage: Int = 1
    var currentPageSize: Int = 20
    var totalProducts: Int = 0
    var selectedFilter: ProductStockFilter = ProductStockFilter.ALL
    var selectedSort: ProductSort = ProductSort.NAME_ASC
    var products: List<PosProduct> = emptyList()
    var firstVisiblePosition: Int = 0
    var firstVisibleOffset: Int = 0
    var initialized: Boolean = false
}
