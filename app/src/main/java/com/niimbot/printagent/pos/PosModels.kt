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
    val id: Long? = null,
    val merek: String? = null,
    val foto: String? = null,
    @SerialName("foto_url") val fotoUrl: String? = null,
    val kategori: PosCategory? = null,
    val supplier: PosSupplier? = null,
    @SerialName("stok_minimum") val stokMinimum: Int = 0,
    @SerialName("stok_status") val stokStatus: String? = null,
    val deskripsi: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
internal data class PosProductEnvelope(val data: PosProduct)

@Serializable
data class PosProductSearchResponse(val data: List<PosProduct>)

@Serializable
data class PosProductListResponse(
    val data: List<PosProduct>,
    val total: Int,
    val page: Int,
    val limit: Int
)

@Serializable
data class PosSupplier(
    val id: Long,
    val nama: String = "",
    val kode: String? = null,
    @SerialName("nama_supplier") val namaSupplier: String? = null,
    @SerialName("kode_supplier") val kodeSupplier: String? = null,
    val kontak: String? = null,
    val telepon: String? = null,
    val email: String? = null
) {
    val displayName: String
        get() = namaSupplier?.trim()?.takeIf { it.isNotEmpty() }
            ?: nama.trim().ifEmpty { "Pemasok #$id" }

    val codeForLabel: String
        get() = kodeSupplier?.trim()?.takeIf { it.isNotEmpty() }
            ?: kode?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayName
}

@Serializable
internal data class PosSupplierEnvelope(val data: List<PosSupplier>)

@Serializable
data class PosCategory(
    val id: Long,
    val nama: String,
    val deskripsi: String? = null
)

@Serializable
data class PosProductMeta(
    val categories: List<PosCategory> = emptyList(),
    val suppliers: List<PosSupplier> = emptyList(),
    val satuan: List<String> = emptyList()
)

@Serializable
internal data class PosProductWriteRequest(
    val sku: String,
    val nama: String,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String,
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
    @SerialName("harga_beli_kode") val hargaBeliKode: String?,
    @SerialName("harga_jual") val hargaJual: Long
)

@Serializable
data class PosProductEditInput(
    val sku: String,
    val nama: String,
    val merek: String?,
    val kategoriId: Long?,
    val supplierId: Long?,
    val hargaBeli: Long,
    val hargaBeliKode: String?,
    val hargaJual: Long,
    val stokMinimum: Int,
    val satuan: String,
    val deskripsi: String?
)

@Serializable
internal data class PosProductUpdateByIdRequest(
    val sku: String,
    val nama: String,
    val merek: String?,
    @SerialName("kategori_id") val kategoriId: Long?,
    @SerialName("supplier_id") val supplierId: Long?,
    @SerialName("harga_beli") val hargaBeli: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String?,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("stok_minimum") val stokMinimum: Int,
    val satuan: String,
    val deskripsi: String?
)
