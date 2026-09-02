package com.niimbot.printagent.label

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LabelDate {
    private const val ISO_PATTERN = "yyyy-MM-dd"
    private const val DISPLAY_PATTERN = "dd/MM/yyyy"

    fun todayIso(): String = SimpleDateFormat(ISO_PATTERN, Locale.US).format(Date())

    fun isValid(value: String): Boolean = runCatching {
        SimpleDateFormat(ISO_PATTERN, Locale.US).apply { isLenient = false }.parse(value)
    }.getOrNull() != null

    fun fromTimestamp(value: String?): String? {
        val datePart = value?.trim()?.take(10).orEmpty()
        return datePart.takeIf(::isValid)
    }

    fun display(value: String?): String? {
        val cleanValue = value?.trim()?.takeIf(::isValid) ?: return null
        val parsed = SimpleDateFormat(ISO_PATTERN, Locale.US).apply { isLenient = false }.parse(cleanValue)
            ?: return null
        return SimpleDateFormat(DISPLAY_PATTERN, Locale("id", "ID")).format(parsed)
    }
}
