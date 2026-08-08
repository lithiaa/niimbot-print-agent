package com.niimbot.printagent

import android.app.Application
import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltAndroidApp
class NiimbotPrintApplication : Application() {

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    private var niimbotManager: com.niimbot.printagent.ble.NiimbotBluetoothManager? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize Hilt
        dagger.hilt.android.HiltAndroidApp.class.java.getAnnotation(dagger.hilt.android.HiltAndroidApp::class.java)
        // Hilt auto-injects via generated Application class
    }

    fun getNiimbotManager(): com.niimbot.printagent.ble.NiimbotBluetoothManager {
        if (niimbotManager == null) {
            // Get from Hilt EntryPoint
            val entryPoint = EntryPointAccessors.fromApplication(
                this,
                NiimbotPrintApplication_EntryPoint::class.java
            )
            niimbotManager = entryPoint.getNiimbotBluetoothManager()
        }
        return niimbotManager!!
    }

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NiimbotPrintApplication_EntryPoint {
        fun getNiimbotBluetoothManager(): com.niimbot.printagent.ble.NiimbotBluetoothManager
    }
}