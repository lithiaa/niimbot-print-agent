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
        """{"sku":"SKU-1","nama":"Barang","harga_beli":100,"harga_jual":150,"stok":9}"""

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
    fun `integration requests use bearer token and omit legacy key header`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        api.lookup("https://pos.example/base/", "token-123", "SKU-1")

        assertEquals("Bearer token-123", recorder.request.header("Authorization"))
        assertFalse(recorder.request.headers.names().any { it.equals("X-Integration-Key", ignoreCase = true) })
    }

    @Test
    fun `401 maps to session expired`() = runBlocking {
        val recorder = RecordingResponder("{}", statusCode = 401)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.lookup("https://pos.example", "expired", "SKU-1")

        assertEquals(PosApiResult.SessionExpired, result)
    }

    @Test
    fun `create posts current barang payload with supplier id`() = runBlocking {
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
        assertEquals("/base/api/integration/barang", recorder.request.url.encodedPath)
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
    fun `existing stock posts exact payload to sku stock-in endpoint`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.addStock(
            "https://pos.example/base/",
            "secret",
            "SKU-1",
            jumlahBarangMasuk = 4,
            hargaSatuan = 100L,
            operationId = operationId
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("POST", recorder.request.method)
        assertEquals(
            "/base/api/integration/barang/by-sku/SKU-1/stok-masuk",
            recorder.request.url.encodedPath
        )
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(
            "{\"jumlah_barang_masuk\":4,\"harga_satuan\":100," +
                "\"operation_id\":\"$operationId\"}",
            recorder.request.bodyText()
        )
    }

    @Test
    fun `search sends query and bearer token then decodes product list`() = runBlocking {
        val recorder = RecordingResponder("""{"data":[$responseJson]}""")
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.searchProducts(
            "https://pos.example/base/",
            "secret",
            "Barang",
            limit = 7
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/integration/barang/search", recorder.request.url.encodedPath)
        assertEquals("Barang", recorder.request.url.queryParameter("q"))
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
        assertEquals("/base/api/integration/suppliers", recorder.request.url.encodedPath)
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
        assertEquals("/base/api/integration/barang/42", recorder.request.url.encodedPath)
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
                kategoriId = 3,
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
        assertEquals("PUT", recorder.request.method)
        assertEquals("/base/api/integration/barang/42", recorder.request.url.encodedPath)
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        val body = recorder.request.bodyText()
        assertTrue(body.contains("\"supplier_id\":7"))
        assertTrue(body.contains("\"stok_minimum\":2"))
        assertTrue(!body.contains("\"stok\""))
    }

    @Test
    fun `product list uses integration endpoint with pagination and optional query`() = runBlocking {
        val recorder = RecordingResponder(
            """{"data":[$responseJson],"total":21,"page":2,"limit":10}"""
        )
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })

        val result = api.listProducts(
            "https://pos.example/base/",
            "secret",
            query = "Kopi",
            page = 2,
            limit = 10
        )

        assertTrue(result is PosApiResult.Success)
        assertEquals("GET", recorder.request.method)
        assertEquals("/base/api/integration/barang", recorder.request.url.encodedPath)
        assertEquals("Kopi", recorder.request.url.queryParameter("q"))
        assertEquals("2", recorder.request.url.queryParameter("page"))
        assertEquals("10", recorder.request.url.queryParameter("limit"))
        assertEquals("Bearer secret", recorder.request.header("Authorization"))
        assertNull(recorder.request.header("X-Integration-Key"))
        assertEquals(21, (result as PosApiResult.Success).value.total)
    }

    private class RecordingResponder(responseJson: String, private val statusCode: Int? = null) {
        lateinit var request: Request
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode ?: if (request.url.encodedPath.endsWith("stok-masuk")) 200 else 201)
                    .message("OK")
                    .body(responseJson.toResponseBody())
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
