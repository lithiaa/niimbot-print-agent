package com.niimbot.printagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizedPrintMessageTest {
    @Test
    fun `translates legacy English queue errors`() {
        assertEquals(
            "Percobaan ulang 2/3 - Printer tidak terhubung",
            localizeLegacyPrintMessage("Retry 2/3 - Printer not connected")
        )
        assertEquals(
            "Penulisan RFID gagal",
            localizeLegacyPrintMessage("RFID write failed")
        )
    }

    @Test
    fun `keeps Indonesian messages unchanged`() {
        assertEquals(
            "Printer tidak terhubung",
            localizeLegacyPrintMessage("Printer tidak terhubung")
        )
    }
}
