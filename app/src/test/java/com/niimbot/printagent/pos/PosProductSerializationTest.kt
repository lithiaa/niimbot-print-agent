package com.niimbot.printagent.pos

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PosProductSerializationTest {

    private val operationId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `create request serializes exact atomic create and incoming stock contract`() {
        val request = PosProductWriteRequest(
            sku = "GULA-1",
            nama = "Gula",
            hargaBeli = 10_000L,
            hargaJual = 12_000L,
            jumlahBarangMasuk = 6,
            operationId = operationId,
            satuan = "pcs"
        )

        assertEquals(
            "{\"sku\":\"GULA-1\",\"nama\":\"Gula\",\"harga_beli\":10000," +
                "\"harga_jual\":12000,\"jumlah_barang_masuk\":6," +
                "\"operation_id\":\"$operationId\",\"satuan\":\"pcs\"}",
            Json.encodeToString(request)
        )
    }

    @Test
    fun `existing stock request serializes exact stock contract`() {
        val request = PosStockInRequest(
            jumlahBarangMasuk = 4,
            hargaSatuan = 10_000L,
            operationId = operationId
        )

        assertEquals(
            "{\"jumlah_barang_masuk\":4,\"harga_satuan\":10000," +
                "\"operation_id\":\"$operationId\"}",
            Json.encodeToString(request)
        )
    }

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
