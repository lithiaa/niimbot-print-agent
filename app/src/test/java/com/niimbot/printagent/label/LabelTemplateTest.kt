package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelTemplateTest {
    @Test
    fun `every layout defines every editable text and barcode element`() {
        LabelLayout.entries.forEach { layout ->
            val template = LabelTemplate.defaultFor(layout)

            assertEquals(LabelElement.entries.toSet(), template.frames.map { it.element }.toSet())
            assertEquals(LabelElement.entries.size, template.frames.size)
        }
    }

    @Test
    fun `updating one element keeps all other frames unchanged`() {
        val original = LabelTemplate.defaultFor(LabelLayout.STANDARD)
        val changed = original.update(
            original.frame(LabelElement.PRODUCT_NAME).copy(
                centerX = .35f,
                centerY = .40f,
                width = .50f,
                height = .20f
            )
        )

        assertNotEquals(original.frame(LabelElement.PRODUCT_NAME), changed.frame(LabelElement.PRODUCT_NAME))
        LabelElement.entries.filterNot { it == LabelElement.PRODUCT_NAME }.forEach { element ->
            assertEquals(original.frame(element), changed.frame(element))
        }
    }

    @Test
    fun `codec round trip preserves edits for queued printing`() {
        val edited = LabelTemplate.defaultFor(LabelLayout.BARCODE_BOTTOM).update(
            LabelElementFrame(LabelElement.BARCODE, .50f, .60f, .70f, .30f)
        )

        val restored = LabelTemplateCodec.decode(LabelTemplateCodec.encode(edited))

        assertEquals(edited, restored)
    }

    @Test
    fun `frames are kept within physical label bounds`() {
        val template = LabelTemplate.defaultFor(LabelLayout.STANDARD).update(
            LabelElementFrame(LabelElement.SKU, 1f, 0f, .40f, .20f)
        )
        val frame = template.frame(LabelElement.SKU)

        assertTrue(frame.centerX + frame.width / 2f <= 1f)
        assertTrue(frame.centerY - frame.height / 2f >= 0f)
    }

    @Test
    fun `overflow normalization may use half percent centers at label edges`() {
        val template = LabelTemplate.defaultFor(LabelLayout.STANDARD).update(
            LabelElementFrame(LabelElement.ENTRY_DATE, .50f, .01f, .55f, .15f)
        )
        val frame = template.frame(LabelElement.ENTRY_DATE)

        assertEquals(.075f, frame.centerY, .0001f)
        assertEquals(7.5f, frame.centerY * 100f, .0001f)
        assertTrue(frame.centerY - frame.height / 2f >= 0f)
    }
}
