package com.niimbot.printagent.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    
    const val LABEL_WIDTH = 590
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
        kodeHargaBeli: String? = null
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
        val hargaBeliEncoded = kodeHargaBeli?.trim()?.takeIf { it.isNotEmpty() } ?: hargaEncode(hargaBeli)

        when (labelLayout) {
            LabelLayout.COMPACT -> drawCompact(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent)
            LabelLayout.BARCODE_BOTTOM -> drawBarcodeBottom(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent)
            LabelLayout.STANDARD -> drawStandard(canvas, paint, width, height, nama, hargaJualText, hargaBeliEncoded, barcodeContent)
        }
        
        return bitmap
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
        hargaJual: String, hargaBeli: String, barcodeContent: String
    ) {
        val scale = height / LABEL_HEIGHT.toFloat()
        val barcodeHeight = (height * .22f).toInt().coerceAtLeast(40)
        val barcode = generateCode128(barcodeContent, width - MARGIN_LEFT - MARGIN_RIGHT, barcodeHeight)
        canvas.drawBitmap(barcode, (width - barcode.width) / 2f, height * .08f, null)
        drawCenteredText(canvas, paint, barcodeContent, width, height * .37f, 16f * scale, false)
        drawCenteredFittedText(canvas, paint, nama, width, height * .57f, 44f * scale, true)
        drawCenteredFittedText(canvas, paint, "$hargaBeli  $hargaJual", width, height * .80f, 46f * scale, true)
    }

    private fun drawCompact(
        canvas: Canvas, paint: Paint, width: Int, height: Int, nama: String,
        hargaJual: String, hargaBeli: String, barcodeContent: String
    ) {
        val padding = (width * .035f).toInt()
        val barcodeWidth = (width * .38f).toInt()
        val contentWidth = width - barcodeWidth - padding * 3
        val barcode = generateCode128(barcodeContent, barcodeWidth, (height * .58f).toInt())
        canvas.drawBitmap(barcode, (width - padding - barcodeWidth).toFloat(), (height * .12f), null)
        drawCenteredText(canvas, paint, barcodeContent, barcodeWidth,
            height * .82f, (height * .055f), false, width - padding - barcodeWidth)
        drawFittedText(canvas, paint, nama, padding.toFloat(), height * .27f, contentWidth.toFloat(), height * .16f, true)
        drawFittedText(canvas, paint, hargaJual, padding.toFloat(), height * .55f, contentWidth.toFloat(), height * .15f, true)
        drawFittedText(canvas, paint, hargaBeli, padding.toFloat(), height * .75f, contentWidth.toFloat(), height * .10f, true)
    }

    private fun drawBarcodeBottom(
        canvas: Canvas, paint: Paint, width: Int, height: Int, nama: String,
        hargaJual: String, hargaBeli: String, barcodeContent: String
    ) {
        val scale = height / LABEL_HEIGHT.toFloat()
        // Keep the first baseline well inside the printer's effective top margin.
        drawCenteredFittedText(canvas, paint, nama, width, height * .21f, 44f * scale, true)
        drawCenteredFittedText(canvas, paint, "$hargaBeli  $hargaJual", width, height * .37f, 42f * scale, true)
        val barcodeHeight = (72 * scale).toInt().coerceAtLeast(44)
        val barcode = generateCode128(barcodeContent, width - MARGIN_LEFT - MARGIN_RIGHT, barcodeHeight)
        canvas.drawBitmap(barcode, (width - barcode.width) / 2f, height * .48f, null)
        drawCenteredText(canvas, paint, barcodeContent, width, height * .86f, 16f * scale, false)
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
    private fun hargaEncode(amount: Long): String {
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
