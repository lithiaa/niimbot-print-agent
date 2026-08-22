package com.niimbot.printagent.di

import android.content.Context
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.server.PrintServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
    fun provideIntegrationConfigStore(@ApplicationContext context: Context): IntegrationConfigStore =
        IntegrationConfigStore(context)

    @Provides
    @Singleton
    fun providePosApiClient(): PosApiClient {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        val json = Json {
            ignoreUnknownKeys = true
        }
        return PosApiClient(client, json)
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
