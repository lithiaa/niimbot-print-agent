package com.niimbot.printagent.pos

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosProductSerializationTest {

    @Test
    fun `product detail decodes photo filename and relative URL`() {
        val product = Json.decodeFromString<PosProduct>(
            """{
                "id":12,"sku":"FLT-1","nama":"Filter Udara",
                "harga_beli":10000,"harga_jual":15000,
                "foto":"uuid.webp","foto_url":"/storage/foto-barang/uuid.webp"
            }""".trimIndent()
        )

        assertEquals("uuid.webp", product.foto)
        assertEquals("/storage/foto-barang/uuid.webp", product.fotoUrl)
    }

    @Test
    fun `product detail decodes and orders multiple photos and suppliers`() {
        val product = Json.decodeFromString<PosProduct>(
            """{
                "id":12,"sku":"FLT-1","nama":"Filter Udara",
                "harga_modal":10000,"harga_jual":15000,
                "primary_supplier_id":8,
                "suppliers":[
                    {"id":7,"nama":"Supplier A","is_primary":false,"jumlah_masuk_kumulatif":4},
                    {"id":8,"nama":"Supplier B","is_primary":true,"jumlah_masuk_kumulatif":9}
                ],
                "photos":[
                    {"id":21,"foto_url":"/storage/foto-barang/dua.webp","urutan":2,"is_primary":false},
                    {"id":20,"foto_url":"/storage/foto-barang/utama.webp","urutan":1,"is_primary":true}
                ]
            }""".trimIndent()
        )

        assertEquals(listOf(20L, 21L), product.displayPhotos.map { it.id })
        assertEquals(listOf("Supplier B", "Supplier A"), product.displaySuppliers.map { it.displayName })
        assertEquals(9, product.displaySuppliers.first().jumlahMasukKumulatif)
    }

    @Test
    fun `legacy single photo and supplier remain available for detail`() {
        val legacySupplier = PosSupplier(id = 7, nama = "Supplier Lama")
        val product = PosProduct(
            id = 12,
            sku = "FLT-1",
            nama = "Filter Udara",
            hargaBeli = 10_000,
            hargaJual = 15_000,
            foto = "uuid.webp",
            fotoUrl = "/storage/foto-barang/uuid.webp",
            supplier = legacySupplier
        )

        assertEquals("/storage/foto-barang/uuid.webp", product.displayPhotos.single().downloadReference)
        assertEquals("Supplier Lama", product.displaySuppliers.single().displayName)
        assertEquals(true, product.displaySuppliers.single().isPrimary)
    }

    @Test
    fun `inventory statistics decodes documented response`() {
        val response = Json.decodeFromString<PosInventoryStatistics>(
            """{
                "total_barang":2,
                "total_stok":15,
                "total_stok_menipis":1,
                "total_stok_habis":0,
                "stok_menipis":[{
                    "id":10,"sku":"SKU-10","nama":"Barang Tipis","stok":2,
                    "stok_minimum":4,"satuan":"pcs","foto":null
                }],
                "stok_habis":[]
            }""".trimIndent()
        )

        assertEquals(2, response.totalBarang)
        assertEquals(15L, response.totalStok)
        assertEquals(1, response.totalStokMenipis)
        assertEquals("Barang Tipis", response.stokMenipis.single().nama)
        assertEquals(4, response.stokMenipis.single().stokMinimum)
    }

    @Test
    fun `identity decodes additive toko and permissions while legacy payload stays valid`() {
        val json = Json { ignoreUnknownKeys = true }

        val identity = json.decodeFromString<PosIdentity>(
            """{"username":"operator","role":"staff","environment":{"id":8,"name":"Toko Satu","status":"active"},"permissions":["barang.read","stok.write"]}"""
        )
        val legacy = json.decodeFromString<PosIdentity>("""{"username":"operator","role":"admin"}""")

        assertEquals(PosEnvironment(8, "Toko Satu", "active"), identity.environment)
        assertEquals(listOf("barang.read", "stok.write"), identity.permissions)
        assertNull(legacy.environment)
        assertEquals(emptyList<String>(), legacy.permissions)
    }

    @Test
    fun `identity serializes toko and permissions roundtrip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = PosIdentity(
            "operator", "staff",
            PosEnvironment(8, "Toko Satu", "active"),
            listOf("barang.read", "stok.write")
        )
        val serialized = json.encodeToString(original)
        val decoded = json.decodeFromString<PosIdentity>(serialized)
        assertEquals(original, decoded)
    }

    @Test
    fun `environment serializes as expected`() {
        val json = Json { ignoreUnknownKeys = true }
        val env = PosEnvironment(42, "Toko Dua", "suspended")
        assertEquals(
            """{"id":42,"name":"Toko Dua","status":"suspended"}""",
            json.encodeToString(env)
        )
    }

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
            "{\"sku\":\"GULA-1\",\"nama\":\"Gula\",\"merek\":\"\",\"supplier_id\":7,\"harga_modal\":10000,\"harga_beli_kode\":\"AUP\",\"harga_jual_kode\":\"ABP\",\"harga_jual\":12000,\"stok_minimum\":5,\"satuan\":\"pcs\",\"deskripsi\":\"\",\"foto\":\"\",\"stok_awal\":6}",
            Json.encodeToString(request)
        )
    }

    @Test
    fun `existing stock request serializes exact stock contract`() {
        val request = PosStockAdjustmentRequest(
            barangId = 42,
            jumlah = 4,
            hargaSatuan = 10_000L,
            keterangan = "Lithia Label Printer | OPERATION_ID=11111111-1111-4111-8111-111111111111"
        )

        assertEquals(
            "{\"barang_id\":42,\"jumlah\":4,\"harga_satuan\":10000,\"keterangan\":\"Lithia Label Printer | OPERATION_ID=11111111-1111-4111-8111-111111111111\"}",
            Json.encodeToString(request)
        )
    }

    @Test
    fun `update request serializes only backend accepted keys`() {
        val request = PosProductUpdateRequest(
            nama = "Gula",
            hargaModal = 10_000L,
            hargaJualKode = "ABP"
        )

        assertEquals(
            "{\"nama\":\"Gula\",\"harga_modal\":10000,\"harga_jual_kode\":\"ABP\"}",
            Json.encodeToString(request)
        )
    }
}