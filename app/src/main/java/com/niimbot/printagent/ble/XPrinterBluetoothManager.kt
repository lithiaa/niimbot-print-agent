package com.niimbot.printagent.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bluetooth Classic SPP transport for XPrinter label printers using TSPL. */
class XPrinterBluetoothManager(context: Context) {
    companion object {
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableConnectionState = MutableLiveData(STATE_DISCONNECTED)
    val connectionStateLive: LiveData<Int> = mutableConnectionState
    private var socket: BluetoothSocket? = null
    var connectedMac: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> = runCatching {
        adapter?.bondedDevices.orEmpty().sortedWith(
            compareBy<BluetoothDevice> { it.name?.lowercase().orEmpty() }.thenBy { it.address }
        )
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String, callback: (Boolean, String?) -> Unit) {
        if (mutableConnectionState.value == STATE_CONNECTED && connectedMac == macAddress) {
            callback(true, null)
            return
        }
        mutableConnectionState.postValue(STATE_CONNECTING)
        scope.launch {
            val result = runCatching {
                disconnectInternal()
                val device = adapter?.getRemoteDevice(macAddress)
                    ?: throw IOException("Bluetooth tidak tersedia")
                adapter.cancelDiscovery()
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                socket = newSocket
                connectedMac = macAddress
            }
            if (result.isSuccess) {
                mutableConnectionState.postValue(STATE_CONNECTED)
                callback(true, null)
            } else {
                disconnectInternal()
                mutableConnectionState.postValue(STATE_DISCONNECTED)
                callback(false, result.exceptionOrNull()?.message)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
            mutableConnectionState.postValue(STATE_DISCONNECTED)
        }
    }

    fun printBitmap(
        bitmap: Bitmap,
        widthMm: Int,
        heightMm: Int,
        dpi: Int,
        copies: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        scope.launch {
            mutex.withLock {
                val result = runCatching {
                    val activeSocket = socket?.takeIf { it.isConnected }
                        ?: throw IOException("XPrinter belum terhubung")
                    val targetWidth = mmToPx(widthMm, dpi)
                    val targetHeight = mmToPx(heightMm, dpi)
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                    try {
                        val bytesPerRow = (scaled.width + 7) / 8
                        val raster = toMonochrome(scaled, bytesPerRow)
                        val output = activeSocket.outputStream
                        val header = buildString {
                            append("SIZE $widthMm mm,$heightMm mm\r\n")
                            append("GAP 2 mm,0 mm\r\n")
                            append("DIRECTION 1\r\n")
                            append("CLS\r\n")
                            append("BITMAP 0,0,$bytesPerRow,${scaled.height},0,")
                        }.toByteArray(Charsets.US_ASCII)
                        output.write(header)
                        output.write(raster)
                        output.write("\r\nPRINT 1,${copies.coerceAtLeast(1)}\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                    } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                }
                if (result.isFailure) {
                    disconnectInternal()
                    mutableConnectionState.postValue(STATE_DISCONNECTED)
                }
                callback(result.isSuccess, result.exceptionOrNull()?.message)
            }
        }
    }

    fun cleanup() {
        runCatching { socket?.close() }
        socket = null
        connectedMac = null
        mutableConnectionState.postValue(STATE_DISCONNECTED)
        scope.cancel()
    }

    private fun disconnectInternal() {
        runCatching { socket?.close() }
        socket = null
        connectedMac = null
    }

    private fun mmToPx(mm: Int, dpi: Int): Int = (mm * dpi / 25.4f).toInt().coerceAtLeast(1)

    private fun toMonochrome(bitmap: Bitmap, bytesPerRow: Int): ByteArray {
        return encodeXPrinterRaster(bitmap.width, bitmap.height, bytesPerRow) { x, y ->
            val pixel = bitmap.getPixel(x, y)
            val gray = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
            gray < 128
        }
    }
}

/** XP-420B TSPL raster uses a cleared bit for a heated (black) dot. */
internal fun encodeXPrinterRaster(
    width: Int,
    height: Int,
    bytesPerRow: Int,
    isBlack: (x: Int, y: Int) -> Boolean
): ByteArray {
    require(bytesPerRow >= (width + 7) / 8)
    val data = ByteArray(bytesPerRow * height) { 0xFF.toByte() }
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (isBlack(x, y)) {
                val index = y * bytesPerRow + x / 8
                val mask = 0x80 shr (x % 8)
                data[index] = (data[index].toInt() and mask.inv()).toByte()
            }
        }
    }
    return data
}
