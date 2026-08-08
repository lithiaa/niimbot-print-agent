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
 * Label Generator for Niimbot B1 Pro (50x30mm @ 300dpi = 584x354px)
 */
object LabelGenerator {
    
    // B1 Pro label dimensions
    const val LABEL_WIDTH = 584
    const val LABEL_HEIGHT = 354
    const val DPI = 300
    
    // Margins
    const val MARGIN_LEFT = 20
    const val MARGIN_TOP = 45
    const val MARGIN_RIGHT = 20
    
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
        barcodeData: String? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(LABEL_WIDTH, LABEL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // White background
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        
        // ============ 1. NAMA BARANG (Centered, Auto 1 or 2 Lines) ============
        val maxNamaWidth = (LABEL_WIDTH - MARGIN_LEFT - MARGIN_RIGHT).toFloat()
        
        paint.textSize = 34f
        paint.isFakeBoldText = true
        
        if (paint.measureText(nama) <= maxNamaWidth) {
            // Fits on 1 line cleanly
            var y = MARGIN_TOP.toFloat() + 32f
            val namaWidth = paint.measureText(nama)
            val namaX = (LABEL_WIDTH - namaWidth) / 2f
            canvas.drawText(nama, namaX, y, paint)
            y += 56f
            
            drawPriceAndBarcode(canvas, paint, hargaJual, hargaBeli, sku, barcodeData, y, 75)
        } else {
            // Long name: use 28f font & wrap into 2 lines
            var y = MARGIN_TOP.toFloat() + 24f
            paint.textSize = 28f
            val words = nama.split(" ")
            var line1 = ""
            var line2 = ""
            
            for (word in words) {
                val testLine = if (line1.isEmpty()) word else "$line1 $word"
                if (paint.measureText(testLine) <= maxNamaWidth) {
                    line1 = testLine
                } else {
                    line2 = if (line2.isEmpty()) word else "$line2 $word"
                }
            }
            
            // Truncate line 2 with ... if still exceeds max width
            if (paint.measureText(line2) > maxNamaWidth) {
                while (line2.isNotEmpty() && paint.measureText("$line2...") > maxNamaWidth) {
                    line2 = line2.dropLast(1)
                }
                line2 = "$line2..."
            }
            
            // Draw Line 1 (Centered)
            val width1 = paint.measureText(line1)
            val x1 = (LABEL_WIDTH - width1) / 2f
            canvas.drawText(line1, x1, y, paint)
            y += 34f
            
            // Draw Line 2 (Centered)
            if (line2.isNotEmpty()) {
                val width2 = paint.measureText(line2)
                val x2 = (LABEL_WIDTH - width2) / 2f
                canvas.drawText(line2, x2, y, paint)
                y += 54f // Spacing between Line 2 and Harga Line
            } else {
                y += 20f
            }
            
            drawPriceAndBarcode(canvas, paint, hargaJual, hargaBeli, sku, barcodeData, y, 68)
        }
        
        return bitmap
    }

    private fun drawPriceAndBarcode(
        canvas: Canvas,
        paint: Paint,
        hargaJual: Long,
        hargaBeli: Long,
        sku: String,
        barcodeData: String?,
        startY: Float,
        barcodeHeight: Int
    ) {
        var y = startY
        
        // ============ 2. HARGA JUAL + HARGA BELI SANGUOERIP (Centered) ============
        paint.textSize = 42f
        paint.isFakeBoldText = true
        
        val hargaJualText = "Rp ${formatRupiah(hargaJual)}"
        val hargaBeliEncoded = hargaEncode(hargaBeli)
        val hargaLine = "$hargaBeliEncoded   $hargaJualText"
        
        val hargaWidth = paint.measureText(hargaLine)
        val hargaX = (LABEL_WIDTH - hargaWidth) / 2f
        canvas.drawText(hargaLine, hargaX, y, paint)
        y += 48f
        
        // ============ 3. BARCODE (Code128) - BOTTOM (Centered) ============
        val barcodeContent = barcodeData ?: sku
        val barcodeBitmap = generateCode128(barcodeContent, LABEL_WIDTH - MARGIN_LEFT - MARGIN_RIGHT, barcodeHeight)
        
        // Center barcode
        val barcodeX = (LABEL_WIDTH - barcodeBitmap.width) / 2
        canvas.drawBitmap(barcodeBitmap, barcodeX.toFloat(), y, null)
        y += barcodeBitmap.height + 22f
        
        // ============ 4. BARCODE TEXT BELOW BARCODE (Centered) ============
        paint.textSize = 17f
        paint.isFakeBoldText = false
        val barcodeTextWidth = paint.measureText(barcodeContent)
        val barcodeTextX = (LABEL_WIDTH - barcodeTextWidth) / 2f
        canvas.drawText(barcodeContent, barcodeTextX, y, paint)
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