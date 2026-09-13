package com.niimbot.printagent.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.util.EnumMap

/** Generates the selectable Lithia Project label designs for every supported size. */
object LabelGenerator {

    const val LABEL_WIDTH = 584
    const val LABEL_HEIGHT = 354
    const val DPI = LabelSize.DPI

    private const val BRAND_NAME = "Lithia Project"

    private val HARGA_DECODE_MAP = mapOf(
        '1' to 'S', '2' to 'A', '3' to 'N', '4' to 'G',
        '5' to 'U', '6' to 'O', '7' to 'E', '8' to 'R',
        '9' to 'I', '0' to 'P',
        's' to 'S', 'a' to 'A', 'n' to 'N', 'g' to 'G',
        'u' to 'U', 'o' to 'O', 'e' to 'E', 'r' to 'R',
        'i' to 'I', 'p' to 'P'
    )

    @Suppress("UNUSED_PARAMETER")
    fun generateLabel(
        nama: String,
        hargaJual: Long,
        hargaBeli: Long,
        sku: String,
        satuan: String = "pcs",
        barcodeData: String? = null,
        labelSize: LabelSize = LabelSize.MM_50_X_30,
        kodeHargaBeli: String? = null,
        itemQty: Int = 1,
        supplierCode: String? = null,
        tanggalMasuk: String? = null,
        brandLogo: Bitmap? = null,
        labelDesign: LabelDesign = LabelDesign.BARCODE
    ): Bitmap {
        val width = labelSize.widthPx
        val height = labelSize.heightPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
        }
        val barcodeContent = barcodeData?.takeIf { it.isNotBlank() } ?: sku
        val salePrice = "Rp ${formatRupiah(hargaJual)}"
        val purchaseCode = kodeHargaBeli?.trim()?.takeIf { it.isNotEmpty() }
            ?: encodePurchasePrice(hargaBeli)

        when (labelDesign) {
            LabelDesign.BARCODE -> drawBarcodeDesign(
                canvas, paint, labelSize, barcodeContent, nama, purchaseCode, salePrice,
                itemQty, tanggalMasuk, sku, supplierCode, brandLogo
            )
            LabelDesign.QR_CODE -> drawQrDesign(
                canvas, paint, labelSize, barcodeContent, nama, purchaseCode, salePrice,
                itemQty, tanggalMasuk, sku, supplierCode, brandLogo
            )
        }

        return bitmap
    }

    fun generateLabelFromJson(jsonData: Map<String, Any>): Bitmap {
        val nama = jsonData["nama"] as? String ?: "Tidak diketahui"
        val hargaJual = (jsonData["harga_jual"] as? Number
            ?: jsonData["hargaJual"] as? Number
            ?: 0L).toLong()
        val hargaBeli = (jsonData["harga_beli"] as? Number
            ?: jsonData["hargaBeli"] as? Number
            ?: 0L).toLong()
        val sku = jsonData["sku"] as? String
            ?: jsonData["kode_barang"] as? String
            ?: "000000"
        val satuan = jsonData["satuan"] as? String ?: "pcs"
        val barcodeData = jsonData["barcode"] as? String
        val labelDesign = LabelDesign.fromName(
            jsonData["labelDesign"] as? String ?: jsonData["label_layout"] as? String
        )

        return generateLabel(
            nama, hargaJual, hargaBeli, sku, satuan, barcodeData,
            labelDesign = labelDesign
        )
    }

    private fun drawBarcodeDesign(
        canvas: Canvas,
        paint: Paint,
        labelSize: LabelSize,
        barcodeContent: String,
        nama: String,
        purchaseCode: String,
        salePrice: String,
        itemQty: Int,
        tanggalMasuk: String?,
        sku: String,
        supplierCode: String?,
        brandLogo: Bitmap?
    ) {
        val width = canvas.width
        val height = canvas.height
        val metrics = FixedLabelMetrics.forSize(labelSize)
        drawBarcode(canvas, metrics.barcode, barcodeContent, width, height)
        drawMetadata(
            canvas, paint, metrics.metadata.toPixels(width, height),
            "${itemQty.coerceAtLeast(1)} QTY",
            entryDateText(tanggalMasuk).ifEmpty { sku },
            supplierCode.orEmpty()
        )
        drawProductName(
            canvas, paint, nama.trim().ifEmpty { "Nama barang" },
            metrics.productName.toPixels(width, height)
        )
        drawTextInBounds(
            canvas, paint, "$purchaseCode  $salePrice",
            metrics.price.toPixels(width, height), bold = true
        )
        drawBrand(canvas, paint, brandLogo, metrics.brand.toPixels(width, height))
    }

    private fun drawQrDesign(
        canvas: Canvas,
        paint: Paint,
        labelSize: LabelSize,
        qrContent: String,
        nama: String,
        purchaseCode: String,
        salePrice: String,
        itemQty: Int,
        tanggalMasuk: String?,
        sku: String,
        supplierCode: String?,
        brandLogo: Bitmap?
    ) {
        val width = canvas.width
        val height = canvas.height
        val metrics = QrLabelMetrics.forSize(labelSize)
        drawQrCode(canvas, metrics.qrCode.toPixels(width, height), qrContent)
        drawProductName(
            canvas, paint, nama.trim().ifEmpty { "Nama barang" },
            metrics.productName.toPixels(width, height)
        )
        drawTextInBounds(
            canvas, paint, "$purchaseCode  $salePrice",
            metrics.price.toPixels(width, height), bold = true
        )
        drawMetadata(
            canvas, paint, metrics.metadata.toPixels(width, height),
            "${itemQty.coerceAtLeast(1)} QTY",
            entryDateText(tanggalMasuk).ifEmpty { sku },
            supplierCode.orEmpty()
        )
        drawBrand(canvas, paint, brandLogo, metrics.brand.toPixels(width, height))
    }

    private fun drawBarcode(
        canvas: Canvas,
        frame: FractionalFrame,
        content: String,
        width: Int,
        height: Int
    ) {
        val bounds = frame.toPixels(width, height)
        val barcode = generateCode128(
            content,
            bounds.width().toInt().coerceAtLeast(1),
            bounds.height().toInt().coerceAtLeast(1)
        )
        val blackBounds = blackPixelBounds(barcode)
        canvas.drawBitmap(
            barcode,
            blackBounds,
            bounds,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
        )
    }

    private fun drawQrCode(canvas: Canvas, frame: RectF, content: String) {
        val side = minOf(frame.width(), frame.height()).toInt().coerceAtLeast(1)
        val qrCode = generateQrCode(content, side)
        val left = frame.centerX() - side / 2f
        val top = frame.centerY() - side / 2f
        canvas.drawBitmap(
            qrCode,
            null,
            RectF(left, top, left + side, top + side),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
        )
    }

    private fun drawMetadata(
        canvas: Canvas,
        paint: Paint,
        bounds: RectF,
        quantity: String,
        date: String,
        supplier: String
    ) {
        val sideWidth = bounds.width() * .22f
        val centerWidth = bounds.width() * .48f
        val quantityBounds = RectF(bounds.left, bounds.top, bounds.left + sideWidth, bounds.bottom)
        val dateBounds = RectF(
            bounds.centerX() - centerWidth / 2f,
            bounds.top,
            bounds.centerX() + centerWidth / 2f,
            bounds.bottom
        )
        val supplierBounds = RectF(bounds.right - sideWidth, bounds.top, bounds.right, bounds.bottom)

        drawTextInBounds(canvas, paint, quantity, quantityBounds, bold = false, alignment = TextAlignment.START)
        drawTextInBounds(canvas, paint, date, dateBounds, bold = false)
        if (supplier.isNotBlank()) {
            drawTextInBounds(canvas, paint, supplier.trim(), supplierBounds, bold = false, alignment = TextAlignment.END)
        }
    }

    private fun drawBrand(
        canvas: Canvas,
        paint: Paint,
        logo: Bitmap?,
        bounds: RectF
    ) {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true
        paint.textSize = bounds.height() * .62f

        val source = logo?.let(::opaqueBounds)
        val iconHeight = bounds.height() * .82f
        val iconWidth = source?.let { iconHeight * it.width() / it.height().toFloat() } ?: 0f
        val gap = if (source != null) bounds.height() * .22f else 0f
        var textWidth = paint.measureText(BRAND_NAME)
        val availableWidth = bounds.width() - iconWidth - gap
        if (textWidth > availableWidth && textWidth > 0f) {
            paint.textSize *= availableWidth / textWidth
            textWidth = paint.measureText(BRAND_NAME)
        }

        val groupWidth = iconWidth + gap + textWidth
        var x = bounds.centerX() - groupWidth / 2f
        if (logo != null && source != null) {
            val iconTop = bounds.centerY() - iconHeight / 2f
            canvas.drawBitmap(
                logo,
                source,
                RectF(x, iconTop, x + iconWidth, iconTop + iconHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            x += iconWidth + gap
        }
        val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(BRAND_NAME, x, baseline, paint)
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
    }

    private fun blackPixelBounds(bitmap: Bitmap): Rect {
        var left = bitmap.width
        var right = -1
        for (x in 0 until bitmap.width) {
            var containsBlack = false
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == Color.BLACK) {
                    containsBlack = true
                    break
                }
            }
            if (containsBlack) {
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        return if (right >= left) Rect(left, 0, right + 1, bitmap.height)
        else Rect(0, 0, bitmap.width, bitmap.height)
    }

    private fun drawTextInBounds(
        canvas: Canvas,
        paint: Paint,
        text: String,
        bounds: RectF,
        bold: Boolean,
        alignment: TextAlignment = TextAlignment.CENTER
    ) {
        fitPaint(paint, text, bounds.width(), bounds.height(), bold)
        val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2f
        val x = when (alignment) {
            TextAlignment.START -> bounds.left
            TextAlignment.CENTER -> bounds.centerX() - paint.measureText(text) / 2f
            TextAlignment.END -> bounds.right - paint.measureText(text)
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawProductName(
        canvas: Canvas,
        paint: Paint,
        text: String,
        bounds: RectF
    ) {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true
        val lineGap = bounds.height() * .04f
        val lineHeight = (bounds.height() - lineGap) / 2f
        fitPaint(paint, "Ag", bounds.width(), lineHeight, bold = true)
        val lines = wrapTextAtMostTwoLines(text, bounds.width()) { paint.measureText(it) }
        if (lines.size == 1) {
            fitPaint(paint, lines.first(), bounds.width(), lineHeight, bold = true)
            val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(
                lines.first(),
                bounds.centerX() - paint.measureText(lines.first()) / 2f,
                baseline,
                paint
            )
            return
        }

        val widestLine = lines.maxByOrNull { paint.measureText(it) }.orEmpty()
        fitPaint(paint, widestLine, bounds.width(), lineHeight, bold = true)
        val textHeight = paint.descent() - paint.ascent()
        val blockHeight = textHeight * lines.size + lineGap
        var baseline = bounds.centerY() - blockHeight / 2f - paint.ascent()
        lines.forEach { line ->
            canvas.drawText(line, bounds.centerX() - paint.measureText(line) / 2f, baseline, paint)
            baseline += textHeight + lineGap
        }
    }

    private fun fitPaint(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        bold: Boolean
    ) {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.isFakeBoldText = bold
        paint.textSize = maxHeight
        val measuredHeight = paint.descent() - paint.ascent()
        if (measuredHeight > maxHeight && measuredHeight > 0f) {
            paint.textSize *= maxHeight / measuredHeight
        }
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > maxWidth && measuredWidth > 0f) {
            paint.textSize *= maxWidth / measuredWidth
        }
    }

    private fun generateCode128(content: String, width: Int, height: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.CODE_128,
            width,
            height,
            hints
        )
        val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until bitMatrix.width) {
            for (y in 0 until bitMatrix.height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun generateQrCode(content: String, size: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 2
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val bitMatrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )
        val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until bitMatrix.width) {
            for (y in 0 until bitMatrix.height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    internal fun entryDateText(tanggalMasuk: String?): String =
        LabelDate.display(tanggalMasuk).orEmpty()

    private fun formatRupiah(amount: Long): String =
        java.text.NumberFormat.getNumberInstance(java.util.Locale.GERMANY).format(amount)

    fun encodePurchasePrice(amount: Long): String {
        if (amount == 0L) return "P"
        val encoded = amount.toString().map { HARGA_DECODE_MAP[it] ?: it }.joinToString("")
        if (encoded.length > 1) {
            val lastChar = encoded.last()
            val stripped = encoded.dropLastWhile { it == lastChar }
            return if (stripped.isNotEmpty()) stripped + lastChar else lastChar.toString()
        }
        return encoded
    }

    private enum class TextAlignment { START, CENTER, END }
}

internal fun wrapTextAtMostTwoLines(
    text: String,
    maxWidth: Float,
    measure: (String) -> Float
): List<String> {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.isEmpty() || measure(normalized) <= maxWidth) return listOf(normalized)

    val candidates = normalized.indices
        .filter { normalized[it] == ' ' }
        .map { index -> normalized.substring(0, index).trim() to normalized.substring(index + 1).trim() }
        .filter { (first, second) -> first.isNotEmpty() && second.isNotEmpty() }

    val split = (candidates.ifEmpty {
        (1 until normalized.length).map { index ->
            normalized.substring(0, index) to normalized.substring(index)
        }
    }).minByOrNull { (first, second) ->
        val firstWidth = measure(first)
        val secondWidth = measure(second)
        maxOf(firstWidth, secondWidth) + kotlin.math.abs(firstWidth - secondWidth) * .15f
    }

    return split?.let { listOf(it.first, it.second) } ?: listOf(normalized)
}

internal data class FractionalFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toPixels(width: Int, height: Int): RectF = RectF(
        left * width,
        top * height,
        right * width,
        bottom * height
    )
}

internal data class FixedLabelMetrics(
    val barcode: FractionalFrame,
    val metadata: FractionalFrame,
    val productName: FractionalFrame,
    val price: FractionalFrame,
    val brand: FractionalFrame
) {
    companion object {
        fun forSize(size: LabelSize): FixedLabelMetrics =
            if (size.matches(30, 20)) {
                FixedLabelMetrics(
                    barcode = FractionalFrame(.055f, .04f, .945f, .245f),
                    metadata = FractionalFrame(.055f, .255f, .945f, .325f),
                    productName = FractionalFrame(.055f, .335f, .945f, .615f),
                    price = FractionalFrame(.055f, .635f, .945f, .765f),
                    brand = FractionalFrame(.22f, .82f, .78f, .94f)
                )
            } else {
                FixedLabelMetrics(
                    barcode = FractionalFrame(.085f, .075f, .915f, .28f),
                    metadata = FractionalFrame(.085f, .29f, .915f, .36f),
                    productName = FractionalFrame(.055f, .37f, .945f, .65f),
                    price = FractionalFrame(.08f, .67f, .92f, .79f),
                    brand = FractionalFrame(.28f, .84f, .72f, .95f)
                )
            }
    }
}

internal data class QrLabelMetrics(
    val qrCode: FractionalFrame,
    val productName: FractionalFrame,
    val price: FractionalFrame,
    val metadata: FractionalFrame,
    val brand: FractionalFrame
) {
    companion object {
        fun forSize(size: LabelSize): QrLabelMetrics =
            if (size.matches(30, 20)) {
                QrLabelMetrics(
                    qrCode = FractionalFrame(.04f, .08f, .38f, .72f),
                    productName = FractionalFrame(.41f, .07f, .955f, .37f),
                    price = FractionalFrame(.41f, .405f, .955f, .555f),
                    metadata = FractionalFrame(.41f, .59f, .955f, .69f),
                    brand = FractionalFrame(.22f, .79f, .78f, .935f)
                )
            } else {
                QrLabelMetrics(
                    qrCode = FractionalFrame(.045f, .08f, .37f, .72f),
                    productName = FractionalFrame(.40f, .07f, .95f, .37f),
                    price = FractionalFrame(.40f, .405f, .95f, .555f),
                    metadata = FractionalFrame(.40f, .59f, .95f, .69f),
                    brand = FractionalFrame(.26f, .80f, .74f, .94f)
                )
            }
    }
}
