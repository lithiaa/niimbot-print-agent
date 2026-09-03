package com.niimbot.printagent.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.util.EnumMap

/**
 * Label generator for supported Niimbot media at 300 dpi.
 */
object LabelGenerator {
    
    const val LABEL_WIDTH = 584
    const val LABEL_HEIGHT = 354
    const val DPI = LabelSize.DPI
    
    // Margins
    const val MARGIN_LEFT = 15
    const val MARGIN_TOP = 15
    const val MARGIN_RIGHT = 15
    
    // SANGUOERIP harga encode map (matches POS config.py)
    private val HARGA_DECODE_MAP = mapOf(
        '1' to 'S', '2' to 'A', '3' to 'N', '4' to 'G',
        '5' to 'U', '6' to 'O', '7' to 'E', '8' to 'R',
        '9' to 'I', '0' to 'P',
        's' to 'S', 'a' to 'A', 'n' to 'N', 'g' to 'G',
        'u' to 'U', 'o' to 'O', 'e' to 'E', 'r' to 'R',
        'i' to 'I', 'p' to 'P'
    )
    
    /**
     * Generate label bitmap from item data
     */
    fun generateLabel(
        nama: String,
        hargaJual: Long,
        hargaBeli: Long,
        sku: String,
        satuan: String = "pcs",
        barcodeData: String? = null,
        labelSize: LabelSize = LabelSize.MM_50_X_30,
        labelLayout: LabelLayout = LabelLayout.STANDARD,
        kodeHargaBeli: String? = null,
        itemQty: Int = 1,
        supplierCode: String? = null,
        tanggalMasuk: String? = null,
        labelTemplate: LabelTemplate? = null,
        highlightedElement: LabelElement? = null
    ): Bitmap {
        val width = labelSize.widthPx
        val height = labelSize.heightPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // White background
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        
        val barcodeContent = barcodeData ?: sku
        val hargaJualText = "Rp ${formatRupiah(hargaJual)}"
        val hargaBeliEncoded = kodeHargaBeli?.trim()?.takeIf { it.isNotEmpty() } ?: encodePurchasePrice(hargaBeli)

        if (labelTemplate != null) {
            drawEditableTemplate(
                canvas = canvas,
                paint = paint,
                width = width,
                height = height,
                template = labelTemplate,
                nama = nama,
                hargaJual = hargaJualText,
                hargaBeli = hargaBeliEncoded,
                barcodeContent = barcodeContent,
                sku = sku,
                itemQty = itemQty,
                supplierCode = supplierCode,
                tanggalMasuk = tanggalMasuk,
                highlightedElement = highlightedElement
            )
        } else {
            when (labelLayout) {
                LabelLayout.COMPACT -> drawCompact(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent, sku, itemQty, supplierCode)
                LabelLayout.BARCODE_BOTTOM -> drawBarcodeBottom(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent, sku, itemQty, supplierCode)
                LabelLayout.STANDARD -> drawStandard(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent, sku, itemQty, supplierCode)
            }
            drawEntryDate(canvas, paint, width, height, tanggalMasuk)
        }
        
        return bitmap
    }

    private fun drawEditableTemplate(
        canvas: Canvas,
        paint: Paint,
        width: Int,
        height: Int,
        template: LabelTemplate,
        nama: String,
        hargaJual: String,
        hargaBeli: String,
        barcodeContent: String,
        sku: String,
        itemQty: Int,
        supplierCode: String?,
        tanggalMasuk: String?,
        highlightedElement: LabelElement?
    ) {
        val safeTemplate = template.normalized()
        val values = mapOf(
            LabelElement.PRODUCT_NAME to nama,
            LabelElement.PURCHASE_CODE to hargaBeli,
            LabelElement.SALE_PRICE to hargaJual,
            LabelElement.SKU to sku,
            LabelElement.ITEM_QTY to "${itemQty.coerceAtLeast(1)} QTY",
            LabelElement.SUPPLIER to supplierCode.orEmpty(),
            LabelElement.ENTRY_DATE to entryDateText(tanggalMasuk)
        )

        LabelElement.entries.forEach { element ->
            val bounds = frameBounds(safeTemplate.frame(element), width, height)
            if (element == LabelElement.BARCODE) {
                val barcode = generateCode128(
                    barcodeContent,
                    bounds.width().toInt().coerceAtLeast(1),
                    bounds.height().toInt().coerceAtLeast(1)
                )
                canvas.drawBitmap(barcode, bounds.left, bounds.top, null)
            } else {
                val text = values[element].orEmpty()
                if (text.isNotEmpty()) {
                    drawTextInFrame(
                        canvas,
                        paint,
                        text,
                        bounds,
                        bold = element in setOf(
                            LabelElement.PRODUCT_NAME,
                            LabelElement.PURCHASE_CODE,
                            LabelElement.SALE_PRICE
                        )
                    )
                }
            }
        }

        highlightedElement?.let { element ->
            val bounds = frameBounds(safeTemplate.frame(element), width, height)
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(25, 118, 210)
            paint.strokeWidth = (width / 180f).coerceAtLeast(2f)
            canvas.drawRect(bounds, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
        }
    }

    private fun frameBounds(frame: LabelElementFrame, width: Int, height: Int): RectF = RectF(
        (frame.centerX - frame.width / 2f) * width,
        (frame.centerY - frame.height / 2f) * height,
        (frame.centerX + frame.width / 2f) * width,
        (frame.centerY + frame.height / 2f) * height
    )

    private fun drawTextInFrame(
        canvas: Canvas,
        paint: Paint,
        text: String,
        bounds: RectF,
        bold: Boolean
    ) {
        fitPaint(paint, text, bounds.width(), bounds.height(), bold)
        val baseline = bounds.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, bounds.centerX() - paint.measureText(text) / 2f, baseline, paint)
    }
    
    /**
     * Generate label from JSON template (for dynamic fields)
     */
    fun generateLabelFromJson(jsonData: Map<String, Any>): Bitmap {
            val nama = jsonData["nama"] as? String ?: "Unknown"
            val hargaJual = (jsonData["harga_jual"] as? Number ?: jsonData["hargaJual"] as? Number ?: 0L).toLong()
            val hargaBeli = (jsonData["harga_beli"] as? Number ?: jsonData["hargaBeli"] as? Number ?: 0L).toLong()
            val sku = jsonData["sku"] as? String ?: jsonData["kode_barang"] as? String ?: "000000"
            val satuan = jsonData["satuan"] as? String ?: "pcs"
            val barcodeData = jsonData["barcode"] as? String

            return generateLabel(nama, hargaJual, hargaBeli, sku, satuan, barcodeData)
        }

    private fun drawStandard(
        canvas: Canvas, paint: Paint, width: Int, height: Int, nama: String,
        hargaJual: String, hargaBeli: String, barcodeContent: String, sku: String,
        itemQty: Int, supplierCode: String?
    ) {
        val scale = height / LABEL_HEIGHT.toFloat()
        val shortLabel = isShortLabel(height)
        val barcodeMargin = barcodeHorizontalMargin(width)
        val barcodeHeight = if (shortLabel) {
            (height * .28f).toInt()
        } else {
            (height * .22f).toInt().coerceAtLeast(40)
        }
        val barcode = generateCode128(barcodeContent, width - barcodeMargin * 2, barcodeHeight)
        val barcodeX = (width - barcode.width) / 2f
        canvas.drawBitmap(barcode, barcodeX, height * if (shortLabel) .05f else .08f, null)
        val barcodeBounds = visibleBarcodeBounds(barcode, barcodeX)
        drawMetadataRow(
            canvas,
            paint,
            barcodeBounds.first,
            barcodeBounds.second,
            height * if (shortLabel) .43f else .37f,
            metadataTextSize(height),
            sku,
            itemQty,
            supplierCode
        )
        drawCenteredFittedText(canvas, paint, nama, width, height * if (shortLabel) .64f else .57f, 44f * scale, true)
        drawCenteredFittedText(canvas, paint, "$hargaBeli  $hargaJual", width, height * if (shortLabel) .87f else .80f, 46f * scale, true)
    }

    private fun drawCompact(
        canvas: Canvas, paint: Paint, width: Int, height: Int, nama: String,
        hargaJual: String, hargaBeli: String, barcodeContent: String, sku: String,
        itemQty: Int, supplierCode: String?
    ) {
        val padding = (width * .035f).toInt()
        val barcodeWidth = (width * .38f).toInt()
        val contentWidth = width - barcodeWidth - padding * 3
        val barcode = generateCode128(barcodeContent, barcodeWidth, (height * .58f).toInt())
        val barcodeX = (width - padding - barcodeWidth).toFloat()
        canvas.drawBitmap(barcode, barcodeX, (height * .12f), null)
        drawFittedText(canvas, paint, nama, padding.toFloat(), height * .27f, contentWidth.toFloat(), height * .16f, true)
        drawFittedText(canvas, paint, hargaJual, padding.toFloat(), height * .55f, contentWidth.toFloat(), height * .15f, true)
        drawFittedText(canvas, paint, hargaBeli, padding.toFloat(), height * .75f, contentWidth.toFloat(), height * .10f, true)
        val barcodeBounds = visibleBarcodeBounds(barcode, barcodeX)
        drawMetadataRow(
            canvas,
            paint,
            barcodeBounds.first,
            barcodeBounds.second,
            height * if (isShortLabel(height)) .86f else .91f,
            metadataTextSize(height),
            sku,
            itemQty,
            supplierCode
        )
    }

    private fun drawBarcodeBottom(
        canvas: Canvas, paint: Paint, width: Int, height: Int, nama: String,
        hargaJual: String, hargaBeli: String, barcodeContent: String, sku: String,
        itemQty: Int, supplierCode: String?
    ) {
        val scale = height / LABEL_HEIGHT.toFloat()
        val shortLabel = isShortLabel(height)
        // Keep the first baseline well inside the printer's effective top margin.
        drawCenteredFittedText(canvas, paint, nama, width, height * if (shortLabel) .19f else .21f, 44f * scale, true)
        drawCenteredFittedText(canvas, paint, "$hargaBeli  $hargaJual", width, height * if (shortLabel) .35f else .37f, 42f * scale, true)
        val barcodeHeight = if (shortLabel) {
            (height * .30f).toInt()
        } else {
            (72 * scale).toInt().coerceAtLeast(44)
        }
        val barcodeMargin = barcodeHorizontalMargin(width)
        val barcode = generateCode128(barcodeContent, width - barcodeMargin * 2, barcodeHeight)
        val barcodeX = (width - barcode.width) / 2f
        canvas.drawBitmap(barcode, barcodeX, height * if (shortLabel) .43f else .48f, null)
        val barcodeBounds = visibleBarcodeBounds(barcode, barcodeX)
        drawMetadataRow(
            canvas,
            paint,
            barcodeBounds.first,
            barcodeBounds.second,
            height * if (shortLabel) .87f else .78f,
            metadataTextSize(height),
            sku,
            itemQty,
            supplierCode
        )
    }

    private fun isShortLabel(height: Int): Boolean = height <= LabelSize.MM_30_X_20.heightPx

    private fun barcodeHorizontalMargin(width: Int): Int =
        if (width <= LabelSize.MM_30_X_20.widthPx) 8 else MARGIN_LEFT

    private fun metadataTextSize(height: Int): Float =
        (16f * height / LABEL_HEIGHT).coerceAtLeast(if (isShortLabel(height)) 20f else 16f)

    private fun drawEntryDate(
        canvas: Canvas,
        paint: Paint,
        width: Int,
        height: Int,
        tanggalMasuk: String?
    ) {
        val displayDate = entryDateText(tanggalMasuk).ifEmpty { return }
        val size = if (isShortLabel(height)) 13f else 14f * height / LABEL_HEIGHT
        drawCenteredFittedText(
            canvas,
            paint,
            displayDate,
            width,
            height * .985f,
            size.coerceAtLeast(13f),
            false
        )
    }

    internal fun entryDateText(tanggalMasuk: String?): String =
        LabelDate.display(tanggalMasuk).orEmpty()

    private fun drawMetadataRow(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        right: Float,
        y: Float,
        size: Float,
        sku: String,
        itemQty: Int,
        supplierCode: String?
    ) {
        val contentWidth = (right - left).coerceAtLeast(1f)
        val sideWidth = contentWidth * .25f
        val gap = 8f
        val qtyText = "${itemQty.coerceAtLeast(1)} QTY"
        val supplierText = supplierCode?.trim().orEmpty()

        fitPaint(paint, qtyText, sideWidth, size, false)
        val qtyWidth = paint.measureText(qtyText)
        canvas.drawText(qtyText, left, y, paint)

        var supplierWidth = 0f
        if (supplierText.isNotEmpty()) {
            fitPaint(paint, supplierText, sideWidth, size, false)
            supplierWidth = paint.measureText(supplierText)
            canvas.drawText(supplierText, right - supplierWidth, y, paint)
        }

        val centerX = left + contentWidth / 2f
        val availableLeft = centerX - (left + qtyWidth + gap)
        val availableRight = (right - supplierWidth - gap) - centerX
        val centerWidth = (2f * minOf(availableLeft, availableRight)).coerceAtLeast(1f)
        fitPaint(paint, sku, centerWidth, size, true)
        canvas.drawText(sku, centerX - paint.measureText(sku) / 2f, y, paint)
    }

    private fun visibleBarcodeBounds(barcode: Bitmap, drawX: Float): Pair<Float, Float> {
        var firstBlack = -1
        var lastBlack = -1
        for (x in 0 until barcode.width) {
            var containsBlack = false
            for (y in 0 until barcode.height) {
                if (barcode.getPixel(x, y) == Color.BLACK) {
                    containsBlack = true
                    break
                }
            }
            if (containsBlack) {
                if (firstBlack < 0) firstBlack = x
                lastBlack = x
            }
        }
        return if (firstBlack >= 0) {
            (drawX + firstBlack) to (drawX + lastBlack + 1)
        } else {
            drawX to (drawX + barcode.width)
        }
    }

    private fun drawCenteredFittedText(canvas: Canvas, paint: Paint, text: String, width: Int, y: Float, size: Float, bold: Boolean) {
        fitPaint(paint, text, width - MARGIN_LEFT - MARGIN_RIGHT.toFloat(), size, bold)
        canvas.drawText(text, (width - paint.measureText(text)) / 2f, y, paint)
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, width: Int, y: Float, size: Float, bold: Boolean, offset: Int = 0) {
        paint.textSize = size
        paint.isFakeBoldText = bold
        canvas.drawText(text, offset + (width - paint.measureText(text)) / 2f, y, paint)
    }

    private fun drawFittedText(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float, maxWidth: Float, size: Float, bold: Boolean) {
        fitPaint(paint, text, maxWidth, size, bold)
        canvas.drawText(text, x, y, paint)
    }

    private fun fitPaint(paint: Paint, text: String, maxWidth: Float, preferredSize: Float, bold: Boolean) {
        paint.textSize = preferredSize
        paint.isFakeBoldText = bold
        if (paint.measureText(text) > maxWidth) {
            paint.textSize *= maxWidth / paint.measureText(text)
        }
    }
    
    /**
     * Generate Code128 barcode bitmap
     */
    private fun generateCode128(content: String, width: Int, height: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        
        val writer = MultiFormatWriter()
        val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.CODE_128, width, height, hints)
        
        val bitmapWidth = bitMatrix.width
        val bitmapHeight = bitMatrix.height
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        
        for (x in 0 until bitmapWidth) {
            for (y in 0 until bitmapHeight) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }
    
    /**
     * Format number as Indonesian Rupiah
     */
    private fun formatRupiah(amount: Long): String {
        return java.text.NumberFormat.getNumberInstance(java.util.Locale.GERMANY).format(amount)
    }

    /** SANGUOERIP encode (matches POS harga_encode) */
    fun encodePurchasePrice(amount: Long): String {
        if (amount == 0L) return "P"
        val s = amount.toString()
        val encoded = s.map { HARGA_DECODE_MAP[it] ?: it }.joinToString("")
        // Collapse repeating trailing chars: AUPPP -> AUP
        if (encoded.length > 1) {
            val lastChar = encoded.last()
            val stripped = encoded.dropLastWhile { it == lastChar }
            return if (stripped.isNotEmpty()) stripped + lastChar else lastChar.toString()
        }
        return encoded
    }
}
