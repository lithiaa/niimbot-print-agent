package com.niimbot.printagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.XPrinterBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.label.LabelDesign
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
        const val CHANNEL_ID = "lithia_label_print_channel"
        const val ACTION_START = "com.niimbot.printagent.START"
        const val ACTION_STOP = "com.niimbot.printagent.STOP"
        const val ACTION_TEST_PRINT = "com.niimbot.printagent.TEST_PRINT"
        const val ACTION_ENQUEUE = "com.niimbot.printagent.ENQUEUE"
        const val ACTION_RESTART_SERVER = "com.niimbot.printagent.RESTART_SERVER"
        const val EXTRA_TEST_DATA = "test_data"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_SERVER_PORT = "server_port"

        private const val TAG = "PrintService"
        private const val TYPE_XPRINTER = "XPRINTER"
    }

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var xPrinterManager: XPrinterBluetoothManager

    @Inject
    lateinit var printServer: PrintServer

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private var queueJob: Job? = null
    private var reconnectJob: Job? = null

    private val xPrinterConnectionObserver = androidx.lifecycle.Observer<Int> { state ->
        updateNotification()
        when (state) {
            XPrinterBluetoothManager.STATE_CONNECTED -> {
                reconnectJob?.cancel()
                prefs.edit().putLong("last_connected", System.currentTimeMillis()).apply()
                queueSignal.trySend(Unit)
            }
            XPrinterBluetoothManager.STATE_CONNECTING -> reconnectJob?.cancel()
            XPrinterBluetoothManager.STATE_DISCONNECTED -> scheduleReconnect()
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

        // Discard a saved configuration that belongs to the removed transport.
        if (prefs.getString("printer_type", TYPE_XPRINTER) != TYPE_XPRINTER) {
            prefs.edit()
                .remove("printer_mac")
                .remove("printer_name")
                .putString("printer_type", TYPE_XPRINTER)
                .putInt("printer_dpi", 203)
                .apply()
            serviceScope.launch {
                if (database.printerConfigDao().getConfigSync()?.printerType != TYPE_XPRINTER) {
                    database.printerConfigDao().clear()
                }
            }
        }

        // Auto-connect to saved XPrinter.
        val savedMac = prefs.getString("printer_mac", null)
        savedMac?.let { mac ->
            xPrinterManager.connect(mac) { success, error ->
                Log.i(TAG, "XPrinter auto-connect result: $success ${error.orEmpty()}")
            }
        }

        // Start queue processor
        queueJob = serviceScope.launch { processQueue() }
        queueSignal.trySend(Unit)

        observePrinterState()

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
                val testData = intent?.getStringExtra(EXTRA_TEST_DATA) ?: "LABEL UJI"
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
        xPrinterManager.cleanup()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // Remove LiveData observers
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            xPrinterManager.connectionStateLive.removeObserver(xPrinterConnectionObserver)
        }
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ─── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Cetak Label",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan cetak latar belakang untuk printer label XPrinter"
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

        val connected = xPrinterManager.connectionStateLive.value == XPrinterBluetoothManager.STATE_CONNECTED
        val statusText = if (connected) "Printer terhubung ✅" else "Printer terputus 🔴"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lithia Label Printer")
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
            "LithiaLabelPrinter::WakeLock"
        ).apply { acquire(60 * 60 * 1000L) } // acquire max 1 hour, re-acquired if needed
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    // ─── BLE State Observer ────────────────────────────────────────────────

    private fun observePrinterState() {
        // LiveData must be observed from main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            xPrinterManager.connectionStateLive.observeForever(xPrinterConnectionObserver)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val intervalMs = prefs.getLong("reconnect_interval", 5000L)
        reconnectJob = serviceScope.launch {
            delay(intervalMs)
            val mac = prefs.getString("printer_mac", null)
            mac?.let { savedMac ->
                xPrinterManager.connect(savedMac) { success, error ->
                    Log.i(TAG, "XPrinter reconnect result: $success ${error.orEmpty()}")
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
                if (!isSelectedPrinterConnected()) {
                    database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Printer tidak terhubung")
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
                    kodeHargaBeli = job.kodeHargaBeli,
                    itemQty = job.itemQty,
                    supplierCode = job.supplierCode,
                    tanggalMasuk = job.tanggalMasuk,
                    brandLogo = BitmapFactory.decodeResource(resources, R.drawable.lithia_project_logo),
                    labelDesign = LabelDesign.fromName(job.labelLayout)
                )

                val requestedCopies = job.qty.coerceAtLeast(1)
                val size = LabelSize.fromName(job.labelSize)
                val printedCopies = if (printViaXPrinterBlocking(bitmap, size, requestedCopies, job.id)) {
                    requestedCopies
                } else {
                    0
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
                        "Tercetak $printedCopies/$requestedCopies salinan; percobaan ulang otomatis dihentikan untuk menghindari duplikasi"
                    )
                } else if (database.printJobDao().getByIdSync(job.id)?.status != PrintStatus.FAILED) {
                    handlePrintFailure(job)
                }

                delay(500)
            }
        }
    }

    private suspend fun recoverInterruptedJobs() {
        database.printJobDao().getByStatusSync(PrintStatus.PRINTING).forEach { job ->
            markPrintFailed(job.id, "Pencetakan terputus; hasil akhirnya tidak diketahui sehingga percobaan ulang otomatis dihentikan")
        }
    }

    private suspend fun printViaXPrinterBlocking(
        bitmap: android.graphics.Bitmap,
        size: LabelSize,
        copies: Int,
        jobId: Long
    ): Boolean {
        val resultChannel = Channel<Boolean>(1)
        xPrinterManager.printBitmap(
            bitmap = bitmap,
            widthMm = size.widthMm,
            heightMm = size.heightMm,
            dpi = prefs.getInt("printer_dpi", 203),
            copies = copies
        ) { success, error ->
            resultChannel.trySend(success)
            if (!success) Log.e(TAG, "XPrinter print error for job #$jobId: $error")
        }
        return withTimeoutOrNull(45_000L) { resultChannel.receive() } ?: false
    }

    private fun isSelectedPrinterConnected(): Boolean =
        xPrinterManager.connectionStateLive.value == XPrinterBluetoothManager.STATE_CONNECTED

    private suspend fun handlePrintFailure(job: PrintJob) {
        if (job.retryCount < 3) {
            val nextRetry = job.retryCount + 1
            database.printJobDao().incrementRetry(job.id)
            database.printJobDao().updateStatus(job.id, PrintStatus.PENDING, "Percobaan ulang $nextRetry/3")
            delay(2000)
            queueSignal.trySend(Unit)
            Log.w(TAG, "Job #${job.id} failed — retry $nextRetry/3")
        } else {
            markPrintFailed(job.id, "Batas percobaan ulang terlampaui")
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

}
