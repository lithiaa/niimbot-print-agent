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
    val model: String = "B1",
    val name: String = "Niimbot B1 Pro",
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