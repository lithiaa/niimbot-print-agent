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
        
        // ============ BARCODE (Code128) - TOP ============
        val barcodeContent = barcodeData ?: sku
        val barcodeBitmap = generateCode128(barcodeContent, LABEL_WIDTH - MARGIN_LEFT - MARGIN_RIGHT, 75)
        
        // Center barcode at top
        val barcodeX = (LABEL_WIDTH - barcodeBitmap.width) / 2
        var y = MARGIN_TOP.toFloat()
        canvas.drawBitmap(barcodeBitmap, barcodeX.toFloat(), y, null)
        y += barcodeBitmap.height + 16f
        
        // ============ BARCODE TEXT BELOW BARCODE ============
        paint.textSize = 16f
        paint.isFakeBoldText = false
        val barcodeTextWidth = paint.measureText(barcodeContent)
        val barcodeTextX = (LABEL_WIDTH - barcodeTextWidth) / 2
        canvas.drawText(barcodeContent, barcodeTextX, y, paint)
        y += 24f
        
        // ============ NAMA BARANG (Bold, Large) ============
        paint.textSize = 36f
        paint.isFakeBoldText = true
        
        // Fix baseline: drawText y is font baseline, so add textSize to MARGIN_TOP
        // y already positioned after barcode
        
        // Dynamic measure & truncate if too long for label width
        val maxNamaWidth = (LABEL_WIDTH - MARGIN_LEFT - MARGIN_RIGHT).toFloat()
        var displayNama = nama
        if (paint.measureText(displayNama) > maxNamaWidth) {
            while (displayNama.isNotEmpty() && paint.measureText("$displayNama...") > maxNamaWidth) {
                displayNama = displayNama.dropLast(1)
            }
            displayNama = "$displayNama..."
        }
        canvas.drawText(displayNama, MARGIN_LEFT.toFloat(), y, paint)
        y += 48f
        
        // ============ HARGA JUAL + HARGA BELI (SAME LINE) ============
        paint.textSize = 48f
        paint.isFakeBoldText = true
        
        val hargaJualText = "Rp ${formatRupiah(hargaJual)}"
        val hargaBeliEncoded = hargaEncode(hargaBeli)
        val hargaLine = "$hargaBeliEncoded  $hargaJualText"
        
        canvas.drawText(hargaLine, MARGIN_LEFT.toFloat(), y, paint)
        y += 56f
        
        // ============ SKU (Medium) ============
        paint.textSize = 24f
        paint.isFakeBoldText = false
        
        canvas.drawText("SKU: $sku", MARGIN_LEFT.toFloat(), y, paint)
        
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