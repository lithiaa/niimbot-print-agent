package com.niimbot.printagent.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import com.niimbot.printagent.util.ByteUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Niimbot Bluetooth Manager - Handles all BLE communication with Niimbot B1 Pro printer.
 * Ported from niimbluelib (TypeScript) to Kotlin.
 */
class NiimbotBluetoothManager(private val context: Context) {

    companion object {
        // Niimbot B1 Service & Characteristic UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor UUID
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // MTU size (Niimbot typical)
        const val MTU_SIZE = 185

        // Connection state
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCONNECTING = 3

        // Print status codes from notify characteristic
        const val STATUS_PRINTING = 0x01
        const val STATUS_DONE = 0x02
        const val STATUS_ERROR = 0x03
        const val STATUS_PAPER_OUT = 0x04
        const val STATUS_COVER_OPEN = 0x05
        const val STATUS_LOW_BATTERY = 0x06

        // Retry config
        const val MAX_RETRY = 3
        const val RETRY_DELAY_MS = 2000L
    }

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
    private var writeJob: Job? = null

    private var targetMac: String? = null
    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var printCompleteCallback: ((Boolean, String?) -> Unit)? = null

    private var retryCount = 0

    // ===================== LIVE DATA GETTERS =====================

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

        // Auto-stop after 10 seconds
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
            val name = try { result.scanRecord?.deviceName ?: device.name } catch (e: SecurityException) { null }
            
            if (name != null) {
                Log.d("NiimbotBLE", "Discovered device: $name - ${device.address}")
            }
            
            if (name?.contains("B1", true) == true ||
                name?.contains("NIIMBOT", true) == true ||
                name?.startsWith("B", true) == true) {
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

    fun connect(mac: String, callback: (Boolean) -> Unit) {
        if (connectionState.value == STATE_CONNECTED) {
            callback(true)
            return
        }

        targetMac = mac
        connectionCallback = callback
        connectionState.postValue(STATE_CONNECTING)

        try {
            val device = bluetoothAdapter.getRemoteDevice(mac)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e("NiimbotBLE", "connect() error: ${e.message}")
            callback(false)
            connectionState.postValue(STATE_DISCONNECTED)
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w("NiimbotBLE", "disconnect error: ${e.message}")
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        connectionState.postValue(STATE_DISCONNECTED)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupCharacteristics(gatt)
            } else {
                Log.e("NiimbotBLE", "Service discovery failed: $status")
                connectionCallback?.invoke(false)
                connectionState.postValue(STATE_DISCONNECTED)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NiimbotBLE", "Write failed: $status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleNotify(characteristic.value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("NiimbotBLE", "Notifications enabled")
                // Start write loop
                startWriteLoop()
                connectionCallback?.invoke(true)
                connectionState.postValue(STATE_CONNECTED)
                retryCount = 0
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i("NiimbotBLE", "MTU changed to $mtu")
        }
    }

    private fun startWriteLoop() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                val data = writeChannel.receive()
                try {
                    writeCharacteristic?.let { char ->
                        @Suppress("DEPRECATION")
                        char.value = data
                        @Suppress("DEPRECATION")
                        gatt?.writeCharacteristic(char)
                    }
                    // Small delay to not overwhelm BLE stack
                    delay(30)
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

        // Fallback for newer Niimbot B1 / ISSC module
        if (service == null) {
            val isscServiceUuid = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
            service = gatt.getService(isscServiceUuid)
            if (service != null) {
                writeCharUuid = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
                notifyCharUuid = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
            }
        }
        
        // Fallback for custom e7810a71 service
        if (service == null) {
            val customServiceUuid = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
            service = gatt.getService(customServiceUuid)
            if (service != null) {
                val commonCharUuid = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
                writeCharUuid = commonCharUuid
                notifyCharUuid = commonCharUuid
            }
        }

        if (service == null) {
            Log.e("NiimbotBLE", "No supported Niimbot service found!")
            connectionCallback?.invoke(false)
            connectionState.postValue(STATE_DISCONNECTED)
            return
        }

        writeCharacteristic = service.getCharacteristic(writeCharUuid)
        notifyCharacteristic = service.getCharacteristic(notifyCharUuid)

        if (writeCharacteristic == null || notifyCharacteristic == null) {
            Log.e("NiimbotBLE", "Failed to get characteristics for service")
            connectionCallback?.invoke(false)
            connectionState.postValue(STATE_DISCONNECTED)
            return
        }

        gatt.setCharacteristicNotification(notifyCharacteristic, true)
        val descriptor = notifyCharacteristic?.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        // Request MTU
        gatt.requestMtu(MTU_SIZE)
    }

    private fun handleDisconnect(status: Int) {
        writeJob?.cancel()
        connectionState.postValue(STATE_DISCONNECTED)
        connectionCallback?.invoke(false)

        // Auto-reconnect logic
        if (targetMac != null && retryCount < MAX_RETRY) {
            retryCount++
            CoroutineScope(Dispatchers.IO).launch {
                delay(RETRY_DELAY_MS * retryCount)
                targetMac?.let { connect(it) { _ -> } }
            }
        }
    }

    private fun handleNotify(data: ByteArray?) {
        data?.let { bytes ->
            if (bytes.isNotEmpty()) {
                val status = bytes[0].toInt() and 0xFF
                printStatus.postValue(status)

                when (status) {
                    STATUS_DONE -> printCompleteCallback?.invoke(true, null)
                    STATUS_ERROR -> printCompleteCallback?.invoke(false, "Printer error")
                    STATUS_PAPER_OUT -> printCompleteCallback?.invoke(false, "Paper out")
                    STATUS_COVER_OPEN -> printCompleteCallback?.invoke(false, "Cover open")
                    STATUS_LOW_BATTERY -> printCompleteCallback?.invoke(false, "Low battery")
                }
            }
        }
    }

    // ===================== PRINT =====================

    /**
     * Print a 1-bit bitmap (584x354) to the Niimbot printer.
     * This encodes the bitmap into Niimbot's proprietary packet format.
     */
    fun printBitmap(bitmap: android.graphics.Bitmap, callback: (Boolean, String?) -> Unit) {
        if (connectionState.value != STATE_CONNECTED || writeCharacteristic == null) {
            callback(false, "Printer not connected")
            return
        }

        printCompleteCallback = callback

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Convert bitmap to Niimbot packet format
                val packets = encodeBitmapToPackets(bitmap)

                // 1. Send init command
                writeChannel.send(buildInitCommand())
                delay(100)

                // 2. Send print start
                writeChannel.send(buildPrintStartCommand())
                delay(100)

                // 3. Send data chunks
                packets.forEachIndexed { index, packet ->
                    writeChannel.send(packet)
                    // Flow control: small delay between packets
                    delay(50)
                }

                // 4. Send print end
                writeChannel.send(buildPrintEndCommand())

            } catch (e: Exception) {
                Log.e("NiimbotBLE", "Print failed", e)
                callback(false, e.message)
            }
        }
    }

    // ===================== PACKET ENCODING (Ported from niimbluelib) =====================

    private fun encodeBitmapToPackets(bitmap: android.graphics.Bitmap): List<ByteArray> {
        // Ensure correct size: 584x354 (B1 Pro 50x30mm @ 300dpi)
        val scaledBitmap = if (bitmap.width != 584 || bitmap.height != 354) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, 584, 354, true)
        } else bitmap

        // Convert to 1-bit bitmap (threshold 128)
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val bytes = ByteArray((width * height + 7) / 8)

        var byteIndex = 0
        var bitIndex = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = scaledBitmap.getPixel(x, y)
                val gray = (
                    0.299 * android.graphics.Color.red(pixel) +
                    0.587 * android.graphics.Color.green(pixel) +
                    0.114 * android.graphics.Color.blue(pixel)
                ).toInt()

                if (gray < 128) { // Black pixel
                    bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl (7 - bitIndex))).toByte()
                }

                bitIndex++
                if (bitIndex == 8) {
                    bitIndex = 0
                    byteIndex++
                }
            }
        }

        // Build packets with Niimbot header format
        return chunkData(bytes)
    }

    private fun chunkData(data: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val chunkSize = MTU_SIZE - 4 // Header overhead

        for (offset in data.indices step chunkSize) {
            val length = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + length)

            // Niimbot packet format: [CMD][SEQ][LEN_HI][LEN_LO][DATA...][CHECKSUM]
            val packet = ByteArray(4 + length + 1)
            packet[0] = 0x50.toByte()                       // CMD: Print Data
            packet[1] = (packets.size % 256).toByte()       // Sequence
            packet[2] = ((length shr 8) and 0xFF).toByte()  // Length high
            packet[3] = (length and 0xFF).toByte()          // Length low
            System.arraycopy(chunk, 0, packet, 4, length)
            packet[packet.lastIndex] = calculateChecksum(packet, 0, packet.size - 1)

            packets.add(packet)
        }

        return packets
    }

    private fun buildInitCommand(): ByteArray {
        val cmd = byteArrayOf(
            0x55.toByte(), // CMD: Init
            0x00, 0x00, 0x00, 0x00
        )
        return cmd + calculateChecksum(cmd, 0, cmd.size - 1)
    }

    private fun buildPrintStartCommand(): ByteArray {
        val cmd = byteArrayOf(
            0x51.toByte(), // CMD: Print Start
            0x00, 0x00, 0x00, 0x00
        )
        return cmd + calculateChecksum(cmd, 0, cmd.size - 1)
    }

    private fun buildPrintEndCommand(): ByteArray {
        val cmd = byteArrayOf(
            0x52.toByte(), // CMD: Print End
            0x00, 0x00, 0x00, 0x00
        )
        return cmd + calculateChecksum(cmd, 0, cmd.size - 1)
    }

    private operator fun ByteArray.plus(byte: Byte): ByteArray {
        val result = ByteArray(this.size + 1)
        System.arraycopy(this, 0, result, 0, this.size)
        result[this.size] = byte
        return result
    }

    private fun calculateChecksum(data: ByteArray, start: Int, end: Int): Byte {
        var sum = 0
        for (i in start..end) {
            sum += data[i].toInt() and 0xFF
        }
        return (sum and 0xFF).toByte()
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