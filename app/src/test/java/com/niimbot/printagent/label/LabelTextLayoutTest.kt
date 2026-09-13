package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelTextLayoutTest {
    private val monospaceMeasure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun shortProductNameStaysOnOneLine() {
        assertEquals(
            listOf("Kopi Arabika"),
            wrapTextAtMostTwoLines("Kopi Arabika", 20f, monospaceMeasure)
        )
    }

    @Test
    fun longProductNameUsesTwoBalancedLines() {
        assertEquals(
            listOf("Kopi Arabika", "Premium 250 Gram"),
            wrapTextAtMostTwoLines("Kopi Arabika Premium 250 Gram", 18f, monospaceMeasure)
        )
    }

    @Test
    fun longWordIsSplitIntoNoMoreThanTwoLines() {
        val lines = wrapTextAtMostTwoLines("SUPERPANJANGSEKALI", 8f, monospaceMeasure)

        assertEquals(2, lines.size)
        assertEquals("SUPERPANJANGSEKALI", lines.joinToString(""))
    }
}
