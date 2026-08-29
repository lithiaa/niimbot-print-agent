package com.niimbot.printagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.niimbot.printagent.data.converters.DateConverter
import java.util.Date

/**
 * Print Job entity - queued print jobs from cloud POS
 */
@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Item data (from cloud)
    val nama: String,
    val hargaJual: Long,
    val hargaBeli: Long,
    val kodeHargaBeli: String? = null,
    val sku: String,
    val stok: Int = 0,
    val satuan: String = "pcs",
    val barcode: String? = null,
    
    // Print settings
    val qty: Int = 1,
    val printerMac: String? = null,
    val printerModel: String = "B1",
    val printDirection: String = "top", // "top" or "left"
    val labelSize: String = "MM_50_X_30",
    val labelLayout: String = "STANDARD",
    
    // Queue status
    val status: PrintStatus = PrintStatus.PENDING,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val priority: Int = 0, // Higher = more urgent
    
    // Timestamps
    @TypeConverters(DateConverter::class)
    val createdAt: Date = Date(),
    
    @TypeConverters(DateConverter::class)
    val printedAt: Date? = null,
    
    @TypeConverters(DateConverter::class)
    val updatedAt: Date = Date()
)

enum class PrintStatus {
    PENDING,     // Waiting in queue
    PRINTING,    // Currently sending to printer
    DONE,        // Successfully printed
    FAILED,      // Error occurred
    CANCELLED    // User cancelled
}
