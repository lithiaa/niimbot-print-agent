package com.niimbot.printagent.server

import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.label.LabelGenerator
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.content.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.serialization.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

// ===================== DTOs =====================

@Serializable
data class PrintRequest(
    val imageBase64: String? = null,  // Base64 PNG (optional, if not using JSON template)
    val nama: String? = null,
    val hargaJual: Long? = null,
    val sku: String? = null,
    val stok: Int? = null,
    val satuan: String = "pcs",
    val barcode: String? = null,
    val qty: Int = 1,
    val printerMac: String? = null,
    val printerModel: String = "B1",
    val printDirection: String = "top"
)

@Serializable
data class PrintResponse(
    val success: Boolean,
    val jobId: Long? = null,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val printerConnected: Boolean = false,
    val queueSize: Int = 0,
    val uptime: Long = 0
)

@Serializable
data class StatusResponse(
    val printer: PrinterStatus,
    val queue: QueueStatus,
    val stats: Stats
)

@Serializable
data class PrinterStatus(
    val connected: Boolean,
    val mac: String? = null,
    val model: String = "B1",
    val battery: Int? = null,
    val paperStatus: String = "ok"
)

@Serializable
data class QueueStatus(
    val pending: Int = 0,
    val printing: Int = 0,
    val failed: Int = 0
)

@Serializable
data class Stats(
    val totalPrinted: Long = 0,
    val totalFailed: Long = 0,
    val uptimeSeconds: Long = 0
)

// ===================== Print Server =====================

class PrintServer(
    private val context: android.content.Context,
    private val database: AppDatabase,
    private val bleManager: com.niimbot.printagent.ble.NiimbotBluetoothManager
) {
    
    private var server: io.ktor.server.application.ApplicationEngine? = null
    private val printQueue = Channel<PrintJob>(100)
    private val queueProcessor = CoroutineScope(Dispatchers.IO).launch { processQueue() }
    private val startTime = System.currentTimeMillis()
    
    // Config
    var port = 8080
    var host = "0.0.0.0"
    
    fun start() {
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json()
            }
            
            routing {
                // Health check
                get("/health") {
                    call.respond(HealthResponse(
                        printerConnected = bleManager.connectionStateLive.value == com.niimbot.printagent.ble.NiimbotBluetoothManager.STATE_CONNECTED,
                        queueSize = getPendingCount(),
                        uptime = (System.currentTimeMillis() - startTime) / 1000
                    ))
                }
                
                // Status
                get("/status") {
                    val connected = bleManager.connectionStateLive.value == com.niimbot.printagent.ble.NiimbotBluetoothManager.STATE_CONNECTED
                    val config = database.printerConfigDao().getConfigSync()
                    
                    call.respond(StatusResponse(
                        printer = PrinterStatus(
                            connected = connected,
                            mac = config?.macAddress,
                            model = config?.model ?: "B1"
                        ),
                        queue = QueueStatus(
                            pending = getPendingCount(),
                            printing = getPrintingCount(),
                            failed = getFailedCount()
                        ),
                        stats = Stats(
                            totalPrinted = getTotalPrinted(),
                            totalFailed = getTotalFailed(),
                            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
                        )
                    ))
                }
                
                // Print endpoint (multipart or JSON)
                route("/print") {
                    post {
                        val contentType = call.request.contentType()?.toString() ?: ""
                        
                        val job = if (contentType.startsWith("multipart")) {
                            handleMultipartPrint(call)
                        } else {
                            handleJsonPrint(call)
                        }
                        
                        if (job != null) {
                            // Enqueue job
                            val jobId = CoroutineScope(Dispatchers.IO).runBlocking {
                                database.printJobDao().insert(job)
                            }
                            
                            printQueue.send(job.copy(id = jobId))
                            
                            call.respond(PrintResponse(
                                success = true,
                                jobId = jobId,
                                message = "Print job queued"
                            ))
                        } else {
                            call.respond(PrintResponse(
                                success = false,
                                error = "Invalid request"
                            ))
                        }
                    }
                }
                
                // Get job status
                get("/jobs/{jobId}") {
                    val jobId = call.parameters["jobId"]?.toLongOrNull() ?: 0
                    val job = database.printJobDao().getById(jobId).value
                    if (job != null) {
                        call.respond(job)
                    } else {
                        call.response.status(io.ktor.http.HttpStatusCode.NotFound)
                        call.respond(PrintResponse(success = false, error = "Job not found"))
                    }
                }
                
                // List jobs
                get("/jobs") {
                    val status = call.parameters["status"]
                    val jobs = when (status) {
                        "pending" -> database.printJobDao().getByStatus(com.niimbot.printagent.data.PrintStatus.PENDING).value
                        "printing" -> database.printJobDao().getByStatus(com.niimbot.printagent.data.PrintStatus.PRINTING).value
                        "failed" -> database.printJobDao().getByStatus(com.niimbot.printagent.data.PrintStatus.FAILED).value
                        "done" -> database.printJobDao().getByStatus(com.niimbot.printagent.data.PrintStatus.DONE).value
                        else -> database.printJobDao().getAllPaged(50, 0).value
                    }
                    call.respond(jobs ?: emptyList())
                }
                
                // Test print
                post("/test-print") {
                    val testJob = PrintJob(
                        nama = "TEST LABEL",
                        hargaJual = 12345,
                        sku = "TEST001",
                        stok = 99,
                        satuan = "pcs",
                        qty = 1
                    )
                    printQueue.send(testJob)
                    call.respond(PrintResponse(success = true, message = "Test print sent"))
                }
            }
        }.apply { start(wait = false) }
        
        android.util.Log.i("PrintServer", "Server started on $host:$port")
    }
    
    fun stop() {
        server?.stop()
        queueProcessor.cancel()
        printQueue.close()
    }
    
    private fun handleMultipartPrint(call: io.ktor.server.request.ApplicationCall): PrintJob? {
        // TODO: Parse multipart form data
        // For now, return null to indicate not implemented
        return null
    }
    
    private fun handleJsonPrint(call: io.ktor.server.request.ApplicationCall): PrintJob? {
        val request = call.receive<PrintRequest>()
        
        // Generate bitmap if JSON template provided
        val bitmap: Bitmap? = if (request.nama != null) {
            LabelGenerator.generateLabel(
                nama = request.nama!!,
                hargaJual = request.hargaJual!!,
                sku = request.sku!!,
                stok = request.stok!!,
                satuan = request.satuan,
                barcodeData = request.barcode
            )
        } else if (request.imageBase64 != null) {
            // Decode base64 image
            val decoded = Base64.decode(request.imageBase64!!, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } else {
            null
        }
        
        // If no bitmap generated and no image, create a minimal job
        return PrintJob(
            nama = request.nama ?: "Unknown",
            hargaJual = request.hargaJual ?: 0,
            sku = request.sku ?: "000000",
            stok = request.stok ?: 0,
            satuan = request.satuan,
            barcode = request.barcode,
            qty = request.qty,
            printerMac = request.printerMac,
            printerModel = request.printerModel,
            printDirection = request.printDirection
        )
    }
    
    private suspend fun processQueue() {
        for (job in printQueue) {
            // Update status to PRINTING
            database.printJobDao().updateStatus(job.id, com.niimbot.printagent.data.PrintStatus.PRINTING, null)
            database.printLogDao().insert(
                com.niimbot.printagent.data.PrintLog(
                    printJobId = job.id,
                    action = com.niimbot.printagent.data.LogAction.PRINTING_STARTED
                )
            )
            
            // Print via BLE
            val success = printViaBle(job)
            
            if (success) {
                database.printJobDao().updateStatus(job.id, com.niimbot.printagent.data.PrintStatus.DONE, null)
                database.printLogDao().insert(
                    com.niimbot.printagent.data.PrintLog(
                        printJobId = job.id,
                        action = com.niimbot.printagent.data.LogAction.PRINTING_COMPLETED
                    )
                )
            } else {
                // Retry logic
                if (job.retryCount < 3) {
                    database.printJobDao().incrementRetry(job.id)
                    database.printJobDao().updateStatus(job.id, com.niimbot.printagent.data.PrintStatus.PENDING, "Retry")
                    printQueue.send(job.copy(retryCount = job.retryCount + 1))
                } else {
                    database.printJobDao().updateStatus(job.id, com.niimbot.printagent.data.PrintStatus.FAILED, "Max retries exceeded")
                    database.printLogDao().insert(
                        com.niimbot.printagent.data.PrintLog(
                            printJobId = job.id,
                            action = com.niimbot.printagent.data.LogAction.PRINTING_FAILED,
                            errorDetail = "Max retries exceeded"
                        )
                    )
                }
            }
            
            // Small delay between prints
            kotlinx.coroutines.delay(500)
        }
    }
    
    private fun printViaBle(job: PrintJob): Boolean {
        // Generate label bitmap
        val bitmap = LabelGenerator.generateLabel(
            nama = job.nama,
            hargaJual = job.hargaJual,
            sku = job.sku,
            stok = job.stok,
            satuan = job.satuan,
            barcodeData = job.barcode
        )
        
        // Print via BLE with timeout
        val result = kotlinx.coroutines.runBlocking {
            val channel = kotlinx.coroutines.channels.Channel<Boolean>()
            val timeoutMs = 30000L
            var completed = false
            
            bleManager.printBitmap(bitmap) { success, error ->
                if (!completed) {
                    completed = true
                    channel.send(success)
                }
            }
            
            val result = withTimeoutOrNull(timeoutMs) { channel.receive() }
            if (result == null) {
                completed = true
                android.util.Log.e("PrintServer", "BLE print timeout after ${timeoutMs}ms")
                false
            } else {
                result
            }
        }
        return result
    }
    
    // Helper functions
    private fun getPendingCount(): Int = database.printJobDao().countByStatus(com.niimbot.printagent.data.PrintStatus.PENDING)
    private fun getPrintingCount(): Int = database.printJobDao().countByStatus(com.niimbot.printagent.data.PrintStatus.PRINTING)
    private fun getFailedCount(): Int = database.printJobDao().countByStatus(com.niimbot.printagent.data.PrintStatus.FAILED)
    private fun getTotalPrinted(): Long = database.printJobDao().countByStatus(com.niimbot.printagent.data.PrintStatus.DONE)
    private fun getTotalFailed(): Long = getFailedCount().toLong()
}