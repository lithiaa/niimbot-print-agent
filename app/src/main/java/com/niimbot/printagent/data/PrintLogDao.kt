package com.niimbot.printagent.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PrintLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: PrintLog): Long
    
    @Query("SELECT * FROM print_logs WHERE printJobId = :jobId ORDER BY createdAt DESC")
    fun getByJobId(jobId: Long): LiveData<List<PrintLog>>
    
    @Query("SELECT * FROM print_logs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getAllPaged(limit: Int, offset: Int): LiveData<List<PrintLog>>
    
    @Query("DELETE FROM print_logs WHERE createdAt < :beforeDate")
    suspend fun deleteOldLogs(beforeDate: Long): Int
}