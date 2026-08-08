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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.send
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Niimbot Bluetooth Manager - Handles all BLE communication with Niimbot B1 Pro printer.
 * Ported from niimbluelib (TypeScript) to Kotlin.
 */
class NiimbotBluetoothManager(private val context: Context) {

    companion object {
        // Niimbot B1 Service & Characteristic UUIDs
        const val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        const val WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        const val NOTIFY_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        
        // Client Characteristic Configuration Descriptor UUID
        const val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
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
    
    private val writeChannel = Channel<ByteArray>(10)
    private val writeJob = CoroutineScope(Dispatchers.IO).launch { writeLoop() }
    
    private var targetMac: String? = null
    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var printCompleteCallback: ((Boolean, String?) -> Unit)? = null
    
    // Retry config
    private var retryCount = 0
    private const val MAX_RETRY = 3
    private const val RETRY_DELAY_MS = 2000L

    // ===================== LIVE DATA GETTERS =====================
    
    val connectionStateLive: LiveData<Int> = connectionState
    val printStatusLive: LiveData<Int> = printStatus
    val discoveredDevicesLive: LiveData<List<BluetoothDevice>> = discoveredDevices

    // ===================== SCAN =====================
    
    fun startScan() {
        if (!isBluetoothEnabled()) {
            Log.w("NiimbotBLE", "Bluetooth not enabled")
            return
        }
        
        val scanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.name?.contains("B1", true) == true || 
                    device.name?.contains("NIIMBOT", true) == true ||
                    device.name?.startsWith("B") == true) {
                    updateDiscoveredDevices(device)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e("NiimbotBLE", "Scan failed: $errorCode")
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scanner.startScan(scanCallback)
        } else {
            @Suppress("DEPRECATION")
            scanner.startScan(scanCallback)
        }
        
        // Auto-stop after 10 seconds
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(10000)
            stopScan()
        }
    }
    
    fun stopScan() {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name?.contains("B1", true) == true || 
                device.name?.contains("NIIMBOT", true) == true ||
                device.name?.startsWith("B") == true) {
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
        
        val device = bluetoothAdapter.getRemoteDevice(mac)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            gatt = device.connectGatt(context, false, gattCallback)
        }
    }
    
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
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
                    Log.w("NiimbotBLE", "Disconnected: $status")
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
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NiimbotBLE", "Write failed: $status")
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic.value)
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("NiimbotBLE", "Notifications enabled")
                connectionCallback?.invoke(true)
                connectionState.postValue(STATE_CONNECTED)
                retryCount = 0
            }
        }
    }
    
    private fun setupCharacteristics(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        
        writeCharacteristic = service.getCharacteristic(WRITE_UUID)
        notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID)
        
        // Enable notifications
        notifyCharacteristic?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID)
            descriptor?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            gatt.writeDescriptor(descriptor!!)
        }
        
        // Request MTU
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            gatt.requestMtu(MTU_SIZE)
        }
    }
    
    private fun handleDisconnect(status: Int) {
        connectionState.postValue(STATE_DISCONNECTED)
        connectionCallback?.invoke(false)
        
        // Auto-reconnect logic
        if (targetMac != null && retryCount < MAX_RETRY) {
            retryCount++
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(RETRY_DELAY_MS * retryCount)
                targetMac?.let { connect(it) { _ -> } }
            }
        }
    }
    
    private fun handleNotify(data: ByteArray?) {
        data?.let { bytes ->
            if (bytes.isNotEmpty()) {
                val status = bytes[0].toInt()
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
        
        try {
            // Convert bitmap to Niimbot packet format
            val packets = encodeBitmapToPackets(bitmap)
            
            // Send packets sequentially via write channel
            CoroutineScope(Dispatchers.IO).launch {
                // 1. Send init command
                sendCommand(buildInitCommand())
                kotlinx.coroutines.delay(100)
                
                // 2. Send print start
                sendCommand(buildPrintStartCommand())
                kotlinx.coroutines.delay(100)
                
                // 3. Send data chunks
                for (packet in packets) {
                    sendCommand(packet)
                    // Flow control: wait for notify (printer ready for next chunk)
                    // In practice, Niimbot handles flow control via notify
                    kotlinx.coroutines.delay(50)
                }
                
                // 4. Send print end
                sendCommand(buildPrintEndCommand())
            }
        } catch (e: Exception) {
            Log.e("NiimbotBLE", "Print failed", e)
            callback(false, e.message)
        }
    }
    
    private suspend fun sendCommand(data: ByteArray) {
        writeChannel.send(data)
    }
    
    private fun writeLoop() {
        for (data in writeChannel) {
            writeCharacteristic?.let { char ->
                char.value = data
                gatt?.writeCharacteristic(char)
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
                val gray = (0.299 * android.graphics.Color.red(pixel) + 
                           0.587 * android.graphics.Color.green(pixel) + 
                           0.114 * android.graphics.Color.blue(pixel)).toInt()
                
                if (gray < 128) { // Black pixel
                    bytes[byteIndex] = (bytes[byteIndex] or (1 shl (7 - bitIndex))).toByte()
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
        
        for (offset in 0 until data.size step chunkSize) {
            val length = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + length)
            
            // Niimbot packet format: [CMD][SEQ][LEN][DATA][CHECKSUM]
            val packet = ByteArray(4 + length + 1)
            packet[0] = 0x50.toByte()        // CMD: Print Data
            packet[1] = (packets.size % 256).toByte()  // Sequence
            packet[2] = ((length shr 8) and 0xFF).toByte() // Length high
            packet[3] = (length and 0xFF).toByte()       // Length low
            System.arraycopy(chunk, 0, packet, 4, length)
            packet[packet.lastIndex] = calculateChecksum(packet, 0, packet.size - 1)
            
            packets.add(packet)
        }
        
        return packets
    }
    
    private fun buildInitCommand(): ByteArray {
        return byteArrayOf(
            0x55.toByte(), // CMD: Init
            0x00, 0x00, 0x00, 0x00, // Placeholder
            0x00  // Checksum (will be calculated)
        ).also { it[it.lastIndex] = calculateChecksum(it, 0, it.size - 1) }
    }
    
    private fun buildPrintStartCommand(): ByteArray {
        return byteArrayOf(
            0x51.toByte(), // CMD: Print Start
            0x00, 0x00, 0x00, 0x00,
            0x00
        ).also { it[it.lastIndex] = calculateChecksum(it, 0, it.size - 1) }
    }
    
    private fun buildPrintEndCommand(): ByteArray {
        return byteArrayOf(
            0x52.toByte(), // CMD: Print End
            0x00, 0x00, 0x00, 0x00,
            0x00
        ).also { it[it.lastIndex] = calculateChecksum(it, 0, it.size - 1) }
    }
    
    private fun calculateChecksum(data: ByteArray, start: Int, end: Int): Byte {
        var sum = 0
        for (i in start..end) {
            sum += data[i].toInt() and 0xFF
        }
        return (sum and 0xFF).toByte()
    }
    
    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter.isEnabled
    }
    
    fun cleanup() {
        writeJob.cancel()
        writeChannel.close()
        disconnect()
    }
}