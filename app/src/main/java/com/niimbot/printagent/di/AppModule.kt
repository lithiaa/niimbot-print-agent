package com.niimbot.printagent.di

import android.content.Context
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.server.PrintServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNiimbotBluetoothManager(@ApplicationContext context: Context): NiimbotBluetoothManager {
        return NiimbotBluetoothManager(context)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePrintServer(
        @ApplicationContext context: Context,
        database: AppDatabase,
        bleManager: NiimbotBluetoothManager
    ): PrintServer {
        return PrintServer(context, database, bleManager)
    }
}