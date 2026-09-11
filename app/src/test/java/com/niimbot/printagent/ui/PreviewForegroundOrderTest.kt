package com.niimbot.printagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewForegroundOrderTest {

    @Test
    fun `preview at first layout position is drawn last`() {
        assertEquals(listOf(1, 0), drawingOrder(childCount = 2, foregroundIndex = 0))
    }

    @Test
    fun `preview at second layout position is still drawn last`() {
        assertEquals(listOf(0, 1), drawingOrder(childCount = 2, foregroundIndex = 1))
    }

    private fun drawingOrder(childCount: Int, foregroundIndex: Int): List<Int> =
        (0 until childCount).map { drawingPosition ->
            PreviewForegroundOrder.childIndex(childCount, foregroundIndex, drawingPosition)
        }
}
