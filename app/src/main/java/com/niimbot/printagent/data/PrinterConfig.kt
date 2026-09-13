package com.niimbot.printagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Printer configuration (paired printer)
 */
@Entity(tableName = "printer_configs")
data class PrinterConfig(
    @PrimaryKey
    val id: Int = 1, // Singleton config
    
    val macAddress: String? = null,
    val model: String = "XPrinter TSPL 203 DPI",
    val name: String = "XPrinter",
    val printerType: String = "XPRINTER",
    val printerDpi: Int = 203,
    val isDefault: Boolean = true,
    
    // Connection settings
    val autoReconnect: Boolean = true,
    val reconnectIntervalMs: Long = 5000,
    
    // Print settings
    val defaultQty: Int = 1,
    val defaultDirection: String = "top",
    val labelWidth: Int = 584,
    val labelHeight: Int = 354,
    
    // Last seen
    val lastConnectedAt: Long? = null
)
