package com.niimbot.printagent.label

/** Rules shared by the label preview and the Code 128 print path. */
object LabelBarcodeRules {
    private const val FALLBACK_CONTENT = "000000"

    /** ZXing's Code 128 writer accepts ASCII input, but not arbitrary Unicode. */
    fun isCode128Compatible(content: String): Boolean =
        content.isNotEmpty() && content.all { it.code in 32..126 }

    /** Keeps stale or externally supplied invalid data from crashing label rendering. */
    fun safeCode128Content(content: String): String =
        content.takeIf(::isCode128Compatible) ?: FALLBACK_CONTENT
}
