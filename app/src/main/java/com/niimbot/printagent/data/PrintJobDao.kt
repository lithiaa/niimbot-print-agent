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

    // ─── LiveData queries (for UI observation) ────────────────────────────

    @Query("SELECT * FROM print_jobs WHERE id = :id")
    fun getById(id: Long): LiveData<PrintJob?>

    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY priority DESC, createdAt ASC")
    fun getByStatus(status: PrintStatus): LiveData<List<PrintJob>>

    @Query("SELECT * FROM print_jobs WHERE status IN (:statuses) ORDER BY priority DESC, createdAt ASC")
    fun getByStatuses(statuses: List<PrintStatus>): LiveData<List<PrintJob>>

    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getAllPaged(limit: Int, offset: Int): LiveData<List<PrintJob>>

    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    fun getNextPending(status: PrintStatus = PrintStatus.PENDING): LiveData<PrintJob?>

    // ─── Suspend queries (for server/service, not UI) ─────────────────────

    @Query("SELECT * FROM print_jobs WHERE id = :id")
    suspend fun getByIdSync(id: Long): PrintJob?

    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY priority DESC, createdAt ASC")
    suspend fun getByStatusSync(status: PrintStatus): List<PrintJob>

    @Query("SELECT * FROM print_jobs WHERE status = :status ORDER BY priority DESC, createdAt ASC LIMIT 1")
    suspend fun getNextPendingSync(status: PrintStatus = PrintStatus.PENDING): PrintJob?

    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllPagedSync(limit: Int, offset: Int): List<PrintJob>

    // ─── Mutations ────────────────────────────────────────────────────────

    @Query("DELETE FROM print_jobs WHERE status = :status")
    suspend fun deleteByStatus(status: PrintStatus): Int

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = :status")
    suspend fun countByStatus(status: PrintStatus): Int

    @Query("UPDATE print_jobs SET status = :status, errorMessage = :error, updatedAt = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun updateStatus(id: Long, status: PrintStatus, error: String?): Int

    @Query("UPDATE print_jobs SET retryCount = retryCount + 1, updatedAt = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun incrementRetry(id: Long): Int
}
