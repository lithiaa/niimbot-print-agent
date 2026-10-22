package com.niimbot.printagent.label

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlin.math.min
import kotlin.math.roundToInt

object ShippingLabelPdf {
    const val WIDTH_MM = 100
    const val HEIGHT_MM = 150
    const val PREVIEW_DPI = 135
    const val MAX_PAGE_COUNT = 100

    fun targetPixels(dpi: Int): PixelSize {
        require(dpi > 0) { "DPI harus lebih dari nol" }
        return PixelSize(
            width = (WIDTH_MM * dpi / 25.4f).roundToInt().coerceAtLeast(1),
            height = (HEIGHT_MM * dpi / 25.4f).roundToInt().coerceAtLeast(1)
        )
    }

    fun fittedPixels(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): PixelSize {
        require(sourceWidth > 0 && sourceHeight > 0) { "Ukuran halaman PDF tidak valid" }
        require(targetWidth > 0 && targetHeight > 0) { "Ukuran target tidak valid" }
        val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
        return PixelSize(
            width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        )
    }

    fun validatePageCount(pageCount: Int) {
        require(pageCount in 1..MAX_PAGE_COUNT) {
            "PDF harus berisi 1 sampai $MAX_PAGE_COUNT halaman"
        }
    }
}

data class PixelSize(val width: Int, val height: Int)

/** Opens and rasterizes a shipping-label PDF without retaining a copy of the source document. */
class ShippingLabelPdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {
    val pageCount: Int = renderer.pageCount.also(ShippingLabelPdf::validatePageCount)

    fun renderPage(pageIndex: Int, dpi: Int): Bitmap {
        require(pageIndex in 0 until pageCount) { "Halaman PDF tidak tersedia" }
        val target = ShippingLabelPdf.targetPixels(dpi)

        return renderer.openPage(pageIndex).use { page ->
            val rotateToPortrait = page.width > page.height
            val orientedWidth = if (rotateToPortrait) page.height else page.width
            val orientedHeight = if (rotateToPortrait) page.width else page.height
            val fitted = ShippingLabelPdf.fittedPixels(
                sourceWidth = orientedWidth,
                sourceHeight = orientedHeight,
                targetWidth = target.width,
                targetHeight = target.height
            )
            val renderedWidth = if (rotateToPortrait) fitted.height else fitted.width
            val renderedHeight = if (rotateToPortrait) fitted.width else fitted.height
            val rendered = Bitmap.createBitmap(renderedWidth, renderedHeight, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

            val oriented = if (rotateToPortrait) {
                Bitmap.createBitmap(
                    rendered,
                    0,
                    0,
                    rendered.width,
                    rendered.height,
                    Matrix().apply { postRotate(90f) },
                    true
                ).also { rotated ->
                    if (rotated !== rendered) rendered.recycle()
                }
            } else {
                rendered
            }

            Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                val left = (target.width - oriented.width) / 2f
                val top = (target.height - oriented.height) / 2f
                canvas.drawBitmap(oriented, left, top, null)
                oriented.recycle()
            }
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        fun open(contentResolver: ContentResolver, uri: Uri): ShippingLabelPdfDocument {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: error("File PDF tidak dapat dibuka")
            return try {
                ShippingLabelPdfDocument(descriptor, PdfRenderer(descriptor))
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
        }
    }
}
