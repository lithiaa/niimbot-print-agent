package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShippingLabelPdfTest {
    @Test
    fun `target pixels use 100 by 150 millimeters`() {
        assertEquals(PixelSize(799, 1199), ShippingLabelPdf.targetPixels(203))
        assertEquals(PixelSize(1181, 1772), ShippingLabelPdf.targetPixels(300))
    }

    @Test
    fun `fit keeps source aspect ratio inside shipping label`() {
        assertEquals(
            PixelSize(799, 1131),
            ShippingLabelPdf.fittedPixels(595, 842, 799, 1199)
        )
    }

    @Test
    fun `page count prevents accidental oversized batches`() {
        ShippingLabelPdf.validatePageCount(1)
        ShippingLabelPdf.validatePageCount(ShippingLabelPdf.MAX_PAGE_COUNT)
        assertThrows(IllegalArgumentException::class.java) {
            ShippingLabelPdf.validatePageCount(ShippingLabelPdf.MAX_PAGE_COUNT + 1)
        }
    }
}
