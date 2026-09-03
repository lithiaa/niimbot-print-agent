package com.niimbot.printagent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Niimbot Bluetooth Manager - Handles BLE communication with Niimbot B1/B1 Pro printers.
 */
@SuppressLint("MissingPermission")
class NiimbotBluetoothManager(private val context: Context) {

    data class LabelRollIdentity(
        val barcode: String,
        val serialNumber: String
    )

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        val WRITE_UUID: UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
        val NOTIFY_UUID: UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val MTU_SIZE = 185

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCONNECTING = 3

        const val STATUS_PRINTING = 0x01
        const val STATUS_DONE = 0x02
        const val STATUS_ERROR = 0x03
        const val STATUS_COVER_OPEN = 0x05
        const val STATUS_LOW_BATTERY = 0x06

        const val MAX_RETRY = 3
        const val RETRY_DELAY_MS = 2000L

        private const val CMD_CONNECT = 0xC1
        private const val CMD_SET_DENSITY = 0x21
        private const val CMD_SET_LABEL_TYPE = 0x23
        private const val CMD_PRINT_START = 0x01
        private const val CMD_PRINT_PAGE_START = 0x03
        private const val CMD_PRINT_STATUS = 0xA3
        private const val CMD_SET_PAGE_SIZE = 0x13
        private const val CMD_PRINTER_INFO = 0x40
        private const val CMD_PRINTER_STATUS_DATA = 0xA5
        private const val CMD_HEARTBEAT = 0xDC
        private const val CMD_GET_RFID = 0x1A
        private const val CMD_PRINT_EMPTY_ROW = 0x84
        private const val CMD_PRINT_BITMAP_ROW = 0x85
        private const val CMD_PRINT_END_PAGE = 0xE3
        private const val CMD_PRINT_END = 0xF3

        private const val RESPONSE_CONNECT = 0xC2
        private const val RESPONSE_SET_DENSITY = 0x31
        private const val RESPONSE_SET_LABEL_TYPE = 0x33
        private const val RESPONSE_PRINT_START = 0x02
        private const val RESPONSE_PRINT_PAGE_START = 0x04
        private const val RESPONSE_PRINT_STATUS = 0xB3
        private const val RESPONSE_SET_PAGE_SIZE = 0x14
        private const val RESPONSE_PRINTER_MODEL = 0x48
        private const val RESPONSE_PRINTER_STATUS_DATA = 0xB5
        private const val RESPONSE_HEARTBEAT = 0xD9
        private const val RESPONSE_GET_RFID = 0x1B
        private const val RESPONSE_PRINT_END_PAGE = 0xE4
        private const val RESPONSE_PRINT_END = 0xF4

        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val PRINT_TIMEOUT_MS = 25_000L
        private const val PRINT_STATUS_RESPONSE_TIMEOUT_MS = 900L
        private const val WRITE_DELAY_MS = 15L
        private const val WRITE_RETRY_DELAY_MS = 20L
        private const val MAX_WRITE_ATTEMPTS = 5
        private const val CONNECTION_SETTLE_MS = 200L
        private const val MTU_FALLBACK_MS = 500L
        private const val ROW_RUN_LIMIT = 200

        private val CONNECTION_PACKET = byteArrayOf(
            0x03,
            0x55,
            0x55,
            CMD_CONNECT.toByte(),
            0x01,
            0x01,
            0xC1.toByte(),
            0xAA.toByte(),
            0xAA.toByte()
        )
    }

    private data class ProtocolFrame(
        val command: Int,
        val data: ByteArray
    )

    private enum class PrintTask { B1, V4 }

    private data class PrinterProfile(
        val modelId: Int?,
        val displayName: String,
        val task: PrintTask,
        val dpi: Int,
        val maxWidthPx: Int?
    )

    private data class PendingResponse(
        val requestCommand: Int,
        val waiter: CompletableDeferred<ProtocolFrame>
    )

    private data class EncodedRow(
        val bytes: ByteArray,
        val blackPixels: Int
    )

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val connectionState = MutableLiveData<Int>(STATE_DISCONNECTED)
    private val printStatus = MutableLiveData<Int>()
    private val discoveredDevices = MutableLiveData<List<BluetoothDevice>>()

    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeScope = CoroutineScope(Dispatchers.IO)
    private val printMutex = Mutex()
    private var writeJob: Job? = null
    private var sessionInitJob: Job? = null
    private var sessionInitializationStarted = false

    private val pendingResponses = mutableMapOf<Int, PendingResponse>()
    private val pendingResponsesLock = Any()
    private var notifyBuffer = ByteArray(0)

    private var targetMac: String? = null
    private var connectedMac: String? = null
    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var connectionPacketSent = false
    private var retryCount = 0
    @Volatile
    private var printerProfile = PrinterProfile(
        modelId = null,
        displayName = "Niimbot (compatibility mode)",
        task = PrintTask.V4,
        dpi = 300,
        maxWidthPx = null
    )

    val connectionStateLive: LiveData<Int> = connectionState
    val printStatusLive: LiveData<Int> = printStatus
    val discoveredDevicesLive: LiveData<List<BluetoothDevice>> = discoveredDevices

    // ===================== SCAN =====================

    fun startScan() {
        Log.d("NiimbotBLE", "startScan() called")
        if (!isBluetoothEnabled()) {
            Log.w("NiimbotBLE", "Bluetooth not enabled")
            return
        }

        try {
            val scanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            scanner.startScan(scanCallback)
            Log.d("NiimbotBLE", "Scanner started successfully")
        } catch (e: SecurityException) {
            Log.e("NiimbotBLE", "SecurityException during scan: ${e.message}")
        } catch (e: Exception) {
            Log.e("NiimbotBLE", "Exception during scan: ${e.message}")
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(10000)
            stopScan()
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w("NiimbotBLE", "stopScan error: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                result.scanRecord?.deviceName ?: device.name
            } catch (e: SecurityException) {
                null
            }

            if (name != null) {
                Log.d("NiimbotBLE", "Discovered device: $name - ${device.address}")
            }

            if (name?.contains("B1", true) == true ||
                name?.contains("NIIMBOT", true) == true ||
                name?.startsWith("B", true) == true
            ) {
                updateDiscoveredDevices(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("NiimbotBLE", "Scan failed: $errorCode")
        }
    }

    private fun updateDiscoveredDevices(device: BluetoothDevice) {
        val current = discoveredDevices.value ?: emptyList()
        if (!current.any { it.address == device.address }) {
            discoveredDevices.postValue(current + device)
        }
    }

    // ===================== CONNECT =====================

    @Synchronized
    fun connect(mac: String, callback: (Boolean) -> Unit) {
        val requestedMac = mac.trim()
        if (requestedMac.isEmpty()) {
            callback(false)
            return
        }

        if (gatt != null && connectedMac.equals(requestedMac, ignoreCase = true)) {
            callback(true)
            return
        }

        if (
            gatt != null &&
            connectedMac == null &&
            targetMac.equals(requestedMac, ignoreCase = true)
        ) {
            val pendingCallback = connectionCallback
            connectionCallback = { success ->
                pendingCallback?.invoke(success)
                callback(success)
            }
            return
        }

        // A connected GATT is reusable only for the same physical printer.
        // Close it before switching so writes cannot keep going to the old MAC.
        closeCurrentGatt()
        connectionCallback?.invoke(false)

        targetMac = requestedMac
        connectedMac = null
        connectionCallback = callback
        connectionState.postValue(STATE_CONNECTING)

        try {
            val device = bluetoothAdapter.getRemoteDevice(requestedMac)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e("NiimbotBLE", "connect() error: ${e.message}")
            finishConnectionAttempt(false)
            connectionState.postValue(STATE_DISCONNECTED)
        }
    }

    @Synchronized
    fun disconnect() {
        // Explicit disconnects, including "Forget printer", must not be retried by a
        // late callback from the GATT that is being closed.
        targetMac = null
        connectedMac = null
        retryCount = 0
        closeCurrentGatt()
        finishConnectionAttempt(false)
        connectionState.postValue(STATE_DISCONNECTED)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@NiimbotBluetoothManager.gatt) {
                Log.d("NiimbotBLE", "Ignoring state from stale GATT: ${gatt.device.address}")
                gatt.close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("NiimbotBLE", "Connected, discovering services...")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w("NiimbotBLE", "Disconnected: status=$status")
                    handleDisconnect(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@NiimbotBluetoothManager.gatt) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupCharacteristics(gatt)
            } else {
                Log.e("NiimbotBLE", "Service discovery failed: $status")
                failConnection(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== this@NiimbotBluetoothManager.gatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NiimbotBLE", "Write failed: $status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt === this@NiimbotBluetoothManager.gatt) {
                handleNotify(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt === this@NiimbotBluetoothManager.gatt) {
                handleNotify(value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== this@NiimbotBluetoothManager.gatt) return

            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("NiimbotBLE", "Notifications enabled")
                // GATT permits only one control operation at a time. Request MTU after
                // the descriptor write completes, then begin the Niimbot handshake.
                val mtuRequested = try {
                    gatt.requestMtu(MTU_SIZE)
                } catch (e: Exception) {
                    Log.w("NiimbotBLE", "MTU request failed: ${e.message}")
                    false
                }
                sessionInitJob?.cancel()
                sessionInitJob = writeScope.launch {
                    delay(if (mtuRequested) MTU_FALLBACK_MS else 0L)
                    beginGattSession(gatt)
                }
            } else if (descriptor.uuid == CCCD_UUID) {
                Log.e("NiimbotBLE", "Failed to enable notifications: $status")
                failConnection(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@NiimbotBluetoothManager.gatt) return
            Log.i("NiimbotBLE", "MTU changed to $mtu (status=$status)")
            sessionInitJob?.cancel()
            beginGattSession(gatt)
        }
    }

    private fun beginGattSession(sessionGatt: BluetoothGatt) {
        if (sessionGatt !== gatt || sessionInitializationStarted) return
        sessionInitializationStarted = true
        startWriteLoop()

        sessionInitJob = writeScope.launch {
            try {
                Log.d("NiimbotBLE", "TX handshake C1")
                sendPacket(CONNECTION_PACKET.copyOf())
                connectionPacketSent = true

                // B1 Pro firmware needs time to arm after the raw C1 packet. Sending
                // SetDensity immediately makes this unit silently ignore the command.
                delay(CONNECTION_SETTLE_MS)
                if (sessionGatt !== gatt) return@launch

                printerProfile = detectPrinterProfile()
                if (printerProfile.task == PrintTask.B1) {
                    performB1Handshake()
                }
                Log.i(
                    "NiimbotBLE",
                    "Detected ${printerProfile.displayName}: task=${printerProfile.task}, " +
                        "dpi=${printerProfile.dpi}, modelId=${printerProfile.modelId ?: "unknown"}"
                )

                connectedMac = sessionGatt.device.address
                connectionState.postValue(STATE_CONNECTED)
                retryCount = 0
                finishConnectionAttempt(true)
            } catch (e: Exception) {
                Log.e("NiimbotBLE", "Session handshake failed", e)
                failConnection(sessionGatt)
            }
        }
    }

    private fun startWriteLoop() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                val data = writeChannel.receive()
                try {
                    writeCharacteristic?.let { characteristic ->
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        var accepted = false
                        for (attempt in 0 until MAX_WRITE_ATTEMPTS) {
                            val currentGatt = gatt ?: break
                            @Suppress("DEPRECATION")
                            characteristic.value = data
                            @Suppress("DEPRECATION")
                            accepted = currentGatt.writeCharacteristic(characteristic)
                            if (accepted) break
                            if (attempt < MAX_WRITE_ATTEMPTS - 1) {
                                delay(WRITE_RETRY_DELAY_MS)
                            }
                        }
                        if (!accepted) {
                            Log.e(
                                "NiimbotBLE",
                    "GATT menolak penulisan setelah $MAX_WRITE_ATTEMPTS percobaan"
                            )
                        }
                    }
                    delay(WRITE_DELAY_MS)
                } catch (e: Exception) {
                    Log.e("NiimbotBLE", "Write error: ${e.message}")
                }
            }
        }
    }

    private fun setupCharacteristics(gatt: BluetoothGatt) {
        var service = gatt.getService(SERVICE_UUID)
        var writeCharUuid = WRITE_UUID
        var notifyCharUuid = NOTIFY_UUID

        if (service == null) {
            val isscServiceUuid = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
            service = gatt.getService(isscServiceUuid)
            if (service != null) {
                writeCharUuid = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
                notifyCharUuid = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
            }
        }

        if (service == null) {
            Log.e("NiimbotBLE", "No supported Niimbot service found")
            failConnection(gatt)
            return
        }

        writeCharacteristic = service.getCharacteristic(writeCharUuid)
        notifyCharacteristic = service.getCharacteristic(notifyCharUuid)

        if (writeCharacteristic == null || notifyCharacteristic == null) {
            Log.e("NiimbotBLE", "Failed to get characteristics for service")
            failConnection(gatt)
            return
        }

        writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.setCharacteristicNotification(notifyCharacteristic, true)
        val descriptor = notifyCharacteristic?.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e("NiimbotBLE", "Notification characteristic has no CCCD")
            failConnection(gatt)
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

    }

    private fun handleDisconnect(status: Int) {
        val disconnectedMac = targetMac
        closeCurrentGatt()
        connectedMac = null
        writeJob?.cancel()
        connectionPacketSent = false
        failPendingResponses("Printer terputus")
        connectionState.postValue(STATE_DISCONNECTED)
        finishConnectionAttempt(false)

        if (disconnectedMac != null && retryCount < MAX_RETRY) {
            retryCount++
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS * retryCount)
                if (targetMac.equals(disconnectedMac, ignoreCase = true)) {
                    connect(disconnectedMac) { }
                }
            }
        }
    }

    private fun finishConnectionAttempt(success: Boolean) {
        val callback = connectionCallback
        connectionCallback = null
        callback?.invoke(success)
    }

    private fun failConnection(failedGatt: BluetoothGatt) {
        if (failedGatt !== gatt) return
        closeCurrentGatt()
        connectedMac = null
        finishConnectionAttempt(false)
        connectionState.postValue(STATE_DISCONNECTED)
    }

    private fun closeCurrentGatt() {
        val currentGatt = gatt
        // Clear first so a late callback cannot mutate a replacement connection.
        gatt = null
        writeJob?.cancel()
        sessionInitJob?.cancel()
        sessionInitJob = null
        sessionInitializationStarted = false
        writeCharacteristic = null
        notifyCharacteristic = null
        connectionPacketSent = false
        printerProfile = PrinterProfile(
            modelId = null,
            displayName = "Niimbot (compatibility mode)",
            task = PrintTask.V4,
            dpi = 300,
            maxWidthPx = null
        )
        notifyBuffer = ByteArray(0)
        while (writeChannel.tryReceive().isSuccess) {
            // Discard packets queued for a GATT that is no longer current.
        }
        failPendingResponses("Printer terputus")

        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (e: Exception) {
            Log.w("NiimbotBLE", "close GATT error: ${e.message}")
        }
    }

    // ===================== NOTIFICATIONS =====================

    private fun handleNotify(data: ByteArray?) {
        if (data == null || data.isEmpty()) {
            return
        }

        val frames = mutableListOf<ProtocolFrame>()
        synchronized(pendingResponsesLock) {
            notifyBuffer += data

            while (true) {
                val headerIndex = findFrameHeader(notifyBuffer)
                if (headerIndex < 0) {
                    notifyBuffer = if (notifyBuffer.lastOrNull() == 0x55.toByte()) {
                        byteArrayOf(0x55)
                    } else {
                        ByteArray(0)
                    }
                    break
                }

                if (headerIndex > 0) {
                    notifyBuffer = notifyBuffer.copyOfRange(headerIndex, notifyBuffer.size)
                }

                if (notifyBuffer.size < 4) {
                    break
                }

                val dataLength = notifyBuffer[3].toInt() and 0xFF
                val frameLength = dataLength + 7
                if (notifyBuffer.size < frameLength) {
                    break
                }

                val frameBytes = notifyBuffer.copyOfRange(0, frameLength)
                notifyBuffer = notifyBuffer.copyOfRange(frameLength, notifyBuffer.size)

                if (frameBytes[frameLength - 2] != 0xAA.toByte() ||
                    frameBytes[frameLength - 1] != 0xAA.toByte() ||
                    !isValidFrameCrc(frameBytes)
                ) {
                    notifyBuffer = byteArrayOf(0x55) + notifyBuffer
                    continue
                }

                val command = frameBytes[2].toInt() and 0xFF
                val frameData = frameBytes.copyOfRange(4, 4 + dataLength)
                frames += ProtocolFrame(command, frameData)
            }
        }

        frames.forEach(::routeResponse)
    }

    private fun findFrameHeader(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == 0x55.toByte() && bytes[index + 1] == 0x55.toByte()) {
                return index
            }
        }
        return -1
    }

    private fun isValidFrameCrc(frame: ByteArray): Boolean {
        val command = frame[2].toInt() and 0xFF
        val dataLength = frame[3].toInt() and 0xFF
        var crc = command xor dataLength
        for (index in 0 until dataLength) {
            crc = crc xor (frame[4 + index].toInt() and 0xFF)
        }
        return frame[4 + dataLength].toInt() and 0xFF == crc
    }

    private fun routeResponse(frame: ProtocolFrame) {
        Log.d(
            "NiimbotBLE",
            "RX 0x${frame.command.toString(16).padStart(2, '0')} " +
                frame.data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        )

        if (frame.command == 0x00 || frame.command == 0xDB) {
            val activeCommand = synchronized(pendingResponsesLock) {
                pendingResponses.values.firstOrNull()?.requestCommand
            }
            val errorCode = frame.data.firstOrNull()?.toInt()?.and(0xFF)
            val message = buildString {
                append("Printer menolak")
                if (activeCommand != null) {
                    append(" perintah 0x")
                    append(activeCommand.toString(16).padStart(2, '0'))
                }
                append(": respons 0x")
                append(frame.command.toString(16).padStart(2, '0'))
                if (errorCode != null) {
                    append(" kesalahan 0x")
                    append(errorCode.toString(16).padStart(2, '0'))
                    append(" (")
                    append(describePrinterError(errorCode))
                    append(')')
                }
            }
            failPendingResponses(message)
            return
        }

        if (frame.command == RESPONSE_PRINT_STATUS) {
            val page = if (frame.data.size >= 2) {
                ((frame.data[0].toInt() and 0xFF) shl 8) or (frame.data[1].toInt() and 0xFF)
            } else {
                0
            }
            printStatus.postValue(if (page >= 1) STATUS_DONE else STATUS_PRINTING)
        }

        val waiter = synchronized(pendingResponsesLock) {
            pendingResponses.remove(frame.command)
        }
        waiter?.waiter?.complete(frame)
    }

    private fun failPendingResponses(message: String) {
        val waiters = synchronized(pendingResponsesLock) {
            val current = pendingResponses.values.map { it.waiter }
            pendingResponses.clear()
            current
        }
        waiters.forEach { it.completeExceptionally(IllegalStateException(message)) }
    }

    private fun describePrinterError(code: Int): String = when (code) {
        0x01 -> "penutup terbuka"
        0x02 -> "kertas tidak tersedia"
        0x06 -> "data cetak atau urutan perintah tidak valid"
        0x10 -> "jenis kertas tidak sesuai"
        0x11 -> "pengaturan kertas gagal"
        0x13 -> "pengaturan kepekatan gagal"
        0x14 -> "penulisan RFID gagal"
        else -> "kesalahan printer"
    }

    // ===================== PRINT =====================

    fun readLabelRollIdentity(callback: (LabelRollIdentity?, String?) -> Unit) {
        if (connectionState.value != STATE_CONNECTED || writeCharacteristic == null) {
            callback(null, "Printer tidak terhubung")
            return
        }

        writeScope.launch {
            printMutex.withLock {
                try {
                    ensureConnectionPacket()
                    val frame = sendCommand(
                        CMD_GET_RFID,
                        byteArrayOf(0x01),
                        RESPONSE_GET_RFID
                    )
                    callback(parseLabelRollIdentity(frame.data), null)
                } catch (e: Exception) {
                    Log.w("NiimbotBLE", "Unable to read label roll identity", e)
                    callback(null, e.message ?: "Tidak dapat membaca rol label")
                }
            }
        }
    }

    private fun parseLabelRollIdentity(data: ByteArray): LabelRollIdentity? {
        if (data.size <= 1) return null
        var offset = 8 // RFID UUID

        fun readVariableString(): String? {
            if (offset >= data.size) return null
            val length = data[offset].toInt() and 0xFF
            offset++
            if (offset + length > data.size) return null
            val value = data.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            offset += length
            return value
        }

        val barcode = readVariableString() ?: return null
        val serialNumber = readVariableString() ?: return null
        Log.d("NiimbotBLE", "RFID roll identity received")
        return LabelRollIdentity(barcode, serialNumber)
    }

    fun printBitmap(bitmap: Bitmap, callback: (Boolean, String?) -> Unit) {
        if (connectionState.value != STATE_CONNECTED || writeCharacteristic == null) {
            callback(false, "Printer tidak terhubung")
            return
        }

        writeScope.launch {
            printMutex.withLock {
                var printableBitmap: Bitmap? = null
                try {
                    ensureConnectionPacket()
                    val profile = printerProfile
                    val pageBitmap = adaptBitmapForPrinter(bitmap, profile)
                    printableBitmap = pageBitmap

                    sendCommand(
                        CMD_SET_DENSITY,
                        byteArrayOf(0x03),
                        RESPONSE_SET_DENSITY
                    )
                    sendCommand(
                        CMD_SET_LABEL_TYPE,
                        byteArrayOf(0x01),
                        RESPONSE_SET_LABEL_TYPE
                    )
                    sendPrintStart(profile)

                    if (profile.task == PrintTask.B1) {
                        sendCommand(
                            CMD_PRINT_PAGE_START,
                            byteArrayOf(0x01),
                            RESPONSE_PRINT_PAGE_START
                        )
                    } else {
                        sendPacket(buildFrame(CMD_PRINT_STATUS, byteArrayOf(0x01)))
                        delay(30)
                    }

                    sendCommand(
                        CMD_SET_PAGE_SIZE,
                        buildPageSizeData(profile.task, pageBitmap),
                        RESPONSE_SET_PAGE_SIZE
                    )

                    sendRows(pageBitmap)

                    sendCommand(
                        CMD_PRINT_END_PAGE,
                        byteArrayOf(0x01),
                        RESPONSE_PRINT_END_PAGE
                    )

                    val pageConfirmed = awaitPageCompletion()

                    sendCommand(
                        CMD_PRINT_END,
                        byteArrayOf(0x01),
                        RESPONSE_PRINT_END
                    )
                    if (!pageConfirmed) {
                        throw IllegalStateException(
                            "Pencetakan tidak terkonfirmasi setelah ${PRINT_TIMEOUT_MS / 1000} detik; " +
                                "perintah akhir cetak sudah dikirim sehingga label telah keluar"
                        )
                    }
                    callback(true, null)
                } catch (e: Exception) {
                    Log.e("NiimbotBLE", "Print failed", e)
                    callback(false, e.message ?: "Pencetakan gagal")
                } finally {
                    if (printableBitmap != null && printableBitmap !== bitmap) {
                        printableBitmap.recycle()
                    }
                }
            }
        }
    }

    private suspend fun sendPrintStart(profile: PrinterProfile) {
        sendCommand(
            CMD_PRINT_START,
            if (profile.task == PrintTask.B1) {
                byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
            } else {
                byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)
            },
            RESPONSE_PRINT_START
        )
    }

    private suspend fun detectPrinterProfile(): PrinterProfile {
        var protocolVersion: Int? = null
        var modelId: Int? = null

        runCatching {
            val status = sendCommand(
                CMD_PRINTER_STATUS_DATA,
                byteArrayOf(0x01),
                RESPONSE_PRINTER_STATUS_DATA,
                timeoutMs = 1000L
            )
            if (status.data.size >= 13) {
                val rawVersion = (status.data[11].toInt() and 0xFF) * 100 +
                    (status.data[12].toInt() and 0xFF)
                protocolVersion = when {
                    rawVersion in 204 until 300 -> 3
                    rawVersion >= 302 -> 5
                    rawVersion >= 300 -> 4
                    else -> null
                }
            }
        }.onFailure { Log.w("NiimbotBLE", "Protocol version probe unavailable: ${it.message}") }

        runCatching {
            val model = sendCommand(
                CMD_PRINTER_INFO,
                byteArrayOf(0x08),
                RESPONSE_PRINTER_MODEL,
                timeoutMs = 1000L
            )
            if (model.data.isNotEmpty()) {
                modelId = if (model.data.size >= 2) {
                    ((model.data[0].toInt() and 0xFF) shl 8) or
                        (model.data[1].toInt() and 0xFF)
                } else {
                    (model.data[0].toInt() and 0xFF) shl 8
                }
            }
        }.onFailure { Log.w("NiimbotBLE", "Printer model probe unavailable: ${it.message}") }

        return when (modelId) {
            0x1000 -> PrinterProfile(modelId, "Niimbot B1", PrintTask.B1, 203, 384)
            0x1001 -> PrinterProfile(modelId, "Niimbot B1 Pro", PrintTask.V4, 300, 584)
            0x1002 -> PrinterProfile(modelId, "Niimbot B1 SE", PrintTask.B1, 203, 384)
            else -> if (protocolVersion == 3) {
                PrinterProfile(modelId, "Niimbot protocol 3", PrintTask.B1, 203, 384)
            } else {
                PrinterProfile(modelId, "Niimbot protocol V4", PrintTask.V4, 300, null)
            }
        }
    }

    private suspend fun performB1Handshake() {
        runCatching {
            sendCommand(
                CMD_PRINTER_STATUS_DATA,
                byteArrayOf(0x01),
                RESPONSE_PRINTER_STATUS_DATA,
                timeoutMs = 1000L
            )
        }
        val infoTypes = intArrayOf(0x08, 0x0B, 0x0D, 0x0A, 0x07, 0x03, 0x0C, 0x09)
        infoTypes.forEach { infoType ->
            runCatching {
                sendCommand(
                    CMD_PRINTER_INFO,
                    byteArrayOf(infoType.toByte()),
                    CMD_PRINTER_INFO + infoType,
                    timeoutMs = 600L
                )
            }
        }
        runCatching {
            sendCommand(
                CMD_HEARTBEAT,
                byteArrayOf(0x04),
                RESPONSE_HEARTBEAT,
                timeoutMs = 1000L
            )
        }
    }

    private fun adaptBitmapForPrinter(bitmap: Bitmap, profile: PrinterProfile): Bitmap {
        if (profile.dpi == 300 && profile.maxWidthPx == null) return bitmap

        val scale = profile.dpi / 300f
        var targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(8)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        profile.maxWidthPx?.let { targetWidth = targetWidth.coerceAtMost(it) }
        if (profile.task == PrintTask.B1 && profile.dpi == 203) {
            targetWidth = ((targetWidth + 4) / 8 * 8)
                .coerceAtMost(profile.maxWidthPx ?: targetWidth)
        }

        if (targetWidth == bitmap.width && targetHeight == bitmap.height) return bitmap
        Log.i(
            "NiimbotBLE",
            "Raster adapted ${bitmap.width}x${bitmap.height} -> ${targetWidth}x$targetHeight " +
                "for ${profile.displayName}"
        )
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false)
    }

    private fun buildPageSizeData(task: PrintTask, bitmap: Bitmap): ByteArray {
        val base = byteArrayOf(
            ((bitmap.height shr 8) and 0xFF).toByte(),
            (bitmap.height and 0xFF).toByte(),
            ((bitmap.width shr 8) and 0xFF).toByte(),
            (bitmap.width and 0xFF).toByte(),
            0x00,
            0x01
        )
        return if (task == PrintTask.B1) base else {
            base + byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        }
    }

    private suspend fun ensureConnectionPacket() {
        if (!connectionPacketSent) {
            connectionPacketSent = true
            sendPacket(CONNECTION_PACKET.copyOf())
        }
    }

    private suspend fun sendCommand(
        command: Int,
        data: ByteArray,
        responseCommand: Int,
        timeoutMs: Long = RESPONSE_TIMEOUT_MS
    ): ProtocolFrame {
        val waiter = CompletableDeferred<ProtocolFrame>()
        synchronized(pendingResponsesLock) {
            pendingResponses[responseCommand] = PendingResponse(command, waiter)
        }

        try {
            Log.d(
                "NiimbotBLE",
                "TX 0x${command.toString(16).padStart(2, '0')} " +
                    data.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } +
                    " expecting 0x${responseCommand.toString(16).padStart(2, '0')}"
            )
            sendPacket(buildFrame(command, data))
            return try {
                withTimeout(timeoutMs) {
                    waiter.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(
                    "Tidak ada respons untuk perintah 0x${command.toString(16)} " +
                        "(mengharapkan 0x${responseCommand.toString(16)}) setelah ${timeoutMs} md",
                    e
                )
            }
        } finally {
            synchronized(pendingResponsesLock) {
                if (pendingResponses[responseCommand]?.waiter === waiter) {
                    pendingResponses.remove(responseCommand)
                }
            }
        }
    }

    private suspend fun sendPacket(packet: ByteArray) {
        writeChannel.send(packet)
        delay(WRITE_DELAY_MS)
    }

    private suspend fun sendRows(bitmap: android.graphics.Bitmap) {
        val rows = ArrayList<EncodedRow>(bitmap.height)
        for (rowIndex in 0 until bitmap.height) {
            rows += encodeRow(bitmap, rowIndex)
        }

        var rowIndex = 0
        while (rowIndex < rows.size) {
            val row = rows[rowIndex]
            var run = 1
            while (
                rowIndex + run < rows.size &&
                run < ROW_RUN_LIMIT &&
                row.bytes.contentEquals(rows[rowIndex + run].bytes)
            ) {
                run++
            }

            val rowHeader = byteArrayOf(
                ((rowIndex shr 8) and 0xFF).toByte(),
                (rowIndex and 0xFF).toByte()
            )
            val frame = if (row.blackPixels == 0) {
                buildFrame(
                    CMD_PRINT_EMPTY_ROW,
                    rowHeader + byteArrayOf(run.toByte())
                )
            } else {
                val total = row.blackPixels and 0xFFFF
                buildFrame(
                    CMD_PRINT_BITMAP_ROW,
                    rowHeader + byteArrayOf(
                        0x00,
                        (total and 0xFF).toByte(),
                        ((total shr 8) and 0xFF).toByte(),
                        run.toByte()
                    ) + row.bytes
                )
            }

            sendPacket(frame)
            rowIndex += run
        }
    }

    private fun encodeRow(bitmap: android.graphics.Bitmap, rowIndex: Int): EncodedRow {
        val stride = (bitmap.width + 7) / 8
        val rowBytes = ByteArray(stride)
        var blackPixels = 0

        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, rowIndex)
            val gray = (
                0.299 * android.graphics.Color.red(pixel) +
                    0.587 * android.graphics.Color.green(pixel) +
                    0.114 * android.graphics.Color.blue(pixel)
                ).toInt()

            if (gray < 128) {
                rowBytes[x / 8] = (
                    rowBytes[x / 8].toInt() or (0x80 shr (x % 8))
                    ).toByte()
                blackPixels++
            }
        }

        return EncodedRow(rowBytes, blackPixels)
    }

    private suspend fun awaitPageCompletion(): Boolean =
        withTimeoutOrNull(PRINT_TIMEOUT_MS) {
            var completedPages = 0
            while (completedPages < 1) {
                val waiter = CompletableDeferred<ProtocolFrame>()
                synchronized(pendingResponsesLock) {
                    pendingResponses[RESPONSE_PRINT_STATUS] = PendingResponse(
                        CMD_PRINT_STATUS,
                        waiter
                    )
                }

                val response = try {
                    sendPacket(buildFrame(CMD_PRINT_STATUS, byteArrayOf(0x01)))
                    try {
                        withTimeout(PRINT_STATUS_RESPONSE_TIMEOUT_MS) { waiter.await() }
                    } catch (_: TimeoutCancellationException) {
                        null
                    }
                } finally {
                    synchronized(pendingResponsesLock) {
                        if (pendingResponses[RESPONSE_PRINT_STATUS]?.waiter === waiter) {
                            pendingResponses.remove(RESPONSE_PRINT_STATUS)
                        }
                    }
                }

                completedPages = if (response != null && response.data.size >= 2) {
                    ((response.data[0].toInt() and 0xFF) shl 8) or
                        (response.data[1].toInt() and 0xFF)
                } else {
                    0
                }
                if (completedPages < 1) delay(150)
            }
            true
        }
            ?: false

    private fun buildFrame(command: Int, data: ByteArray): ByteArray {
        require(data.size <= 0xFF) { "Niimbot frame data is too large" }

        val frame = ByteArray(data.size + 7)
        frame[0] = 0x55
        frame[1] = 0x55
        frame[2] = command.toByte()
        frame[3] = data.size.toByte()
        System.arraycopy(data, 0, frame, 4, data.size)

        var crc = command xor data.size
        data.forEach { crc = crc xor (it.toInt() and 0xFF) }
        frame[4 + data.size] = crc.toByte()
        frame[5 + data.size] = 0xAA.toByte()
        frame[6 + data.size] = 0xAA.toByte()
        return frame
    }

    fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter.isEnabled
        } catch (e: Exception) {
            false
        }
    }

    fun cleanup() {
        writeJob?.cancel()
        writeChannel.close()
        disconnect()
    }
}
