package com.niimbot.printagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.niimbot.printagent.data.converters.DateConverter
import java.util.Date

/**
 * Print log for history/debugging
 */
@Entity(tableName = "print_logs")
data class PrintLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val printJobId: Long,
    val action: LogAction,
    val message: String? = null,
    val errorDetail: String? = null,
    
    @TypeConverters(DateConverter::class)
    val createdAt: Date = Date()
)

enum class LogAction {
    QUEUED,
    PRINTING_STARTED,
    PRINTING_COMPLETED,
    PRINTING_FAILED,
    RECONNECT_ATTEMPT,
    RECONNECT_SUCCESS,
    RECONNECT_FAILED,
    QUEUE_CLEARED
}