package com.niimbot.printagent.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.zxing.android.encoder.EncodeHintType
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
    
    /**
     * Generate label bitmap from item data
     */
    fun generateLabel(
        nama: String,
        hargaJual: Long,
        sku: String,
        stok: Int,
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
        
        var y = MARGIN_TOP
        
        // ============ NAMA BARANG (Bold, Large) ============
        paint.textSize = 36f
        paint.isFakeBoldText = true
        
        // Truncate if too long
        val displayNama = if (nama.length > 30) "${nama.substring(0, 27)}..." else nama
        canvas.drawText(displayNama, MARGIN_LEFT.toFloat(), y, paint)
        y += 48
        
        // ============ HARGA JUAL (Very Large, Bold) ============
        paint.textSize = 50f
        paint.isFakeBoldText = true
        
        val hargaText = "Rp ${formatRupiah(hargaJual)}"
        canvas.drawText(hargaText, MARGIN_LEFT.toFloat(), y, paint)
        y += 58
        
        // ============ SKU + STOK (Medium) ============
        paint.textSize = 24f
        paint.isFakeBoldText = false
        
        canvas.drawText("SKU: $sku", MARGIN_LEFT.toFloat(), y, paint)
        y += 32
        
        canvas.drawText("Stok: $stok $satuan", MARGIN_LEFT.toFloat(), y, paint)
        y += 32
        
        // ============ BARCODE (Code128) ============
        val barcodeContent = barcodeData ?: sku
        val barcodeBitmap = generateCode128(barcodeContent, LABEL_WIDTH - MARGIN_LEFT - MARGIN_RIGHT, 80)
        
        // Center barcode
        val barcodeX = (LABEL_WIDTH - barcodeBitmap.width) / 2
        canvas.drawBitmap(barcodeBitmap, barcodeX.toFloat(), y.toFloat(), null)
        y += barcodeBitmap.height + 10
        
        // ============ BARCODE TEXT BELOW ============
        paint.textSize = 16f
        val barcodeTextWidth = paint.measureText(barcodeContent)
        val barcodeTextX = (LABEL_WIDTH - barcodeTextWidth) / 2
        canvas.drawText(barcodeContent, barcodeTextX, y, paint)
        
        return bitmap
    }
    
    /**
     * Generate label from JSON template (for dynamic fields)
     */
    fun generateLabelFromJson(jsonData: Map<String, Any>): Bitmap {
        val nama = jsonData["nama"] as? String ?: "Unknown"
        val hargaJual = (jsonData["harga_jual"] as? Number ?: jsonData["hargaJual"] as? Number ?: 0L).toLong()
        val sku = jsonData["sku"] as? String ?: jsonData["kode_barang"] as? String ?: "000000"
        val stok = (jsonData["stok"] as? Number ?: 0).toInt()
        val satuan = jsonData["satuan"] as? String ?: "pcs"
        val barcodeData = jsonData["barcode"] as? String
        
        return generateLabel(nama, hargaJual, sku, stok, satuan, barcodeData)
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
}