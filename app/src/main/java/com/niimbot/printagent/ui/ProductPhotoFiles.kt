package com.niimbot.printagent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.niimbot.printagent.pos.PosPhotoRules
import com.niimbot.printagent.pos.PosPhotoUpload
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ProductPhotoReadResult {
    data class Success(val upload: PosPhotoUpload, val preview: Bitmap) : ProductPhotoReadResult
    data object TooLarge : ProductPhotoReadResult
    data object Unsupported : ProductPhotoReadResult
    data object Unreadable : ProductPhotoReadResult
}

object ProductPhotoFiles {
    suspend fun read(context: Context, uri: Uri): ProductPhotoReadResult = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > PosPhotoRules.MAX_BYTES) return@withContext ProductPhotoReadResult.TooLarge
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull() ?: return@withContext ProductPhotoReadResult.Unreadable
        if (!PosPhotoRules.isWithinSizeLimit(bytes)) return@withContext ProductPhotoReadResult.Unreadable
        val mediaType = PosPhotoRules.detectMediaType(bytes)
            ?: return@withContext ProductPhotoReadResult.Unsupported
        val originalName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val preview = decodePreview(bytes) ?: return@withContext ProductPhotoReadResult.Unsupported
        val convertedBytes = encodeSquare(preview, mediaType)
            ?: return@withContext ProductPhotoReadResult.Unsupported
        if (!PosPhotoRules.isWithinSizeLimit(convertedBytes)) {
            return@withContext ProductPhotoReadResult.TooLarge
        }
        ProductPhotoReadResult.Success(
            PosPhotoUpload(
                convertedBytes,
                mediaType,
                PosPhotoRules.normalizedFileName(originalName, mediaType)
            ),
            preview
        )
    }

    fun decodePreview(bytes: ByteArray, targetSize: Int = PosPhotoRules.OUTPUT_SIDE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetSize * 2 || bounds.outHeight / sampleSize > targetSize * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val crop = PosPhotoRules.centeredSquareCrop(decoded.width, decoded.height) ?: return null
        val square = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.size, crop.size)
        return if (square.width > targetSize) {
            Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
        } else {
            square
        }
    }

    @Suppress("DEPRECATION")
    private fun encodeSquare(bitmap: Bitmap, mediaType: String): ByteArray? {
        val format = when (mediaType) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP
            else -> return null
        }
        val quality = if (mediaType == "image/png") 100 else 90
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(format, quality, output)) return null
            output.toByteArray()
        }
    }
}
