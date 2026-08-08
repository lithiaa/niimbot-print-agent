package com.niimbot.printagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.server.PrintServer
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PrintForegroundService : Service(), LifecycleObserver {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "niimbot_print_channel"
        const val ACTION_START = "com.niimbot.printagent.START"
        const val ACTION_STOP = "com.niimbot.printagent.STOP"
        const val ACTION_TEST_PRINT = "com.niimbot.printagent.TEST_PRINT"
        const val EXTRA_TEST_DATA = "test_data"
    }

    @Inject
    lateinit var database: AppDatabase
    
    @Inject
    lateinit var bleManager: NiimbotBluetoothManager
    
    @Inject
    lateinit var printServer: PrintServer

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val printQueue = Channel<PrintJob>(100)
    private val queueJob = CoroutineScope(Dispatchers.IO).launch { processQueue() }
    private var reconnectJob: kotlinx.coroutines.Job? = null
    
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        
        prefs = getSharedPreferences("niimbot_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel (Android 8+)
        createNotificationChannel()
        
        // Acquire wake lock for background processing
        acquireWakeLock()
        
        // Start print server
        printServer.port = prefs.getInt("server_port", 8080)
        printServer.start()
        
        // Auto-connect to saved printer
        val savedMac = prefs.getString("printer_mac", null)
        savedMac?.let { bleManager.connect(it) { _ -> } }
        
        // Start queue processor
        lifecycle.addObserver(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onStart() {
        // Service started
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        
        when (action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_TEST_PRINT -> {
                val testData = intent.getStringExtra(EXTRA_TEST_DATA) ?: "TEST LABEL"
                sendTestPrint(testData)
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        queueJob.cancel()
        reconnectJob?.cancel()
        printServer.stop()
        bleManager.cleanup()
        releaseWakeLock()
        lifecycle.removeObserver(this)
        stopForeground(true)
        super.onDestroy()
    }

    // ===================== NOTIFICATION =====================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Niimbot Print Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background print service for Niimbot B1 Pro"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(): Notification {
        val intent = Intent(this, com.niimbot.printagent.ui.MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val connected = bleManager.connectionStateLive.value == NiimbotBluetoothManager.STATE_CONNECTED
        val statusText = if (connected) "Printer Connected" else "Printer Disconnected - Auto-reconnecting..."
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Niimbot Print Agent")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ===================== WAKE LOCK =====================
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, 
            "NiimbotPrintAgent::WakeLock"
        ).apply {
            acquire()
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    // ===================== BLE OBSERVER =====================
    
    private fun observeBleState() {
        bleManager.connectionStateLive.observeForever { state ->
            updateNotification()
            
            when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED -> {
                    android.util.Log.i("PrintService", "BLE Connected")
                    prefs.edit().putLong("last_connected", System.currentTimeMillis()).apply()
                }
                NiimbotBluetoothManager.STATE_DISCONNECTED -> {
                    android.util.Log.w("PrintService", "BLE Disconnected - scheduling reconnect")
                    scheduleReconnect()
                }
            }
        }
        
        bleManager.printStatusLive.observeForever { status ->
            when (status) {
                NiimbotBluetoothManager.STATUS_PAPER_OUT -> logError("Paper out")
                NiimbotBluetoothManager.STATUS_COVER_OPEN -> logError("Cover open")
                NiimbotBluetoothManager.STATUS_LOW_BATTERY -> logError("Low battery")
                NiimbotBluetoothManager.STATUS_ERROR -> logError("Printer error")
            }
        }
    }
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val interval = prefs.getLong("reconnect_interval", 5000)
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(interval)
            val mac = prefs.getString("printer_mac", null)
            mac?.let { bleManager.connect(it) { _ -> } }
        }
    }

    // ===================== PRINT QUEUE =====================
    
    private suspend fun processQueue() {
        for (job in printQueue) {
            if (isProcessing) continue
            isProcessing = true
            
            // Update status to PRINTING
            database.printJobDao().updateStatus(job.id, PrintStatus.PRINTING, null)
            database.printLogDao().insert(
                PrintLog(job.id, LogAction.PRINTING_STARTED)
            )
            
            // Check printer connection
            if (bleManager.connectionStateLive.value != NiimbotBluetoothManager.STATE_CONNECTED) {
                // Re-queue with delay
                database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Printer not connected")
                CoroutineScope(Dispatchers.IO).launch {
                    delay(5000)
                    printQueue.send(job)
                }
                isProcessing = false
                continue
            }
            
            // Generate label
            val bitmap = com.niimbot.printagent.label.LabelGenerator.generateLabel(
                nama = job.nama,
                hargaJual = job.hargaJual,
                sku = job.sku,
                stok = job.stok,
                satuan = job.satuan,
                barcodeData = job.barcode
            )
            
            // Print via BLE
            val success = printViaBleBlocking(bitmap)
            
            if (success) {
                database.printJobDao().updateStatus(job.id, PrintStatus.DONE, null)
                database.printLogDao().insert(
                    PrintLog(job.id, LogAction.PRINTING_COMPLETED)
                )
            } else {
                handlePrintFailure(job)
            }
            
            isProcessing = false
            delay(500) // Small delay between prints
        }
    }
    
    private fun printViaBleBlocking(bitmap: android.graphics.Bitmap): Boolean {
        return kotlinx.coroutines.runBlocking {
            val channel = kotlinx.coroutines.channels.Channel<Boolean>()
            val timeoutMs = 30000L
            var completed = false
            
            bleManager.printBitmap(bitmap) { success, error ->
                if (!completed) {
                    completed = true
                    channel.send(success)
                }
            }
            
            val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { channel.receive() }
            if (result == null) {
                completed = true
                android.util.Log.e("PrintService", "BLE print timeout after ${timeoutMs}ms")
                false
            } else {
                result
            }
        }
    }
    
    private fun handlePrintFailure(job: PrintJob) {
        if (job.retryCount < 3) {
            database.printJobDao().incrementRetry(job.id)
            database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Retry")
            printQueue.send(job.copy(retryCount = job.retryCount + 1))
        } else {
            database.printJobDao().updateStatus(job.id, PrintStatus.FAILED, "Max retries exceeded")
            database.printLogDao().insert(
                PrintLog(job.id, LogAction.PRINTING_FAILED, errorDetail = "Max retries exceeded")
            )
        }
    }
    
    private fun sendTestPrint(text: String) {
        val testJob = PrintJob(
            nama = text,
            hargaJual = 99999,
            sku = "TEST001",
            stok = 1,
            satuan = "pcs",
            qty = 1
        )
        printQueue.send(testJob)
    }
    
    private fun logError(message: String) {
        android.util.Log.e("PrintService", message)
        // Could add to print logs here
    }
}