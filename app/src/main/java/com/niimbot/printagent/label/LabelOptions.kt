package com.niimbot.printagent.label

data class LabelSize(
    val name: String,
    val displayName: String,
    val widthMm: Int,
    val heightMm: Int
) {

    // A 50 mm B1 Pro roll has a validated printable width of 584 px. The raw
    // 300-dpi conversion is 590 px, which the printer rejects at SetPageSize.
    val widthPx: Int get() = if (widthMm == 50) 584 else mmToPx(widthMm)
    val heightPx: Int get() = mmToPx(heightMm)

    companion object {
        const val DPI = 300
        val MM_50_X_30 = LabelSize("MM_50_X_30", "50 × 30 mm", 50, 30)
        val MM_50_X_20 = LabelSize("MM_50_X_20", "50 × 20 mm", 50, 20)
        val MM_40_X_30 = LabelSize("MM_40_X_30", "40 × 30 mm", 40, 30)
        val entries = listOf(MM_50_X_30, MM_50_X_20, MM_40_X_30)

        fun mmToPx(mm: Int): Int = (mm * DPI / 25.4f).toInt()
        fun detected(widthMm: Int, heightMm: Int): LabelSize =
            entries.firstOrNull { it.matches(widthMm, heightMm) }
                ?: LabelSize("DETECTED_${widthMm}_X_$heightMm", "$widthMm × $heightMm mm", widthMm, heightMm)

        fun fromName(value: String): LabelSize {
            entries.firstOrNull { it.name == value }?.let { return it }
            val match = Regex("DETECTED_(\\d+)_X_(\\d+)").matchEntire(value) ?: return MM_50_X_30
            return detected(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }

    fun matches(width: Int, height: Int): Boolean =
        (widthMm == width && heightMm == height) || (widthMm == height && heightMm == width)
}

enum class LabelLayout(val displayName: String) {
    STANDARD("Standar — barcode di atas"),
    COMPACT("Ringkas — barcode di kanan"),
    BARCODE_BOTTOM("Standar — barcode di bawah");

    companion object {
        fun fromName(value: String): LabelLayout = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
