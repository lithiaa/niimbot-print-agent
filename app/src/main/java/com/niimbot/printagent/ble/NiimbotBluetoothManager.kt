package com.niimbot.printagent.ble

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
import java.util.UUID

/**
 * Niimbot Bluetooth Manager - Handles BLE communication with Niimbot B1 Pro printers.
 */
class NiimbotBluetoothManager(private val context: Context) {

    data class LabelRollStatus(
        val barcode: String,
        val serialNumber: String,
        val totalLabels: Int,
        val usedLabels: Int
    ) {
        val remainingLabels: Int get() = (totalLabels - usedLabels).coerceAtLeast(0)
    }

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
        const val STATUS_PAPER_OUT = 0x04
        const val STATUS_COVER_OPEN = 0x05
        const val STATUS_LOW_BATTERY = 0x06

        const val MAX_RETRY = 3
        const val RETRY_DELAY_MS = 2000L

        private const val CMD_CONNECT = 0xC1
        private const val CMD_SET_DENSITY = 0x21
        private const val CMD_SET_LABEL_TYPE = 0x23
        private const val CMD_PRINT_START = 0x01
        private const val CMD_PRINT_STATUS = 0xA3
        private const val CMD_SET_PAGE_SIZE = 0x13
        private const val CMD_GET_RFID = 0x1A
        private const val CMD_PRINT_EMPTY_ROW = 0x84
        private const val CMD_PRINT_BITMAP_ROW = 0x85
        private const val CMD_PRINT_END_PAGE = 0xE3
        private const val CMD_PRINT_END = 0xF3

        private const val RESPONSE_CONNECT = 0xC2
        private const val RESPONSE_SET_DENSITY = 0x31
        private const val RESPONSE_SET_LABEL_TYPE = 0x33
        private const val RESPONSE_PRINT_START = 0x02
        private const val RESPONSE_PRINT_STATUS = 0xB3
        private const val RESPONSE_SET_PAGE_SIZE = 0x14
        private const val RESPONSE_GET_RFID = 0x1B
        private const val RESPONSE_PRINT_END_PAGE = 0xE4
        private const val RESPONSE_PRINT_END = 0xF4

        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val PRINT_TIMEOUT_MS = 25_000L
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

    private val pendingResponses = mutableMapOf<Int, CompletableDeferred<ProtocolFrame>>()
    private val pendingResponsesLock = Any()
    private var notifyBuffer = ByteArray(0)

    private var targetMac: String? = null
    private var connectedMac: String? = null
    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var connectionPacketSent = false
    private var retryCount = 0

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
                                "GATT rejected write after $MAX_WRITE_ATTEMPTS attempts"
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
        failPendingResponses("Printer disconnected")
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
        notifyBuffer = ByteArray(0)
        while (writeChannel.tryReceive().isSuccess) {
            // Discard packets queued for a GATT that is no longer current.
        }
        failPendingResponses("Printer disconnected")

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
            failPendingResponses(
                "Printer rejected command: response 0x${frame.command.toString(16)}"
            )
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
        waiter?.complete(frame)
    }

    private fun failPendingResponses(message: String) {
        val waiters = synchronized(pendingResponsesLock) {
            val current = pendingResponses.values.toList()
            pendingResponses.clear()
            current
        }
        waiters.forEach { it.completeExceptionally(IllegalStateException(message)) }
    }

    // ===================== PRINT =====================

    fun readLabelRollStatus(callback: (LabelRollStatus?, String?) -> Unit) {
        if (connectionState.value != STATE_CONNECTED || writeCharacteristic == null) {
            callback(null, "Printer not connected")
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
                    callback(parseLabelRollStatus(frame.data), null)
                } catch (e: Exception) {
                    Log.w("NiimbotBLE", "Unable to read remaining labels", e)
                    callback(null, e.message ?: "Unable to read label roll")
                }
            }
        }
    }

    private fun parseLabelRollStatus(data: ByteArray): LabelRollStatus? {
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
        if (offset + 4 > data.size) return null
        val total = ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
        val used = ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        Log.d(
            "NiimbotBLE",
            "RFID roll barcode=$barcode serial=$serialNumber total=$total used=$used"
        )
        return LabelRollStatus(barcode, serialNumber, total, used)
    }

    /**
     * Print a 1-bit bitmap (584x354) to the Niimbot B1 Pro printer.
     */
    fun printBitmap(bitmap: android.graphics.Bitmap, callback: (Boolean, String?) -> Unit) {
        if (connectionState.value != STATE_CONNECTED || writeCharacteristic == null) {
            callback(false, "Printer not connected")
            return
        }

        writeScope.launch {
            printMutex.withLock {
                try {
                    ensureConnectionPacket()

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
                    sendCommand(
                        CMD_PRINT_START,
                        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00),
                        RESPONSE_PRINT_START
                    )

                    sendPacket(buildFrame(CMD_PRINT_STATUS, byteArrayOf(0x01)))
                    delay(30)

                    sendCommand(
                        CMD_SET_PAGE_SIZE,
                        byteArrayOf(
                            ((bitmap.height shr 8) and 0xFF).toByte(),
                            (bitmap.height and 0xFF).toByte(),
                            ((bitmap.width shr 8) and 0xFF).toByte(),
                            (bitmap.width and 0xFF).toByte(),
                            0x00, 0x01,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                        ),
                        RESPONSE_SET_PAGE_SIZE
                    )

                    sendRows(bitmap)

                    sendCommand(
                        CMD_PRINT_END_PAGE,
                        byteArrayOf(0x01),
                        RESPONSE_PRINT_END_PAGE
                    )

                    awaitPageCompletion()

                    sendCommand(
                        CMD_PRINT_END,
                        byteArrayOf(0x01),
                        RESPONSE_PRINT_END
                    )
                    callback(true, null)
                } catch (e: Exception) {
                    Log.e("NiimbotBLE", "Print failed", e)
                    callback(false, e.message ?: "Print failed")
                }
            }
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
        responseCommand: Int
    ): ProtocolFrame {
        val waiter = CompletableDeferred<ProtocolFrame>()
        synchronized(pendingResponsesLock) {
            pendingResponses[responseCommand] = waiter
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
                withTimeout(RESPONSE_TIMEOUT_MS) {
                    waiter.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException(
                    "No response to command 0x${command.toString(16)} " +
                        "(expected 0x${responseCommand.toString(16)}) after ${RESPONSE_TIMEOUT_MS}ms",
                    e
                )
            }
        } finally {
            synchronized(pendingResponsesLock) {
                if (pendingResponses[responseCommand] === waiter) {
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

    private suspend fun awaitPageCompletion() {
        withTimeout(PRINT_TIMEOUT_MS) {
            var page = 0
            while (page < 1) {
                val waiter = CompletableDeferred<ProtocolFrame>()
                synchronized(pendingResponsesLock) {
                    pendingResponses[RESPONSE_PRINT_STATUS] = waiter
                }

                try {
                    sendPacket(buildFrame(CMD_PRINT_STATUS, byteArrayOf(0x01)))
                    val response = withTimeout(RESPONSE_TIMEOUT_MS) {
                        waiter.await()
                    }
                    page = if (response.data.size >= 2) {
                        ((response.data[0].toInt() and 0xFF) shl 8) or
                            (response.data[1].toInt() and 0xFF)
                    } else {
                        0
                    }
                } finally {
                    synchronized(pendingResponsesLock) {
                        if (pendingResponses[RESPONSE_PRINT_STATUS] === waiter) {
                            pendingResponses.remove(RESPONSE_PRINT_STATUS)
                        }
                    }
                }

                if (page < 1) {
                    delay(200)
                }
            }
        }
    }

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
