package com.niimbot.printagent.server

import android.graphics.BitmapFactory
import android.content.Intent
import android.util.Log
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.service.PrintForegroundService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider

import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

// ===================== DTOs =====================

@Serializable
data class PrintRequest(
    val imageBase64: String? = null,   // Base64 PNG (optional)
    val nama: String? = null,
    val hargaJual: Long? = null,
    val hargaBeli: Long? = null,
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

@Serializable
data class StatusResponse(
    val printer: PrinterStatus,
    val queue: QueueStatus,
    val stats: Stats
)

// ===================== Print Server =====================

class PrintServer(
    private val context: android.content.Context,
    private val database: AppDatabase,
    private val bleManager: NiimbotBluetoothManager
) {

    private var server: ApplicationEngine? = null
    private val startTime = System.currentTimeMillis()

    // Config (settable from service)
    var port = 8080
    var host = "0.0.0.0"

    fun start() {
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json()
            }

            routing {
                // ─── Health check ────────────────────────────────────────
                get("/health") {
                    call.respond(
                        HealthResponse(
                            printerConnected = bleManager.connectionStateLive.value == NiimbotBluetoothManager.STATE_CONNECTED,
                            queueSize = getPendingCount(),
                            uptime = (System.currentTimeMillis() - startTime) / 1000
                        )
                    )
                }

                // ─── Full status ─────────────────────────────────────────
                get("/status") {
                    val connected = bleManager.connectionStateLive.value == NiimbotBluetoothManager.STATE_CONNECTED
                    val config = database.printerConfigDao().getConfigSync()

                    call.respond(
                        StatusResponse(
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
                        )
                    )
                }

                // ─── Print endpoint ───────────────────────────────────────
                route("/print") {
                    post {
                        val contentType = call.request.contentType().toString()

                        val job: PrintJob? = if (contentType.startsWith("multipart")) {
                            handleMultipartPrint(call)
                        } else {
                            handleJsonPrint(call)
                        }

                        if (job != null) {
                            val jobId = database.printJobDao().insert(job)
                            database.printLogDao().insert(
                                PrintLog(
                                    printJobId = jobId,
                                    action = LogAction.QUEUED
                                )
                            )

                            signalQueue(jobId)

                            call.respond(
                                PrintResponse(
                                    success = true,
                                    jobId = jobId,
                                    message = "Print job queued"
                                )
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                PrintResponse(
                                    success = false,
                                    error = "Invalid request: provide JSON fields (nama, hargaJual, sku, stok) or image (imageBase64 / multipart file)"
                                )
                            )
                        }
                    }
                }

                // ─── Get job by ID ────────────────────────────────────────
                get("/jobs/{jobId}") {
                    val jobId = call.parameters["jobId"]?.toLongOrNull()
                    if (jobId == null) {
                        call.respond(HttpStatusCode.BadRequest, PrintResponse(success = false, error = "Invalid jobId"))
                        return@get
                    }
                    // Use suspend DAO method (we need to add it or use runBlocking from coroutine)
                    val job = database.printJobDao().getByIdSync(jobId)
                    if (job != null) {
                        call.respond(job)
                    } else {
                        call.respond(HttpStatusCode.NotFound, PrintResponse(success = false, error = "Job not found"))
                    }
                }

                // ─── List jobs ────────────────────────────────────────────
                get("/jobs") {
                    val statusParam = call.parameters["status"]
                    val jobs = when (statusParam) {
                        "pending"  -> database.printJobDao().getByStatusSync(PrintStatus.PENDING)
                        "printing" -> database.printJobDao().getByStatusSync(PrintStatus.PRINTING)
                        "failed"   -> database.printJobDao().getByStatusSync(PrintStatus.FAILED)
                        "done"     -> database.printJobDao().getByStatusSync(PrintStatus.DONE)
                        else       -> database.printJobDao().getAllPagedSync(50, 0)
                    }
                    call.respond(jobs)
                }

                // ─── Test print ───────────────────────────────────────────
                post("/test-print") {
                    val testJob = PrintJob(
                        nama = "TEST LABEL",
                        hargaJual = 12345,
                        hargaBeli = 8000,
                        sku = "TEST001",
                        stok = 99,
                        satuan = "pcs",
                        qty = 1
                    )
                    val jobId = database.printJobDao().insert(testJob)
                    database.printLogDao().insert(
                        PrintLog(printJobId = jobId, action = LogAction.QUEUED)
                    )
                    signalQueue(jobId)
                    call.respond(PrintResponse(success = true, jobId = jobId, message = "Test print queued"))
                }
            }
        }.apply { start(wait = false) }

        Log.i("PrintServer", "HTTP server started on $host:$port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        Log.i("PrintServer", "HTTP server stopped")
    }

    // ─── Multipart handler ─────────────────────────────────────────────────

    private suspend fun handleMultipartPrint(call: io.ktor.server.application.ApplicationCall): PrintJob? {
        var imageBytes: ByteArray? = null
        var qty = 1

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    if (part.name == "file" || part.name == "image") {
                        imageBytes = part.streamProvider().readBytes()
                    }
                    part.dispose()
                }
                is PartData.FormItem -> {
                    if (part.name == "qty") {
                        qty = part.value.toIntOrNull() ?: 1
                    }
                    part.dispose()
                }
                else -> part.dispose()
            }
        }

        val bitmap = imageBytes?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        } ?: return null

        // For multipart, we store a "raw image" job — nama generated from timestamp
        val ts = System.currentTimeMillis()
        return PrintJob(
            nama = "RAW-$ts",
            hargaJual = 0,
            hargaBeli = 0,
            sku = "RAW",
            stok = 0,
            satuan = "pcs",
            qty = qty
        )
        // Note: actual bitmap would need to be stored/passed differently
        // For now multipart print queues a raw job and prints via BLE directly
    }

    // ─── JSON handler ──────────────────────────────────────────────────────

    private suspend fun handleJsonPrint(call: io.ktor.server.application.ApplicationCall): PrintJob? {
        return try {
            val request = call.receive<PrintRequest>()

            // Validate required fields (either JSON template OR base64 image)
            val hasTemplate = request.nama != null && request.hargaJual != null && request.sku != null
            val hasImage = request.imageBase64 != null

            if (!hasTemplate && !hasImage) return null

            PrintJob(
                nama = request.nama ?: "Unknown",
                hargaJual = request.hargaJual ?: 0,
                hargaBeli = request.hargaBeli ?: 0,
                sku = request.sku ?: "000000",
                stok = request.stok ?: 0,
                satuan = request.satuan,
                barcode = request.barcode,
                qty = request.qty,
                printerMac = request.printerMac,
                printerModel = request.printerModel,
                printDirection = request.printDirection
            )
        } catch (e: Exception) {
            Log.e("PrintServer", "Failed to parse JSON request: ${e.message}")
            null
        }
    }

    private fun signalQueue(jobId: Long) {
        val intent = Intent(context, PrintForegroundService::class.java).apply {
            action = PrintForegroundService.ACTION_ENQUEUE
            putExtra(PrintForegroundService.EXTRA_JOB_ID, jobId)
        }
        // The HTTP server only exists while this foreground service is already running.
        context.startService(intent)
    }

    // ─── DAO helpers ───────────────────────────────────────────────────────

    private suspend fun getPendingCount(): Int =
        database.printJobDao().countByStatus(PrintStatus.PENDING)

    private suspend fun getPrintingCount(): Int =
        database.printJobDao().countByStatus(PrintStatus.PRINTING)

    private suspend fun getFailedCount(): Int =
        database.printJobDao().countByStatus(PrintStatus.FAILED)

    private suspend fun getTotalPrinted(): Long =
        database.printJobDao().countByStatus(PrintStatus.DONE).toLong()

    private suspend fun getTotalFailed(): Long =
        database.printJobDao().countByStatus(PrintStatus.FAILED).toLong()
}
