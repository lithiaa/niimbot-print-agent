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
import org.junit.Assert.assertTrue
import org.junit.Test

class PosApiClientRequestTest {
    private val operationId = "11111111-1111-4111-8111-111111111111"
    private val responseJson =
        """{"sku":"SKU-1","nama":"Barang","harga_beli":100,"harga_jual":150,"stok":9}"""

    @Test
    fun `create posts atomic payload to integration barang endpoint`() = runBlocking {
        val recorder = RecordingResponder(responseJson)
        val api = PosApiClient(recorder.client, Json { ignoreUnknownKeys = true })
        val form = LabelData("SKU-1", "Barang", 100L, 150L, 2, 4)

        val result = api.create("https://pos.example/base/", "secret", form, operationId)

        assertTrue(result is PosApiResult.Success)
        assertEquals("POST", recorder.request.method)
        assertEquals("/base/api/integration/barang", recorder.request.url.encodedPath)
        assertEquals(
            "{\"sku\":\"SKU-1\",\"nama\":\"Barang\",\"harga_beli\":100," +
                "\"harga_jual\":150,\"jumlah_barang_masuk\":4," +
                "\"operation_id\":\"$operationId\",\"satuan\":\"pcs\"}",
            recorder.request.bodyText()
        )
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
        assertEquals(
            "{\"jumlah_barang_masuk\":4,\"harga_satuan\":100," +
                "\"operation_id\":\"$operationId\"}",
            recorder.request.bodyText()
        )
    }

    @Test
    fun `search sends query and integration key then decodes product list`() = runBlocking {
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
        assertEquals("secret", recorder.request.header("X-Integration-Key"))
        assertEquals(1, (result as PosApiResult.Success).value.size)
        assertEquals("SKU-1", result.value.single().sku)
    }

    private class RecordingResponder(responseJson: String) {
        lateinit var request: Request
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (request.url.encodedPath.endsWith("stok-masuk")) 200 else 201)
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
