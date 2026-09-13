package com.niimbot.printagent

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LithiaLabelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("LithiaLabelApp", "Application created")
    }
}
