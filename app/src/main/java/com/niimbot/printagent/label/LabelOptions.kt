package com.niimbot.printagent.label

data class LabelSize(
    val name: String,
    val displayName: String,
    val widthMm: Int,
    val heightMm: Int
) {

    // Keep the established 50 mm design canvas at 584 px; XPrinter output is
    // scaled to the configured 203/300 DPI immediately before TSPL encoding.
    val widthPx: Int get() = if (widthMm == 50) 584 else mmToPx(widthMm)
    val heightPx: Int get() = mmToPx(heightMm)

    companion object {
        const val DPI = 300
        val MM_50_X_30 = LabelSize("MM_50_X_30", "50 × 30 mm", 50, 30)
        val MM_50_X_20 = LabelSize("MM_50_X_20", "50 × 20 mm", 50, 20)
        val MM_40_X_30 = LabelSize("MM_40_X_30", "40 × 30 mm", 40, 30)
        val MM_30_X_20 = LabelSize("MM_30_X_20", "30 × 20 mm", 30, 20)
        val entries = listOf(MM_50_X_30, MM_50_X_20, MM_40_X_30, MM_30_X_20)

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
