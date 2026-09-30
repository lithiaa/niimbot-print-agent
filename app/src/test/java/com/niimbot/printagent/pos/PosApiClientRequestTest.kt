package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosApiClientRequestTest {
    private val operationId = "11111111-1111-4111-8111-111111111111"
    private val responseJson =
        """{"id":42,"sku":"SKU-1","nama":"Barang","harga_beli":100,"harga_jual":150,"stok":9}"""

    @Test
    fun `login posts username and password to auth endpoint`() = runBlocking {
        val recorder = RecordingResponder("""{"access_token":"token-123"}""")
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.login("https://pos.example/base/", "operator", "not-saved")

        assertEquals(PosApiResult.Success(PosLogin("token-123")), result)
        assertEquals("POST", recorder.request.method)
        assertEquals("/base/api/auth/login", recorder.request.url.encodedPath)
        assertEquals("{\"username\":\"operator\",\"password\":\"not-saved\"}", recorder.request.bodyText())
        assertNull(recorder.request.header("Authorization"))
    }

    @Test
    fun `login 401 reports bad credentials rather than expired session`() = runBlocking {
        val recorder = RecordingResponder("{}", statusCode = 401)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.login("https://pos.example", "operator", "wrong")

        assertEquals(
            PosApiResult.Failure("Username atau password Lithia POS salah.", 401),
            result
        )
    }

    @Test
    fun `me gets authenticated identity with bearer token`() = runBlocking {
        val recorder = RecordingResponder("""{"username":"operator","role":"admin"}""")
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.me("https://pos.example/base/", "token-123")

        assertEquals(PosApiResult.Success(PosIdentity("operator", "admin")), result)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/auth/me", recorder.request.url.encodedPath)
        assertEquals("Bearer token-123", recorder.request.header("Authorization"))
    }

    @Test
    fun `SKU lookup uses authenticated barang endpoint and omits legacy key header`() = runBlocking {
        val recorder = RecordingResponder(
            """{"data":[$responseJson],"total":1,"page":1,"limit":100}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.lookup("https://pos.example/base/", "token-123", "SKU-1")

        assertTrue(result is PosApiResult.Success)
        assertEquals("/base/api/barang", recorder.request.url.encodedPath)
        assertEquals("SKU-1", recorder.request.url.queryParameter("search"))
        assertEquals("Bearer token-123", recorder.request.header("Authorization"))
        assertFalse(recorder.request.headers.names().any { it.equals("X-Integration-Key", ignoreCase = true) })
    }

    @Test
    fun `SKU lookup does not treat a partial search result as the requested item`() = runBlocking {
        val partial = responseJson.replace("SKU-1", "SKU-10")
        val recorder = RecordingResponder(
            """{"data":[$partial],"total":1,"page":1,"limit":100}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.lookup("https://pos.example", "token-123", "sku-1")

        assertEquals(PosApiResult.NotFound, result)
    }

    @Test
    fun `401 maps to session expired`() = runBlocking {
        val recorder = RecordingResponder("{}", statusCode = 401)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.lookup("https://pos.example", "expired", "SKU-1")

        assertEquals(PosApiResult.SessionExpired, result)
    }

    @Test
    fun `create posts current payload with supplier id to barang endpoint`() = runBlocking {
        val recorder = RecordingResponder(
            """{"sku":"SKU-1","nama":"Barang","harga_modal":100,"harga_jual":150,"stok_awal":4}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })
        val form = LabelData(
            "SKU-1", "Barang", 100L, 150L, 2, 4,
            supplierCode = "SA",
            supplierId = 7
        )

        val result = api.create("https://pos.example/base/", "secret", form, operationId)

        assertTrue(result is PosApiResult.Success)
        assertEquals("POST", recorder.request.method)
        assertEquals("/base/api/barang", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(
            "{\"sku\":\"SKU-1\",\"nama\":\"Barang\",\"merek\":\"\",\"supplier_id\":7," +
                "\"harga_modal\":100,\"harga_beli_kode\":\"SP\",\"harga_jual_kode\":\"SUP\"," +
                "\"harga_jual\":150,\"stok_minimum\":5,\"satuan\":\"pcs\",\"deskripsi\":\"\"," +
                "\"foto\":\"\",\"stok_awal\":4}",
            recorder.request.bodyText()
        )
        result as PosApiResult.Success
        assertEquals(100L, result.value.hargaBeli)
        assertEquals(4, result.value.stok)
    }

    @Test
    fun `create fails locally when supplier id is missing`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.create(
            "https://pos.example/base/",
            "secret",
            LabelData("SKU-1", "Barang", 100L, 150L, 2, 4, supplierCode = "SA"),
            operationId
        )

        assertEquals(PosApiResult.Failure("Supplier Sistem belum dipilih."), result)
    }

    @Test
    fun `existing stock posts JWT payload to user stock-in endpoint`() = runBlocking {
        val recorder = RecordingResponder(
            """{"message":"stok diperbarui"}""",
            statusCode = 200
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.addStock(
            "https://pos.example/base/",
            "secret",
            PosProduct("SKU-1", "Barang", 100, 150, stok = 9, id = 42),
            jumlahBarangMasuk = 4,
            hargaSatuan = 100L,
            operationId = operationId
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("POST", recorder.request.method)
        assertEquals("/base/api/stok/masuk", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(
            "{\"barang_id\":42,\"jumlah\":4,\"harga_satuan\":100," +
                "\"keterangan\":\"Lithia Label Printer | OPERATION_ID=$operationId\"}",
            recorder.request.bodyText()
        )
        assertEquals(13, (result as PosApiResult.Success).value.stok)
    }

    @Test
    fun `stock subtraction posts documented payload and updates local stock`() = runBlocking {
        val recorder = RecordingResponder("""{"message":"stok diperbarui"}""", statusCode = 200)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.subtractStock(
            "https://pos.example/base/",
            "secret",
            PosProduct("SKU-1", "Barang", 100, 150, stok = 9, id = 42),
            quantity = 3,
            unitPrice = 150L,
            operationId = operationId
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("POST", recorder.request.method)
        assertEquals("/base/api/stok/keluar", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertEquals(
            "{\"barang_id\":42,\"jumlah\":3,\"harga_satuan\":150," +
                "\"keterangan\":\"Lithia Label Printer | OPERATION_ID=$operationId\"}",
            recorder.request.bodyText()
        )
        assertEquals(6, (result as PosApiResult.Success).value.stok)
    }

    @Test
    fun `label conflict update uses product id and user-authenticated fields`() = runBlocking {
        val recorder = RecordingResponder(responseJson, statusCode = 200)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })
        val form = LabelData("SKU-1", "Barang Baru", 100L, 150L, 2, 4)

        val result = api.update(
            "https://pos.example/base/",
            "secret",
            form,
            PosProduct("SKU-1", "Barang", 90, 140, stok = 9, id = 42)
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("PUT", recorder.request.method)
        assertEquals("/base/api/barang/42", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertEquals(
            "{\"nama\":\"Barang Baru\",\"harga_modal\":100,\"harga_jual_kode\":\"SUP\"}",
            recorder.request.bodyText()
        )
    }

    @Test
    fun `search sends query and bearer token then decodes product list`() = runBlocking {
        val recorder = RecordingResponder(
            """{"data":[$responseJson],"total":1,"page":1,"limit":7}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.searchProducts(
            "https://pos.example/base/",
            "secret",
            "Barang",
            limit = 7
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/barang", recorder.request.url.encodedPath)
        assertEquals("Barang", recorder.request.url.queryParameter("search"))
        assertEquals("1", recorder.request.url.queryParameter("page"))
        assertEquals("7", recorder.request.url.queryParameter("limit"))
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(1, (result as PosApiResult.Success).value.size)
        assertEquals("SKU-1", result.value.single().sku)
    }

    @Test
    fun `supplier list uses bearer token and decodes mobile supplier fields`() = runBlocking {
        val recorder = RecordingResponder(
            """[{"id":7,"nama_supplier":"Supplier A","kode_supplier":"SA"}]"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.listSuppliers("https://pos.example/base/", "secret")

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/supplier", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals("SA", (result as PosApiResult.Success).value.single().codeForLabel)
    }

    @Test
    fun `detail gets product by id using bearer token`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.getProductById("https://pos.example/base/", "secret", 42)

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/barang/42", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
    }

    @Test
    fun `edit puts complete product metadata without stock`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.updateProductById(
            "https://pos.example/base/",
            "secret",
            42,
            PosProductEditInput(
                sku = "SKU-1",
                nama = "Barang Baru",
                merek = "Merek",
                supplierId = 7,
                hargaBeli = 100,
                hargaBeliKode = "SP",
                hargaJual = 150,
                stokMinimum = 2,
                satuan = "pcs",
                deskripsi = "Deskripsi"
            )
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals(2, recorder.requests.size)
        val update = recorder.requests.first()
        assertEquals("PUT", update.method)
        assertEquals("/base/api/barang/42", update.url.encodedPath)
        assertEquals("Bearer secret", update.header("Authorization"))
        assertNull(update.header("X-Integration-Key"))
        val body = update.bodyText()
        assertTrue(body.contains("\"sku\":\"SKU-1\""))
        assertTrue(body.contains("\"supplier_id\":7"))
        assertTrue(body.contains("\"harga_modal\":100"))
        assertTrue(body.contains("\"harga_beli_kode\":\"SP\""))
        assertTrue(body.contains("\"harga_jual_kode\":\"SUP\""))
        assertTrue(body.contains("\"harga_jual\":150"))
        assertTrue(body.contains("\"stok_minimum\":2"))
        assertTrue(!body.contains("kategori"))
        assertTrue(!body.contains("\"stok\""))
        assertEquals("GET", recorder.requests.last().method)
        assertEquals("/base/api/barang/42", recorder.requests.last().url.encodedPath)
    }

    @Test
    fun `product list uses authenticated endpoint with pagination and supported filters`() = runBlocking {
        val recorder = RecordingResponder(
            """{"data":[$responseJson],"total":21,"page":2,"limit":10}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.listProducts(
            "https://pos.example/base/",
            "secret",
            query = "Kopi",
            stockStatus = "menipis",
            page = 2,
            limit = 10
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/barang", recorder.request.url.encodedPath)
        assertEquals("Kopi", recorder.request.url.queryParameter("search"))
        assertEquals("true", recorder.request.url.queryParameter("stok_menipis"))
        assertEquals("2", recorder.request.url.queryParameter("page"))
        assertEquals("10", recorder.request.url.queryParameter("limit"))
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(21, (result as PosApiResult.Success).value.total)
    }

    @Test
    fun `inventory statistics uses authenticated integration endpoint and decodes stock lists`() = runBlocking {
        val recorder = RecordingResponder(
            """{
                "total_barang":12,
                "total_stok":345,
                "total_stok_menipis":1,
                "total_stok_habis":1,
                "stok_menipis":[{
                    "id":7,"sku":"OLI-1","nama":"Oli Mesin","stok":2,
                    "stok_minimum":5,"satuan":"botol","foto":null
                }],
                "stok_habis":[{
                    "id":8,"sku":"BUSI-1","nama":"Busi","stok":0,
                    "stok_minimum":3,"satuan":"pcs","foto":"busi.jpg"
                }]
            }""".trimIndent()
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.getInventoryStatistics("https://pos.example/base/", "secret")

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/integration/barang/statistik", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        val statistics = (result as PosApiResult.Success).value
        assertEquals(12, statistics.totalBarang)
        assertEquals(345L, statistics.totalStok)
        assertEquals("OLI-1", statistics.stokMenipis.single().sku)
        assertEquals(3, statistics.stokHabis.single().stokMinimum)
    }

    @Test
    fun `feature 401 keeps local session when auth identity is still valid`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val isIdentityCheck = request.url.encodedPath.endsWith("/api/auth/me")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isIdentityCheck) 200 else 401)
                    .message("OK")
                    .body(
                        (if (isIdentityCheck) {
                            """{"username":"operator","role":"admin"}"""
                        } else {
                            "{}"
                        }).toResponseBody()
                    )
                    .build()
            }
            .build()
        val api = PosApiClient(client, Json { ignoreUnknownKeys = true })

        val result = api.listSuppliers("https://pos.example/base/", "still-valid")

        assertTrue(result is PosApiResult.Failure)
        assertEquals(401, (result as PosApiResult.Failure).statusCode)
        assertEquals(
            listOf("/base/api/supplier", "/base/api/auth/me"),
            requests.map { it.url.encodedPath }
        )
    }

    @Test
    fun `product metadata uses documented supplier endpoint only`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = """[{"id":7,"nama":"Supplier A"}]"""
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
        val api = PosApiClient(client, Json { ignoreUnknownKeys = true })

        val result = api.getProductMeta("https://pos.example/base/", "secret")

        assertTrue(result is PosApiResult.Success)
        result as PosApiResult.Success
        assertEquals("Supplier A", result.value.suppliers.single().displayName)
        assertEquals(listOf("/base/api/supplier"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `photo upload posts multipart file then reloads full product detail`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

        val result = api.uploadProductPhoto(
            "https://pos.example/base/",
            "secret",
            42,
            PosPhotoUpload(jpeg, "image/jpeg", "filter udara.png")
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals(2, recorder.requests.size)
        val upload = recorder.requests.first()
        assertEquals("POST", upload.method)
        assertEquals("/base/api/barang/42/photos", upload.url.encodedPath)
        assertEquals("Bearer secret", upload.header("Authorization"))
        val body = upload.bodyText()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"filter_udara.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertEquals("GET", recorder.requests.last().method)
        assertEquals("/base/api/barang/42", recorder.requests.last().url.encodedPath)
    }

    @Test
    fun `photo delete calls integration endpoint then reloads detail`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.deleteProductPhoto("https://pos.example/base/", "secret", 42)

        assertTrue(result is PosApiResult.Success)
        assertEquals(listOf("DELETE", "GET"), recorder.requests.map { it.method })
        assertEquals("/base/api/integration/barang/42/foto", recorder.requests.first().url.encodedPath)
        assertEquals("Bearer secret", recorder.requests.first().header("Authorization"))
    }

    @Test
    fun `multiple photos delete selected ids append every upload then reload once`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        val result = api.updateProductPhotos(
            baseUrl = "https://pos.example/base/",
            accessToken = "secret",
            productId = 42,
            deletePhotoIds = listOf(9, 11),
            uploads = listOf(
                PosPhotoUpload(jpeg, "image/jpeg", "depan.jpg"),
                PosPhotoUpload(png, "image/png", "samping.png")
            )
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals(
            listOf("DELETE", "DELETE", "POST", "POST", "GET"),
            recorder.requests.map { it.method }
        )
        assertEquals(
            listOf(
                "/base/api/barang/42/photos/9",
                "/base/api/barang/42/photos/11",
                "/base/api/barang/42/photos",
                "/base/api/barang/42/photos",
                "/base/api/barang/42"
            ),
            recorder.requests.map { it.url.encodedPath }
        )
        assertTrue(recorder.requests[2].bodyText().contains("filename=\"depan.jpg\""))
        assertTrue(recorder.requests[3].bodyText().contains("filename=\"samping.png\""))
    }

    @Test
    fun `photo read resolves backend relative storage URL`() = runBlocking {
        val recorder = RecordingResponder("photo-bytes")
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.downloadProductPhoto(
            "https://api-ijm.lithiaproject.site/",
            "/storage/foto-barang/uuid.webp"
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/storage/foto-barang/uuid.webp", recorder.request.url.encodedPath)
        assertNull(recorder.request.header("Authorization"))
        assertEquals("photo-bytes", (result as PosApiResult.Success).value.decodeToString())
    }

    private class RecordingResponder(responseJson: String, private val statusCode: Int? = null) {
        lateinit var request: Request
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                request = chain.request()
                requests += request
                val responseCode = statusCode ?: when {
                    request.method == "DELETE" -> 204
                    request.method == "GET" -> 200
                    request.url.encodedPath.endsWith("stok-masuk") -> 200
                    else -> 201
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("OK")
                    .body((if (responseCode == 204) "" else responseJson).toResponseBody())
                    .build()
            }
            .build()
    }

    private fun Request.bodyText(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
