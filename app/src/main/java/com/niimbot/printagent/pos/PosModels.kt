package com.niimbot.printagent.pos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PosProduct(
    val sku: String,
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String? = null,
    val stok: Int = 0,
    val satuan: String = "pcs",
    val id: Long? = null
)

@Serializable
internal data class PosProductEnvelope(val data: PosProduct)

@Serializable
internal data class PosProductWriteRequest(
    val sku: String,
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long,
    val stok: Int,
    val satuan: String
)

@Serializable
internal data class PosProductUpdateRequest(
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long
)
