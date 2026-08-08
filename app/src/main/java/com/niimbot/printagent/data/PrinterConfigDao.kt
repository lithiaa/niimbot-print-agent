package com.niimbot.printagent.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PrinterConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: PrinterConfig): Long
    
    @Update
    suspend fun update(config: PrinterConfig): Int
    
    @Query("SELECT * FROM printer_configs WHERE id = 1")
    fun getConfig(): LiveData<PrinterConfig?>
    
    @Query("SELECT * FROM printer_configs WHERE id = 1")
    suspend fun getConfigSync(): PrinterConfig?
    
    @Query("DELETE FROM printer_configs")
    suspend fun clear(): Int
}