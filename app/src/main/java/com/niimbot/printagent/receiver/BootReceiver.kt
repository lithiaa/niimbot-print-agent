package com.niimbot.printagent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.niimbot.printagent.service.PrintForegroundService

/**
 * Boot Receiver - Auto-starts the print service on device boot
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "Boot completed - starting print service")
            
            val serviceIntent = Intent(context, PrintForegroundService::class.java)
            serviceIntent.action = PrintForegroundService.ACTION_START
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}