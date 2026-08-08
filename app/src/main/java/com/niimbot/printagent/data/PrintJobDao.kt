package com.niimbot.printagent.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PrintJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: PrintJob): Long
    
    @Update
    suspend fun update(job: PrintJob): Int
    
    @Query("SELECT * FROM print_jobs WHERE id = :id")
    fun getById(id: Long): LiveData<PrintJob?>
    
    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY priority DESC, createdAt ASC")
    fun getByStatus(status: PrintStatus): LiveData<List<PrintJob>>
    
    @Query("SELECT * FROM print_jobs WHERE status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    fun getByStatuses(statuses: List<PrintStatus>): LiveData<List<PrintJob>>
    
    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getAllPaged(limit: Int, offset: Int): LiveData<List<PrintJob>>
    
    @Query("DELETE FROM print_jobs WHERE status = :status")
    suspend fun deleteByStatus(status: PrintStatus): Int
    
    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = :status")
    suspend fun countByStatus(status: PrintStatus): Int
    
    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    fun getNextPending(): LiveData<PrintJob?>
    
    @Query("UPDATE print_jobs SET status = :status, errorMessage = :error, updatedAt = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun updateStatus(id: Long, status: PrintStatus, error: String?): Int
    
    @Query("UPDATE print_jobs SET retryCount = retryCount + 1, updatedAt = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun incrementRetry(id: Long): Int
}