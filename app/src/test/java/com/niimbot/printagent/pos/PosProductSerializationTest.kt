package com.niimbot.printagent.pos

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PosProductSerializationTest {

    @Test
    fun `update request serializes only backend accepted keys`() {
        val request = PosProductUpdateRequest(
            nama = "Gula",
            hargaBeli = 10_000L,
            hargaJual = 12_000L
        )

        assertEquals(
            "{\"nama\":\"Gula\",\"harga_beli\":10000,\"harga_jual\":12000}",
            Json.encodeToString(request)
        )
    }
}
