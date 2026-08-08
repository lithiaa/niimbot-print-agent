package com.niimbot.printagent

import android.app.Application
import android.util.Log
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class NiimbotPrintApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("NiimbotApp", "Application created")
    }

    /**
     * Accessor for NiimbotBluetoothManager from non-Hilt contexts (e.g., UI fragments that
     * get the manager via Application rather than @Inject).
     */
    fun getNiimbotManager(): NiimbotBluetoothManager {
        return EntryPoints.get(this, NiimbotEntryPoint::class.java).getNiimbotBluetoothManager()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NiimbotEntryPoint {
        fun getNiimbotBluetoothManager(): NiimbotBluetoothManager
    }
}