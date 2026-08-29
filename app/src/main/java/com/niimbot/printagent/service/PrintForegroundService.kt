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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.label.LabelLayout
import com.niimbot.printagent.label.LabelSize
import com.niimbot.printagent.server.PrintServer
import com.niimbot.printagent.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class PrintForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "niimbot_print_channel"
        const val ACTION_START = "com.niimbot.printagent.START"
        const val ACTION_STOP = "com.niimbot.printagent.STOP"
        const val ACTION_TEST_PRINT = "com.niimbot.printagent.TEST_PRINT"
        const val ACTION_ENQUEUE = "com.niimbot.printagent.ENQUEUE"
        const val ACTION_RESTART_SERVER = "com.niimbot.printagent.RESTART_SERVER"
        const val EXTRA_TEST_DATA = "test_data"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_SERVER_PORT = "server_port"

        private const val TAG = "PrintService"
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

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private var queueJob: Job? = null
    private var reconnectJob: Job? = null

    // Observer references for proper removal
    private val connectionObserver = androidx.lifecycle.Observer<Int> { state ->
        updateNotification()
        when (state) {
            NiimbotBluetoothManager.STATE_CONNECTED -> {
                reconnectJob?.cancel()
                Log.i(TAG, "BLE Connected ✅")
                prefs.edit().putLong("last_connected", System.currentTimeMillis()).apply()
                queueSignal.trySend(Unit)
            }
            NiimbotBluetoothManager.STATE_CONNECTING -> {
                // Do not let a scheduled reconnect restore the old saved printer while
                // the user is deliberately switching to another one.
                reconnectJob?.cancel()
            }
            NiimbotBluetoothManager.STATE_DISCONNECTED -> {
                Log.w(TAG, "BLE Disconnected — scheduling reconnect")
                scheduleReconnect()
            }
        }
    }
    private val printStatusObserver = androidx.lifecycle.Observer<Int> { status ->
        when (status) {
            NiimbotBluetoothManager.STATUS_PAPER_OUT  -> logBleError("Paper out")
            NiimbotBluetoothManager.STATUS_COVER_OPEN -> logBleError("Cover open")
            NiimbotBluetoothManager.STATUS_LOW_BATTERY -> logBleError("Low battery")
            NiimbotBluetoothManager.STATUS_ERROR       -> logBleError("Printer error")
        }
    }

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("niimbot_prefs", Context.MODE_PRIVATE)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        // Start HTTP server
        printServer.port = prefs.getInt("server_port", 8080)
        printServer.start()

        // Auto-connect to saved printer
        val savedMac = prefs.getString("printer_mac", null)
        savedMac?.let {
            bleManager.connect(it) { success ->
                Log.i(TAG, "Auto-connect result: $success")
            }
        }

        // Start queue processor
        queueJob = serviceScope.launch { processQueue() }
        queueSignal.trySend(Unit)

        // Observe BLE state changes (use Handler for LiveData from non-main thread)
        observeBleState()

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action != ACTION_STOP) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        when (action) {
            ACTION_START -> {
                Log.i(TAG, "Foreground service started")
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopSelf()
            }
            ACTION_TEST_PRINT -> {
                val testData = intent?.getStringExtra(EXTRA_TEST_DATA) ?: "TEST LABEL"
                sendTestPrint(testData)
            }
            ACTION_ENQUEUE -> {
                queueSignal.trySend(Unit)
            }
            ACTION_RESTART_SERVER -> {
                val port = intent?.getIntExtra(EXTRA_SERVER_PORT, prefs.getInt("server_port", 8080))
                    ?: 8080
                printServer.stop()
                printServer.port = port
                printServer.start()
                Log.i(TAG, "Print server restarted on port $port")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        queueJob?.cancel()
        queueSignal.close()
        reconnectJob?.cancel()
        serviceScope.cancel()
        printServer.stop()
        bleManager.cleanup()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // Remove LiveData observers
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bleManager.connectionStateLive.removeObserver(connectionObserver)
            bleManager.printStatusLive.removeObserver(printStatusObserver)
        }
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ─── Notification ──────────────────────────────────────────────────────

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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val connected = bleManager.connectionStateLive.value == NiimbotBluetoothManager.STATE_CONNECTED
        val statusText = if (connected) "Printer Connected ✅" else "Printer Disconnected 🔴"

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

    // ─── Wake Lock ─────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NiimbotPrintAgent::WakeLock"
        ).apply { acquire(60 * 60 * 1000L) } // acquire max 1 hour, re-acquired if needed
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    // ─── BLE State Observer ────────────────────────────────────────────────

    private fun observeBleState() {
        // LiveData must be observed from main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            bleManager.connectionStateLive.observeForever(connectionObserver)
            bleManager.printStatusLive.observeForever(printStatusObserver)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val intervalMs = prefs.getLong("reconnect_interval", 5000L)
        reconnectJob = serviceScope.launch {
            delay(intervalMs)
            val mac = prefs.getString("printer_mac", null)
            mac?.let {
                bleManager.connect(it) { success ->
                    Log.i(TAG, "Reconnect result: $success")
                }
            }
        }
    }

    // ─── Print Queue ───────────────────────────────────────────────────────

    private suspend fun processQueue() {
        recoverInterruptedJobs()
        for (ignored in queueSignal) {
            while (true) {
                val job = database.printJobDao().getNextPendingSync() ?: break

                // Check printer before claiming the Room job.
                if (bleManager.connectionStateLive.value != NiimbotBluetoothManager.STATE_CONNECTED) {
                    database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Printer not connected")
                    serviceScope.launch {
                        delay(5000)
                        queueSignal.trySend(Unit)
                    }
                    break
                }

                database.printJobDao().updateStatus(job.id, PrintStatus.PRINTING, null)
                database.printLogDao().insert(
                    PrintLog(printJobId = job.id, action = LogAction.PRINTING_STARTED)
                )

                val bitmap = LabelGenerator.generateLabel(
                    nama = job.nama,
                    hargaJual = job.hargaJual,
                    hargaBeli = job.hargaBeli,
                    sku = job.sku,
                    satuan = job.satuan,
                    barcodeData = job.barcode,
                    labelSize = LabelSize.fromName(job.labelSize),
                    labelLayout = LabelLayout.fromName(job.labelLayout),
                    kodeHargaBeli = job.kodeHargaBeli
                )

                val requestedCopies = job.qty.coerceAtLeast(1)
                var printedCopies = 0
                while (
                    printedCopies < requestedCopies &&
                    printViaBleBlocking(bitmap, job.id)
                ) {
                    printedCopies++
                    // PRINT_END acknowledges the protocol session before the printer's
                    // mechanical feed/buffer is always ready for another full bitmap.
                    // Starting the next copy immediately caused longer runs to stop
                    // consistently after several labels (commonly 5 of 10).
                    if (printedCopies < requestedCopies) delay(1_200L)
                }

                if (printedCopies == requestedCopies) {
                    database.printJobDao().updateStatus(job.id, PrintStatus.DONE, null)
                    database.printLogDao().insert(
                        PrintLog(printJobId = job.id, action = LogAction.PRINTING_COMPLETED)
                    )
                    Log.i(TAG, "Job #${job.id} printed successfully")
                } else if (printedCopies > 0) {
                    markPrintFailed(
                        job.id,
                        "Printed $printedCopies/$requestedCopies copies; automatic retry stopped to avoid duplicates"
                    )
                } else {
                    handlePrintFailure(job)
                }

                delay(500)
            }
        }
    }

    private suspend fun recoverInterruptedJobs() {
        database.printJobDao().getByStatusSync(PrintStatus.PRINTING).forEach { job ->
            markPrintFailed(job.id, "Print interrupted; completion unknown, so automatic retry was stopped")
        }
    }

    private suspend fun printViaBleBlocking(bitmap: android.graphics.Bitmap, jobId: Long): Boolean {
        val resultChannel = Channel<Boolean>(1)

        bleManager.printBitmap(bitmap) { success, error ->
            resultChannel.trySend(success)
            if (!success) Log.e(TAG, "BLE print error for job #$jobId: $error")
        }

        return withTimeoutOrNull(30_000L) {
            resultChannel.receive()
        } ?: run {
            Log.e(TAG, "BLE print timeout for job #$jobId")
            false
        }
    }

    private suspend fun handlePrintFailure(job: PrintJob) {
        if (job.retryCount < 3) {
            val nextRetry = job.retryCount + 1
            database.printJobDao().incrementRetry(job.id)
            database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Retry $nextRetry/3")
            delay(2000)
            queueSignal.trySend(Unit)
            Log.w(TAG, "Job #${job.id} failed — retry $nextRetry/3")
        } else {
            markPrintFailed(job.id, "Max retries exceeded")
        }
    }

    private suspend fun markPrintFailed(jobId: Long, error: String) {
        database.printJobDao().updateStatus(jobId, PrintStatus.FAILED, error)
        database.printLogDao().insert(
            PrintLog(
                printJobId = jobId,
                action = LogAction.PRINTING_FAILED,
                errorDetail = error
            )
        )
        Log.e(TAG, "Job #$jobId failed: $error")
    }

    private fun sendTestPrint(text: String) {
        serviceScope.launch {
            val testJob = PrintJob(
                nama = text,
                hargaJual = 99999,
                hargaBeli = 75000,
                sku = "TEST001",
                satuan = "pcs",
                qty = 1
            )
            val jobId = database.printJobDao().insert(testJob)
            database.printLogDao().insert(PrintLog(printJobId = jobId, action = LogAction.QUEUED))
            queueSignal.trySend(Unit)
            Log.i(TAG, "Test print queued, job #$jobId")
        }
    }

    private fun logBleError(message: String) {
        Log.e(TAG, "BLE status: $message")
    }
}
