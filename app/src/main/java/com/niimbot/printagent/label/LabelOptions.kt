package com.niimbot.printagent.label

enum class LabelSize(val displayName: String, val widthMm: Int, val heightMm: Int) {
    MM_50_X_30("50 × 30 mm", 50, 30),
    MM_50_X_20("50 × 20 mm", 50, 20),
    MM_40_X_30("40 × 30 mm", 40, 30);

    val widthPx: Int get() = mmToPx(widthMm)
    val heightPx: Int get() = mmToPx(heightMm)

    companion object {
        const val DPI = 300

        fun mmToPx(mm: Int): Int = (mm * DPI / 25.4f).toInt()
        fun fromName(value: String): LabelSize = entries.firstOrNull { it.name == value } ?: MM_50_X_30
    }
}

enum class LabelLayout(val displayName: String) {
    STANDARD("Standar — barcode di atas"),
    COMPACT("Ringkas — barcode di kanan");

    companion object {
        fun fromName(value: String): LabelLayout = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
