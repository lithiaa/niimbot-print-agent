package com.niimbot.printagent.label

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class LabelElement(val displayName: String) {
    PRODUCT_NAME("Nama barang"),
    PURCHASE_CODE("Kode harga beli"),
    SALE_PRICE("Harga jual"),
    BARCODE("Barcode"),
    SKU("SKU"),
    ITEM_QTY("Qty barang"),
    SUPPLIER("Kode supplier"),
    ENTRY_DATE("Tanggal masuk")
}

/**
 * An element frame stored as a fraction of the physical label dimensions.
 * Keeping the values normalized makes one edit render consistently on every
 * supported label size and in the on-screen preview.
 */
@Serializable
data class LabelElementFrame(
    val element: LabelElement,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
) {
    fun normalized(): LabelElementFrame {
        val safeWidth = width.coerceIn(MIN_WIDTH, 1f)
        val safeHeight = height.coerceIn(MIN_HEIGHT, 1f)
        return copy(
            centerX = centerX.coerceIn(safeWidth / 2f, 1f - safeWidth / 2f),
            centerY = centerY.coerceIn(safeHeight / 2f, 1f - safeHeight / 2f),
            width = safeWidth,
            height = safeHeight
        )
    }

    private companion object {
        const val MIN_WIDTH = .05f
        const val MIN_HEIGHT = .02f
    }
}

@Serializable
data class LabelTemplate(val frames: List<LabelElementFrame>) {
    fun frame(element: LabelElement): LabelElementFrame =
        frames.firstOrNull { it.element == element }
            ?: defaultFor(LabelLayout.STANDARD).frames.first { it.element == element }

    fun update(frame: LabelElementFrame): LabelTemplate = copy(
        frames = LabelElement.entries.map { element ->
            if (element == frame.element) frame.normalized() else this.frame(element).normalized()
        }
    )

    fun normalized(): LabelTemplate = copy(
        frames = LabelElement.entries.map { element -> frame(element).normalized() }
    )

    companion object {
        fun defaultFor(layout: LabelLayout): LabelTemplate = LabelTemplate(
            when (layout) {
                LabelLayout.STANDARD -> listOf(
                    frame(LabelElement.PRODUCT_NAME, .50f, .52f, .92f, .13f),
                    frame(LabelElement.PURCHASE_CODE, .28f, .75f, .38f, .13f),
                    frame(LabelElement.SALE_PRICE, .71f, .75f, .46f, .13f),
                    frame(LabelElement.BARCODE, .50f, .19f, .95f, .23f),
                    frame(LabelElement.SKU, .50f, .39f, .42f, .075f),
                    frame(LabelElement.ITEM_QTY, .14f, .39f, .22f, .075f),
                    frame(LabelElement.SUPPLIER, .86f, .39f, .22f, .075f),
                    frame(LabelElement.ENTRY_DATE, .50f, .965f, .55f, .045f)
                )
                LabelLayout.COMPACT -> listOf(
                    frame(LabelElement.PRODUCT_NAME, .27f, .23f, .47f, .16f),
                    frame(LabelElement.PURCHASE_CODE, .27f, .70f, .47f, .10f),
                    frame(LabelElement.SALE_PRICE, .27f, .49f, .47f, .15f),
                    frame(LabelElement.BARCODE, .78f, .41f, .38f, .58f),
                    frame(LabelElement.SKU, .79f, .88f, .25f, .065f),
                    frame(LabelElement.ITEM_QTY, .62f, .88f, .14f, .065f),
                    frame(LabelElement.SUPPLIER, .94f, .88f, .10f, .065f),
                    frame(LabelElement.ENTRY_DATE, .50f, .965f, .55f, .045f)
                )
                LabelLayout.BARCODE_BOTTOM -> listOf(
                    frame(LabelElement.PRODUCT_NAME, .50f, .16f, .92f, .13f),
                    frame(LabelElement.PURCHASE_CODE, .28f, .32f, .38f, .12f),
                    frame(LabelElement.SALE_PRICE, .71f, .32f, .46f, .12f),
                    frame(LabelElement.BARCODE, .50f, .59f, .95f, .23f),
                    frame(LabelElement.SKU, .50f, .79f, .42f, .075f),
                    frame(LabelElement.ITEM_QTY, .14f, .79f, .22f, .075f),
                    frame(LabelElement.SUPPLIER, .86f, .79f, .22f, .075f),
                    frame(LabelElement.ENTRY_DATE, .50f, .965f, .55f, .045f)
                )
            }
        ).normalized()

        private fun frame(
            element: LabelElement,
            centerX: Float,
            centerY: Float,
            width: Float,
            height: Float
        ) = LabelElementFrame(element, centerX, centerY, width, height)
    }
}

object LabelTemplateCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(template: LabelTemplate): String = json.encodeToString(template.normalized())

    fun decode(value: String?): LabelTemplate? = value
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded -> runCatching { json.decodeFromString<LabelTemplate>(encoded).normalized() }.getOrNull() }
}
