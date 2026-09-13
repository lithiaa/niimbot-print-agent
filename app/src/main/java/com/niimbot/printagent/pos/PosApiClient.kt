package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
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
    data object SessionExpired : PosApiResult<Nothing>
    data class Failure(val message: String, val statusCode: Int? = null) : PosApiResult<Nothing>
}

interface PosProductGateway {
    suspend fun lookup(
        baseUrl: String,
        accessToken: String,
        normalizedSku: String
    ): PosApiResult<PosProduct>

    suspend fun create(
        baseUrl: String,
        accessToken: String,
        form: LabelData,
        operationId: String
    ): PosApiResult<PosProduct>

    suspend fun update(
        baseUrl: String,
        accessToken: String,
        form: LabelData
    ): PosApiResult<PosProduct>

    suspend fun addStock(
        baseUrl: String,
        accessToken: String,
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
    }

    suspend fun login(baseUrl: String, username: String, password: String): PosApiResult<PosLogin> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(apiUrl(baseUrl, "api/auth/login"))
                .header("Accept", "application/json")
                .post(json.encodeToString(PosLoginRequest(username, password)).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                    runCatching { json.decodeFromString<PosLogin>(body) }
                        .fold(
                            onSuccess = { PosApiResult.Success(it) },
                            onFailure = { PosApiResult.Failure("Respons login Lithia POS tidak valid.", response.code) }
                        )
                }
            } catch (_: IOException) {
                PosApiResult.Failure("Tidak dapat terhubung ke Lithia POS. Periksa URL dan jaringan.")
            }
        }

    suspend fun me(baseUrl: String, accessToken: String): PosApiResult<PosIdentity> =
        withContext(Dispatchers.IO) {
            val request = authenticatedRequest(baseUrl, accessToken, "api/auth/me").get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) return@withContext PosApiResult.SessionExpired
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                    runCatching { json.decodeFromString<PosIdentity>(body) }
                        .fold(
                            onSuccess = { PosApiResult.Success(it) },
                            onFailure = { PosApiResult.Failure("Respons identitas Lithia POS tidak valid.", response.code) }
                        )
                }
            } catch (_: IOException) {
                PosApiResult.Failure("Tidak dapat terhubung ke Lithia POS. Periksa URL dan jaringan.")
            }
        }

    override suspend fun lookup(baseUrl: String, accessToken: String, normalizedSku: String): PosApiResult<PosProduct> =
        executeProductRequest(
            baseUrl = baseUrl,
            accessToken = accessToken,
            request = requestBuilder(baseUrl, accessToken, normalizedSku).get().build(),
            allowNotFound = true
        )

    suspend fun searchProducts(
        baseUrl: String,
        accessToken: String,
        query: String,
        limit: Int = 10
    ): PosApiResult<List<PosProduct>> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Sistem tidak valid")
        val url = parsedBase.newBuilder()
            .addPathSegments("api/barang")
            .addQueryParameter("search", query.trim())
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                runCatching { json.decodeFromString<PosProductListResponse>(body).data }
                    .recoverCatching { json.decodeFromString<PosProductSearchResponse>(body).data }
                    .fold(
                        onSuccess = { PosApiResult.Success(it) },
                        onFailure = { PosApiResult.Failure("Respons pencarian Sistem tidak valid.") }
                    )
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat terhubung ke Sistem. Periksa URL dan jaringan.")
        }
    }

    suspend fun listProducts(
        baseUrl: String,
        accessToken: String,
        query: String = "",
        stockStatus: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): PosApiResult<PosProductListResponse> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Sistem tidak valid")
        val url = parsedBase.newBuilder()
            .addPathSegments("api/barang")
            .apply {
                query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it) }
                stockStatus?.trim()?.lowercase()?.takeIf { it == "menipis" || it == "habis" }?.let {
                    addQueryParameter("stok_menipis", "true")
                }
            }
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                runCatching { json.decodeFromString<PosProductListResponse>(body) }
                    .fold(
                        onSuccess = { PosApiResult.Success(it) },
                        onFailure = { PosApiResult.Failure("Respons daftar barang Sistem tidak valid.") }
                    )
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat mengambil daftar barang. Periksa URL dan jaringan.")
        }
    }

    suspend fun listSuppliers(
        baseUrl: String,
        accessToken: String
    ): PosApiResult<List<PosSupplier>> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Sistem tidak valid")
        val request = Request.Builder()
            .url(parsedBase.newBuilder().addPathSegments("api/supplier").build())
            .header("Authorization", "Bearer ${accessToken.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                val suppliers = runCatching { json.decodeFromString<List<PosSupplier>>(body) }.getOrNull()
                    ?: runCatching { json.decodeFromString<PosSupplierEnvelope>(body).data }.getOrNull()
                    ?: return@withContext PosApiResult.Failure("Respons pemasok Sistem tidak valid.")
                PosApiResult.Success(suppliers)
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat mengambil pemasok dari Sistem. Periksa URL dan jaringan.")
        }
    }

    suspend fun getProductById(
        baseUrl: String,
        accessToken: String,
        productId: Long
    ): PosApiResult<PosProduct> = executeProductRequest(
        baseUrl = baseUrl,
        accessToken = accessToken,
        request = Request.Builder()
            .url(productByIdUrl(baseUrl, productId))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build(),
        allowNotFound = true
    )

    suspend fun getProductMeta(
        baseUrl: String,
        accessToken: String
    ): PosApiResult<PosProductMeta> = withContext(Dispatchers.IO) {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: return@withContext PosApiResult.Failure("URL Sistem tidak valid")
        try {
            val categoriesRequest = Request.Builder()
                .url(parsedBase.newBuilder().addPathSegments("api/kategori").build())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            val categories = client.newCall(categoriesRequest).execute().use { response ->
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                runCatching { json.decodeFromString<List<PosCategory>>(body) }.getOrNull()
                    ?: return@withContext PosApiResult.Failure("Respons kategori Sistem tidak valid.")
            }
            val suppliersRequest = Request.Builder()
                .url(parsedBase.newBuilder().addPathSegments("api/supplier").build())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            val suppliers = client.newCall(suppliersRequest).execute().use { response ->
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                runCatching { json.decodeFromString<List<PosSupplier>>(body) }.getOrNull()
                    ?: return@withContext PosApiResult.Failure("Respons pemasok Sistem tidak valid.")
            }
            PosApiResult.Success(PosProductMeta(categories, suppliers, listOf("pcs")))
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat mengambil metadata barang Sistem.")
        }
    }

    suspend fun updateProductById(
        baseUrl: String,
        accessToken: String,
        productId: Long,
        input: PosProductEditInput
    ): PosApiResult<PosProduct> {
        val requestBody = PosProductUpdateByIdRequest(
            sku = input.sku,
            nama = input.nama,
            merek = input.merek,
            kategoriId = input.kategoriId,
            supplierId = input.supplierId,
            hargaBeli = input.hargaBeli,
            hargaBeliKode = input.hargaBeliKode,
            hargaJual = input.hargaJual,
            stokMinimum = input.stokMinimum,
            satuan = input.satuan,
            deskripsi = input.deskripsi
        )
        return executeProductRequest(
            baseUrl = baseUrl,
            accessToken = accessToken,
            request = Request.Builder()
                .url(productByIdUrl(baseUrl, productId))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .put(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            allowNotFound = true
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun create(
        baseUrl: String,
        accessToken: String,
        form: LabelData,
        operationId: String
    ): PosApiResult<PosProduct> {
        val supplierId = form.supplierId
            ?: return PosApiResult.Failure("Supplier Sistem belum dipilih.")
        val product = PosProductCreateRequest(
            sku = form.sku,
            nama = form.nama,
            merek = "",
            supplierId = supplierId,
            hargaModal = form.hargaBeli,
            hargaBeliKode = form.kodeHargaBeli ?: LabelGenerator.encodePurchasePrice(form.hargaBeli),
            hargaJualKode = LabelGenerator.encodePurchasePrice(form.hargaJual),
            hargaJual = form.hargaJual,
            stokMinimum = 5,
            satuan = "pcs",
            deskripsi = "",
            foto = "",
            stokAwal = form.jumlahBarangMasuk
        )
        return executeProductRequest(
            baseUrl = baseUrl,
            accessToken = accessToken,
            request = authenticatedRequest(baseUrl, accessToken, "api/barang")
                .post(json.encodeToString(product).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    override suspend fun update(
        baseUrl: String,
        accessToken: String,
        form: LabelData
    ): PosApiResult<PosProduct> {
        val product = PosProductUpdateRequest(
            nama = form.nama,
            hargaBeli = form.hargaBeli,
            hargaBeliKode = form.kodeHargaBeli ?: LabelGenerator.encodePurchasePrice(form.hargaBeli),
            hargaJual = form.hargaJual
        )
        return executeProductRequest(
            baseUrl = baseUrl,
            accessToken = accessToken,
            request = requestBuilder(baseUrl, accessToken, form.sku)
                .put(json.encodeToString(product).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    override suspend fun addStock(
        baseUrl: String,
        accessToken: String,
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
            baseUrl = baseUrl,
            accessToken = accessToken,
            request = requestBuilder(baseUrl, accessToken, sku, stockIn = true)
                .post(json.encodeToString(stock).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    suspend fun testConnection(baseUrl: String, accessToken: String): PosApiResult<PosIdentity> =
        me(baseUrl, accessToken)

    private suspend fun executeProductRequest(
        baseUrl: String,
        accessToken: String,
        request: Request,
        allowNotFound: Boolean = false
    ): PosApiResult<PosProduct> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (allowNotFound && response.code == 404) return@withContext PosApiResult.NotFound
                if (response.code == 401) {
                    response.close()
                    return@withContext resolveUnauthorized(baseUrl, accessToken)
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext failureForStatus(response.code, body)
                decodeProduct(body)?.let { PosApiResult.Success(it) }
                        ?: PosApiResult.Failure("Respons Sistem tidak valid.", response.code)
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat terhubung ke Sistem. Periksa URL dan jaringan.")
        }
    }

    private fun requestBuilder(
        baseUrl: String,
        accessToken: String,
        sku: String? = null,
        stockIn: Boolean = false
    ): Request.Builder {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("URL Sistem tidak valid")
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
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
    }

    private fun authenticatedRequest(
        baseUrl: String,
        accessToken: String,
        path: String
    ): Request.Builder = Request.Builder()
        .url(apiUrl(baseUrl, path))
        .header("Authorization", "Bearer $accessToken")
        .header("Accept", "application/json")

    private fun apiUrl(baseUrl: String, path: String) =
        PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments(path)
            ?.build()
            ?: throw IllegalArgumentException("URL Lithia POS tidak valid")

    private fun productByIdUrl(baseUrl: String, productId: Long): okhttp3.HttpUrl {
        val parsedBase = PosProductRules.normalizeBaseUrl(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("URL Sistem tidak valid")
        return parsedBase.newBuilder()
            .addPathSegments("api/barang")
            .addPathSegment(productId.toString())
            .build()
    }

    private fun decodeProduct(body: String): PosProduct? =
        runCatching { json.decodeFromString<PosProduct>(body) }.getOrNull()
            ?: runCatching { json.decodeFromString<PosProductEnvelope>(body).data }.getOrNull()

    private fun resolveUnauthorized(baseUrl: String, accessToken: String): PosApiResult<Nothing> {
        val request = authenticatedRequest(baseUrl, accessToken, "api/auth/me").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> PosApiResult.SessionExpired
                    response.isSuccessful -> PosApiResult.Failure(
                        "Sesi masih aktif, tetapi akun tidak diizinkan mengakses fitur ini.",
                        401
                    )
                    else -> PosApiResult.Failure(
                        "Tidak dapat memverifikasi sesi Lithia POS (HTTP ${response.code}).",
                        response.code
                    )
                }
            }
        } catch (_: IOException) {
            PosApiResult.Failure("Tidak dapat memverifikasi sesi Lithia POS. Periksa jaringan.")
        }
    }

    private fun failureForStatus(code: Int, responseBody: String = ""): PosApiResult.Failure {
        val detail = extractApiDetail(responseBody)
        return when (code) {
            401 -> PosApiResult.Failure("Username atau password Lithia POS salah.", code)
            403 -> PosApiResult.Failure("Akses Lithia POS ditolak.", code)
            422 -> PosApiResult.Failure(
                    detail?.let { "Data ditolak Sistem: $it" }
                        ?: "Data ditolak Sistem karena ada isian yang tidak valid.",
                code
            )
            else -> PosApiResult.Failure(
                    detail?.let { "Sistem gagal memproses permintaan (HTTP $code): $it" }
                        ?: "Sistem gagal memproses permintaan (HTTP $code).",
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
