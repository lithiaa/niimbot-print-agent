package com.niimbot.printagent.label

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelOptionsTest {
    @Test
    fun `physical sizes use B1 Pro printable dimensions`() {
        assertEquals(584, LabelSize.MM_50_X_30.widthPx)
        assertEquals(354, LabelSize.MM_50_X_30.heightPx)
        assertEquals(236, LabelSize.MM_50_X_20.heightPx)
        assertEquals(472, LabelSize.MM_40_X_30.widthPx)
        assertEquals(354, LabelSize.MM_30_X_20.widthPx)
        assertEquals(236, LabelSize.MM_30_X_20.heightPx)
        assertEquals(LabelSize.MM_30_X_20, LabelSize.fromName("MM_30_X_20"))
        assertEquals(LabelSize.MM_30_X_20, LabelSize.detected(30, 20))
    }

    @Test
    fun `unknown persisted options safely use defaults`() {
        assertEquals(LabelSize.MM_50_X_30, LabelSize.fromName("unknown"))
    }
}
