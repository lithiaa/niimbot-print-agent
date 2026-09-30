package com.niimbot.printagent.ui

internal object DashboardStockListRules {
    const val MAX_VISIBLE_ITEMS = 5

    fun <T> visibleItems(items: List<T>): List<T> = items.take(MAX_VISIBLE_ITEMS)
}
