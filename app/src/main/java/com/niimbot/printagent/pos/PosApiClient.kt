package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelGenerator
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface PosApiResult<out T> {
    data class Success<T>(val value: T) : PosApiResult<T>
    data object NotFound : PosApiResult<Nothing>
    data class Failure(val message: String, val statusCode: Int? = null) : PosApiResult<Nothing>
}

interface PosProductGateway {
    suspend fun lookup(
        baseUrl: String,
        integrationKey: String,
        normalizedSku: String
    ): PosApiResult<PosProduct>

    suspend fun create(
        baseUrl: String,
        integrationKey: String,
        form: com.niimbot.printagent.label.LabelData,
        operationId: String
    ): PosApiResult<PosProduct>

    suspend fun update(
        baseUrl: String,
        integrationKey: String,
        form: com.niimbot.printagent.label.LabelData
    ): PosApiResult<PosProduct>

    suspend fun addStock(
        baseUrl: String,
        integrationKey: String,
        sku: String,
        jumlahBarangMasuk: Int,
        hargaSatuan: Long,
        operationId: String
    ): PosApiResult<PosProduct>
}

class PosApiClient(
    private val client: OkHttpClient,
    private val json: Json
) : PosProductGateway {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECTION_TEST_SKU = "__NIIMBOT_CONNECTION_TEST__"
    }

    override suspend fun lookup(baseUrl: String, integrationKey: String, normalizedSku: String): PosApiResult<PosProduct> =
        executeProductRequest(
            request = requestBuilder(baseUrl, integrationKey, normalizedSku).get().build(),
            allowNotFound = true
        )

    suspend fun searchProducts(
        baseUrl: String,
        integrationKey: String,
        query: String,
        limit: Int = 10
    ): PosApiResult<List<PosProduct>> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Lithia POS tidak valid")
        val url = parsedBase.newBuilder()
            .addPathSegments("api/integration/barang/search")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("X-Integration-Key", integrationKey)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                runCatching { json.decodeFromString<PosProductSearchResponse>(body).data }
                    .fold(
                        onSuccess = { PosApiResult.Success(it) },
                        onFailure = { PosApiResult.Failure("Respons pencarian Lithia POS tidak valid.") }
                    )
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat terhubung ke Lithia POS. Periksa URL dan jaringan.")
        }
    }

    suspend fun listSuppliers(
        baseUrl: String,
        bearerToken: String
    ): PosApiResult<List<PosSupplier>> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Lithia POS tidak valid")
        val request = Request.Builder()
            .url(parsedBase.newBuilder().addPathSegments("api/supplier").build())
            .header("Authorization", "Bearer ${bearerToken.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                val suppliers = runCatching { json.decodeFromString<List<PosSupplier>>(body) }.getOrNull()
                    ?: runCatching { json.decodeFromString<PosSupplierEnvelope>(body).data }.getOrNull()
                    ?: return@withContext PosApiResult.Failure("Respons supplier Lithia POS tidak valid.")
                PosApiResult.Success(suppliers)
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat mengambil supplier dari Lithia POS. Periksa URL dan jaringan.")
        }
    }

    override suspend fun create(
        baseUrl: String,
        integrationKey: String,
        form: com.niimbot.printagent.label.LabelData,
        operationId: String
    ): PosApiResult<PosProduct> {
        val product = PosProductWriteRequest(
            sku = form.sku,
            nama = form.nama,
            hargaBeli = form.hargaBeli,
            hargaBeliKode = form.kodeHargaBeli ?: LabelGenerator.encodePurchasePrice(form.hargaBeli),
            hargaJual = form.hargaJual,
            jumlahBarangMasuk = form.jumlahBarangMasuk,
            operationId = operationId,
            satuan = "pcs"
        )
        return executeProductRequest(
            requestBuilder(baseUrl, integrationKey)
                .post(json.encodeToString(product).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    override suspend fun update(
        baseUrl: String,
        integrationKey: String,
        form: com.niimbot.printagent.label.LabelData
    ): PosApiResult<PosProduct> {
        val product = PosProductUpdateRequest(
            nama = form.nama,
            hargaBeli = form.hargaBeli,
            hargaBeliKode = form.kodeHargaBeli ?: LabelGenerator.encodePurchasePrice(form.hargaBeli),
            hargaJual = form.hargaJual
        )
        return executeProductRequest(
            requestBuilder(baseUrl, integrationKey, form.sku)
                .put(json.encodeToString(product).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    override suspend fun addStock(
        baseUrl: String,
        integrationKey: String,
        sku: String,
        jumlahBarangMasuk: Int,
        hargaSatuan: Long,
        operationId: String
    ): PosApiResult<PosProduct> {
        val stock = PosStockInRequest(
            jumlahBarangMasuk = jumlahBarangMasuk,
            hargaSatuan = hargaSatuan,
            operationId = operationId
        )
        return executeProductRequest(
            requestBuilder(baseUrl, integrationKey, sku, stockIn = true)
                .post(json.encodeToString(stock).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    suspend fun testConnection(baseUrl: String, integrationKey: String): PosApiResult<Unit> = withContext(Dispatchers.IO) {
        val request = requestBuilder(baseUrl, integrationKey, CONNECTION_TEST_SKU).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful || response.code == 404 -> PosApiResult.Success(Unit)
                    else -> failureForStatus(response.code, body)
                }
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat terhubung ke Lithia POS. Periksa URL dan jaringan.")
        }
    }

    private suspend fun executeProductRequest(
        request: Request,
        allowNotFound: Boolean = false
    ): PosApiResult<PosProduct> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (allowNotFound && response.code == 404) return@withContext PosApiResult.NotFound
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                decodeProduct(body)?.let { PosApiResult.Success(it) }
                    ?: PosApiResult.Failure("Respons Lithia POS tidak valid.", response.code)
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat terhubung ke Lithia POS. Periksa URL dan jaringan.")
        }
    }

    private fun requestBuilder(
        baseUrl: String,
        integrationKey: String,
        sku: String? = null,
        stockIn: Boolean = false
    ): Request.Builder {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("URL Lithia POS tidak valid")
        val url = parsedBase.newBuilder()
            .addPathSegments("api/integration/barang")
            .apply {
                if (sku != null) {
                    addPathSegment("by-sku")
                    addPathSegment(sku)
                    if (stockIn) addPathSegment("stok-masuk")
                }
            }
            .build()
        return Request.Builder()
            .url(url)
            .header("X-Integration-Key", integrationKey)
            .header("Accept", "application/json")
    }

    private fun decodeProduct(body: String): PosProduct? =
        runCatching { json.decodeFromString<PosProduct>(body) }.getOrNull()
            ?: runCatching { json.decodeFromString<PosProductEnvelope>(body).data }.getOrNull()

    private fun failureForStatus(code: Int, responseBody: String = ""): PosApiResult.Failure {
        val detail = extractApiDetail(responseBody)
        return when (code) {
            401, 403 -> PosApiResult.Failure("Autentikasi Lithia POS ditolak. Periksa integration key atau token supplier.", code)
            422 -> PosApiResult.Failure(
                detail?.let { "Data ditolak Lithia POS: $it" }
                    ?: "Data ditolak Lithia POS karena ada isian yang tidak valid.",
                code
            )
            else -> PosApiResult.Failure(
                detail?.let { "Lithia POS gagal memproses permintaan (HTTP $code): $it" }
                    ?: "Lithia POS gagal memproses permintaan (HTTP $code).",
                code
            )
        }
    }

    private fun extractApiDetail(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        val detail = runCatching {
            (json.parseToJsonElement(responseBody) as? JsonObject)?.get("detail")
        }.getOrNull() ?: return null
        return when (detail) {
            is JsonPrimitive -> detail.content
            is JsonArray -> detail.joinToString("; ") { item ->
                val objectItem = item as? JsonObject
                val location = (objectItem?.get("loc") as? JsonArray)
                    ?.joinToString(".") { (it as? JsonPrimitive)?.content.orEmpty() }
                val message = (objectItem?.get("msg") as? JsonPrimitive)?.content
                listOfNotNull(location?.takeIf { it.isNotBlank() }, message).joinToString(": ")
                    .ifBlank { item.toString() }
            }
            else -> detail.toString()
        }.take(500)
    }
}
