package com.niimbot.printagent.pos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosPhotoRulesTest {
    @Test
    fun `detects supported image signatures`() {
        assertEquals("image/jpeg", PosPhotoRules.detectMediaType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(
            "image/png",
            PosPhotoRules.detectMediaType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        )
        assertEquals("image/webp", PosPhotoRules.detectMediaType("RIFF0000WEBP".encodeToByteArray()))
        assertNull(PosPhotoRules.detectMediaType("not an image".encodeToByteArray()))
    }

    @Test
    fun `enforces documented five MiB limit`() {
        assertFalse(PosPhotoRules.isWithinSizeLimit(byteArrayOf()))
        assertTrue(PosPhotoRules.isWithinSizeLimit(ByteArray(PosPhotoRules.MAX_BYTES)))
        assertFalse(PosPhotoRules.isWithinSizeLimit(ByteArray(PosPhotoRules.MAX_BYTES + 1)))
    }

    @Test
    fun `normalizes upload filename from detected media type`() {
        assertEquals("filter_udara.jpg", PosPhotoRules.normalizedFileName("filter udara.png", "image/jpeg"))
        assertEquals("foto-barang.webp", PosPhotoRules.normalizedFileName(null, "image/webp"))
    }

    @Test
    fun `center crop produces one-to-one bounds for landscape portrait and square images`() {
        assertEquals(PosPhotoRules.SquareCrop(300, 0, 600), PosPhotoRules.centeredSquareCrop(1200, 600))
        assertEquals(PosPhotoRules.SquareCrop(0, 300, 600), PosPhotoRules.centeredSquareCrop(600, 1200))
        assertEquals(PosPhotoRules.SquareCrop(0, 0, 800), PosPhotoRules.centeredSquareCrop(800, 800))
        assertNull(PosPhotoRules.centeredSquareCrop(0, 800))
    }
}
