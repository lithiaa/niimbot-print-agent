package com.niimbot.printagent.ble

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class DetectedLabelSize(val widthMm: Int, val heightMm: Int)

@Singleton
class NiimbotLabelMetadataClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    suspend fun getLabelSize(barcode: String): DetectedLabelSize? = withContext(Dispatchers.IO) {
        if (barcode.isBlank()) return@withContext null
        val body = json.encodeToString(LabelMetadataRequest(barcode))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(METADATA_URL)
            .header("niimbot-user-agent", "Android/NiimbotPrintAgent")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body?.string().orEmpty()
                val data = json.decodeFromString<LabelMetadataResponse>(payload).data
                    ?: return@use null
                if (data.width <= 0 || data.height <= 0) null
                else DetectedLabelSize(data.width, data.height)
            }
        }.getOrNull()
    }

    private companion object {
        const val METADATA_URL =
            "https://print.niimbot.com/api/template/getCloudTemplateByOneCode"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class LabelMetadataRequest(@SerialName("oneCode") val oneCode: String)

@Serializable
private data class LabelMetadataResponse(val data: LabelMetadataData? = null)

@Serializable
private data class LabelMetadataData(val width: Int, val height: Int)
