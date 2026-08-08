package com.niimbot.printagent.util

object ByteUtils {
    fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
    
    fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return data
    }
    
    fun intToBytes(value: Int, bigEndian: Boolean = true): ByteArray {
        return if (bigEndian) {
            byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        } else {
            byteArrayOf(
                value.toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte()
            )
        }
    }
    
    fun bytesToInt(bytes: ByteArray, offset: Int = 0, bigEndian: Boolean = true): Int {
        return if (bigEndian) {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        } else {
            (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }
    }
    
    fun shortToBytes(value: Short, bigEndian: Boolean = true): ByteArray {
        return if (bigEndian) {
            byteArrayOf(
                (value.toInt() shr 8).toByte(),
                value.toByte()
            )
        } else {
            byteArrayOf(
                value.toByte(),
                (value.toInt() shr 8).toByte()
            )
        }
    }
}