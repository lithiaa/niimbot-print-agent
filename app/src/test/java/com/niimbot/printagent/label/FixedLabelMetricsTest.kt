package com.niimbot.printagent.label

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedLabelMetricsTest {
    @Test
    fun `50x30 and 30x20 use independently positioned fixed designs`() {
        val large = FixedLabelMetrics.forSize(LabelSize.MM_50_X_30)
        val small = FixedLabelMetrics.forSize(LabelSize.MM_30_X_20)

        assertNotEquals(large, small)
        assertTrue(small.barcode.top < large.barcode.top)
        assertTrue(small.productName.top < large.productName.top)
    }

    @Test
    fun `every fixed design frame remains inside the label`() {
        listOf(LabelSize.MM_50_X_30, LabelSize.MM_30_X_20).forEach { size ->
            val metrics = FixedLabelMetrics.forSize(size)
            listOf(metrics.barcode, metrics.metadata, metrics.productName, metrics.price, metrics.brand)
                .forEach { frame ->
                    assertTrue(frame.left >= 0f)
                    assertTrue(frame.top >= 0f)
                    assertTrue(frame.right <= 1f)
                    assertTrue(frame.bottom <= 1f)
                    assertTrue(frame.left < frame.right)
                    assertTrue(frame.top < frame.bottom)
                }
        }
    }

    @Test
    fun `product name reserves two near-original-height lines without overlap`() {
        listOf(LabelSize.MM_50_X_30, LabelSize.MM_30_X_20).forEach { size ->
            val metrics = FixedLabelMetrics.forSize(size)
            val productHeight = metrics.productName.bottom - metrics.productName.top
            val lineHeight = productHeight * .96f / 2f

            assertTrue(productHeight >= .279f)
            assertTrue(lineHeight >= .134f)
            assertTrue(metrics.metadata.bottom < metrics.productName.top)
            assertTrue(metrics.productName.bottom < metrics.price.top)
        }
    }

    @Test
    fun `qr design keeps code separate from readable text content`() {
        listOf(LabelSize.MM_50_X_30, LabelSize.MM_30_X_20).forEach { size ->
            val metrics = QrLabelMetrics.forSize(size)
            val frames = listOf(
                metrics.qrCode,
                metrics.productName,
                metrics.price,
                metrics.metadata,
                metrics.brand
            )

            frames.forEach { frame ->
                assertTrue(frame.left >= 0f)
                assertTrue(frame.top >= 0f)
                assertTrue(frame.right <= 1f)
                assertTrue(frame.bottom <= 1f)
                assertTrue(frame.left < frame.right)
                assertTrue(frame.top < frame.bottom)
            }
            assertTrue(metrics.qrCode.right < metrics.productName.left)
            assertTrue(metrics.productName.bottom < metrics.price.top)
            assertTrue(metrics.price.bottom < metrics.metadata.top)
            assertTrue(metrics.productName.bottom - metrics.productName.top >= .299f)
        }
    }
}
