package com.niimbot.printagent.pos

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PosProductSerializationTest {

    @Test
    fun `create request serializes exact barang contract with supplier id`() {
        val request = PosProductCreateRequest(
            sku = "GULA-1",
            nama = "Gula",
            merek = "",
            supplierId = 7,
            hargaModal = 10_000L,
            hargaBeliKode = "AUP",
            hargaJualKode = "ABP",
            hargaJual = 12_000L,
            stokMinimum = 5,
            satuan = "pcs",
            deskripsi = "",
            foto = "",
            stokAwal = 6
        )

        assertEquals(
            "{\"sku\":\"GULA-1\",\"nama\":\"Gula\",\"merek\":\"\",\"supplier_id\":7," +
                "\"harga_modal\":10000,\"harga_beli_kode\":\"AUP\",\"harga_jual_kode\":\"ABP\"," +
                "\"harga_jual\":12000,\"stok_minimum\":5,\"satuan\":\"pcs\",\"deskripsi\":\"\"," +
                "\"foto\":\"\",\"stok_awal\":6}",
            Json.encodeToString(request)
        )
    }

    @Test
    fun `existing stock request serializes exact stock contract`() {
        val request = PosStockInRequest(
            jumlahBarangMasuk = 4,
            hargaSatuan = 10_000L,
            operationId = "11111111-1111-4111-8111-111111111111"
        )

        assertEquals(
            "{\"jumlah_barang_masuk\":4,\"harga_satuan\":10000," +
                "\"operation_id\":\"11111111-1111-4111-8111-111111111111\"}",
            Json.encodeToString(request)
        )
    }

    @Test
    fun `update request serializes only backend accepted keys`() {
        val request = PosProductUpdateRequest(
            nama = "Gula",
            hargaBeli = 10_000L,
            hargaBeliKode = "AUP",
            hargaJual = 12_000L
        )

        assertEquals(
            "{\"nama\":\"Gula\",\"harga_beli\":10000,\"harga_beli_kode\":\"AUP\",\"harga_jual\":12000}",
            Json.encodeToString(request)
        )
    }
}
