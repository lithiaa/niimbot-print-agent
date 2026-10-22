package com.niimbot.printagent.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/** Generates the fixed 50 x 30 mm buyer notice used from the Printer page. */
object BuyerNoticeLabelGenerator {
    const val LAYOUT_NAME = "BUYER_NOTICE"
    const val JOB_NAME = "Peringatan Pembeli"

    val labelSize: LabelSize = LabelSize.MM_50_X_30

    fun generate(brandLogo: Bitmap? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(labelSize.widthPx, labelSize.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val width = bitmap.width.toFloat()

        canvas.drawColor(Color.WHITE)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.BLACK
        canvas.drawRect(8f, 8f, width - 8f, bitmap.height - 8f, paint)

        drawBrand(canvas, paint, brandLogo, RectF(24f, 16f, width - 24f, 64f))

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(54f, 73f, width - 54f, 75f, paint)
        drawCenteredText(
            canvas = canvas,
            paint = paint,
            text = "REKAM VIDEO UNBOXING",
            bounds = RectF(20f, 84f, width - 20f, 128f),
            maxTextSize = 34f,
            bold = true
        )

        drawCenteredText(
            canvas, paint, "Rekam sejak paket masih tertutup",
            RectF(22f, 139f, width - 22f, 174f), 28f
        )
        drawCenteredText(
            canvas, paint, "agar komplain dapat diproses.",
            RectF(22f, 173f, width - 22f, 208f), 28f
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(68f, 222f, width - 68f, 224f, paint)
        drawCenteredText(
            canvas, paint, "Puas dengan pesanannya?",
            RectF(22f, 233f, width - 22f, 263f), 24f
        )
        drawCenteredText(
            canvas, paint, "Mohon beri ★★★★★ di Shopee",
            RectF(20f, 262f, width - 20f, 306f), 31f, true
        )
        drawCenteredText(
            canvas, paint, "Terima kasih!",
            RectF(22f, 308f, width - 22f, 340f), 23f
        )

        return bitmap
    }

    private fun drawBrand(
        canvas: Canvas,
        paint: Paint,
        logo: Bitmap?,
        bounds: RectF
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 29f

        val text = "Lithia Project"
        val textWidth = paint.measureText(text)
        val source = logo?.let(::opaqueBounds)
        val logoHeight = bounds.height() * .82f
        val logoWidth = source?.let { logoHeight * it.width() / it.height().toFloat() } ?: 0f
        val gap = if (source != null) 12f else 0f
        val groupWidth = logoWidth + gap + textWidth
        var left = bounds.centerX() - groupWidth / 2f

        if (logo != null && source != null) {
            val top = bounds.centerY() - logoHeight / 2f
            canvas.drawBitmap(
                logo,
                source,
                RectF(left, top, left + logoWidth, top + logoHeight),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            )
            left += logoWidth + gap
        }

        val metrics = paint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, left, baseline, paint)
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            if (Color.alpha(color) > 20) {
                val x = index % bitmap.width
                val y = index / bitmap.width
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
    }

    private fun drawCenteredText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        bounds: RectF,
        maxTextSize: Float,
        bold: Boolean = false,
        color: Int = Color.BLACK
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = maxTextSize

        val availableWidth = bounds.width()
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > availableWidth && measuredWidth > 0f) {
            paint.textSize *= availableWidth / measuredWidth
        }

        val metrics = paint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, bounds.centerX(), baseline, paint)
    }
}
