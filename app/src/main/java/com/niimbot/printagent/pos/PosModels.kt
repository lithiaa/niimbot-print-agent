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
data class PosProductSearchResponse(val data: List<PosProduct>)

@Serializable
internal data class PosProductWriteRequest(
    val sku: String,
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("jumlah_barang_masuk") val jumlahBarangMasuk: Int,
    @SerialName("operation_id") val operationId: String,
    val satuan: String
)

@Serializable
internal data class PosStockInRequest(
    @SerialName("jumlah_barang_masuk") val jumlahBarangMasuk: Int,
    @SerialName("harga_satuan") val hargaSatuan: Long,
    @SerialName("operation_id") val operationId: String
)

@Serializable
internal data class PosProductUpdateRequest(
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long
)
