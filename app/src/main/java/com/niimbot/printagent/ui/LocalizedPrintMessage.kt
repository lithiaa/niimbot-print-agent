package com.niimbot.printagent.ui

internal fun localizeLegacyPrintMessage(message: String): String = message
    .replace("Printer not connected", "Printer tidak terhubung", ignoreCase = true)
    .replace("Printer disconnected", "Printer terputus", ignoreCase = true)
    .replace("RFID write failed", "Penulisan RFID gagal", ignoreCase = true)
    .replace("BLE print timeout", "Waktu tunggu cetak BLE habis", ignoreCase = true)
    .replace("Max retries exceeded", "Batas percobaan ulang terlampaui", ignoreCase = true)
    .replace(Regex("Retry\\s+(\\d+)/3", RegexOption.IGNORE_CASE), "Percobaan ulang ${'$'}1/3")
