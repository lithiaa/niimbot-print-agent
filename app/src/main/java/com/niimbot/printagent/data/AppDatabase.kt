package com.niimbot.printagent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.niimbot.printagent.data.converters.DateConverter

@Database(
    entities = [
        PrintJob::class,
        PrinterConfig::class,
        PrintLog::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printJobDao(): PrintJobDao
    abstract fun printerConfigDao(): PrinterConfigDao
    abstract fun printLogDao(): PrintLogDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 2 only changed Kotlin defaults; the persisted schema is unchanged.
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN labelSize TEXT NOT NULL DEFAULT 'MM_50_X_30'")
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN labelLayout TEXT NOT NULL DEFAULT 'STANDARD'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN kodeHargaBeli TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN itemQty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN supplierCode TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "niimbot_print_agent.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
