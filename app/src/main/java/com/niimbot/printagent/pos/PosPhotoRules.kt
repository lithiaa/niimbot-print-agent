package com.niimbot.printagent.pos

object PosPhotoRules {
    const val MAX_BYTES: Int = 5 * 1024 * 1024
    const val OUTPUT_SIDE_PX: Int = 1024

    data class SquareCrop(val left: Int, val top: Int, val size: Int)

    fun centeredSquareCrop(width: Int, height: Int): SquareCrop? {
        if (width <= 0 || height <= 0) return null
        val size = minOf(width, height)
        return SquareCrop(
            left = (width - size) / 2,
            top = (height - size) / 2,
            size = size
        )
    }

    fun detectMediaType(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> "image/png"
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> null
    }

    fun isWithinSizeLimit(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.size <= MAX_BYTES

    fun normalizedFileName(originalName: String?, mediaType: String): String {
        val extension = when (mediaType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "webp"
        }
        val original = originalName.orEmpty()
        val base = original
            .substringBeforeLast('.', original)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
            .take(80)
            .ifEmpty { "foto-barang" }
        return "$base.$extension"
    }
}
