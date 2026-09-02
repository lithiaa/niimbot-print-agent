package com.niimbot.printagent.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class XPrinterRasterTest {

    @Test
    fun `black pixels clear bits while white pixels stay set`() {
        val raster = encodeXPrinterRaster(width = 8, height = 1, bytesPerRow = 1) { x, _ ->
            x == 0 || x == 7
        }

        assertArrayEquals(byteArrayOf(0x7E), raster)
    }

    @Test
    fun `padding bits remain white`() {
        val raster = encodeXPrinterRaster(width = 3, height = 1, bytesPerRow = 1) { x, _ ->
            x == 1
        }

        assertArrayEquals(byteArrayOf(0xBF.toByte()), raster)
    }
}
