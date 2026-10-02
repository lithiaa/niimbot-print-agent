package com.niimbot.printagent.pos

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@Serializable
data class PosLogin(@SerialName("access_token") val accessToken: String)

@Serializable
data class PosEnvironment(
    val id: Long,
    val name: String,
    val status: String
)

@Serializable
data class PosIdentity(
    val username: String,
    val role: String,
    val environment: PosEnvironment? = null,
    val permissions: List<String> = emptyList()
)

object PosIdentityAccess {
    fun canMutate(permissions: List<String>, permission: String): Boolean {
        // ponytail: empty means legacy server; enforce once all identity responses include permissions.
        return permissions.isEmpty() || permission in permissions
    }
}

@Serializable
internal data class PosLoginRequest(val username: String, val password: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PosProduct(
    val sku: String,
    val nama: String,
    @SerialName("harga_beli") @JsonNames("harga_modal") val hargaBeli: Long,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String? = null,
    @JsonNames("stok_awal") val stok: Int = 0,
    val satuan: String = "pcs",
    val id: Long? = null,
    val merek: String? = null,
    val foto: String? = null,
    @SerialName("foto_url") val fotoUrl: String? = null,
    val supplier: PosSupplier? = null,
    @SerialName("primary_supplier_id") val primarySupplierId: Long? = null,
    @SerialName("primary_supplier") val primarySupplier: PosSupplier? = null,
    val suppliers: List<PosSupplier> = emptyList(),
    val photos: List<PosProductPhoto> = emptyList(),
    @SerialName("stok_minimum") val stokMinimum: Int = 0,
    @SerialName("stok_status") @JsonNames("status") val stokStatus: String? = null,
    val deskripsi: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    val displayPhotos: List<PosProductPhoto>
        get() {
            val available = photos.filter { !it.downloadReference.isNullOrBlank() }
                .sortedWith(compareByDescending<PosProductPhoto> { it.isPrimary }.thenBy { it.urutan })
            if (available.isNotEmpty()) {
                return if (available.any { it.isPrimary }) available else {
                    available.mapIndexed { index, photo ->
                        if (index == 0) photo.copy(isPrimary = true) else photo
                    }
                }
            }
            val legacyUrl = fotoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return listOf(PosProductPhoto(foto = foto, fotoUrl = legacyUrl, isPrimary = true))
        }

    val displaySuppliers: List<PosSupplier>
        get() {
            val primary = primarySupplier ?: supplier
            val primaryId = primarySupplierId ?: primary?.id
            val available = if (suppliers.isNotEmpty()) suppliers else listOfNotNull(primary)
            return available.distinctBy { it.id }.map { supplierItem ->
                if (!supplierItem.isPrimary && supplierItem.id == primaryId) {
                    supplierItem.copy(isPrimary = true)
                } else {
                    supplierItem
                }
            }.sortedByDescending { it.isPrimary }
        }
}

@Serializable
data class PosProductPhoto(
    val id: Long? = null,
    val filename: String? = null,
    val foto: String? = null,
    @SerialName("foto_url") val fotoUrl: String? = null,
    val urutan: Int = 0,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
) {
    val downloadReference: String?
        get() = fotoUrl?.trim()?.takeIf { it.isNotEmpty() }
}

data class PosPhotoUpload(
    val bytes: ByteArray,
    val mediaType: String,
    val fileName: String
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
data class PosStockStatisticItem(
    val id: Long,
    val sku: String,
    val nama: String,
    val stok: Int,
    @SerialName("stok_minimum") val stokMinimum: Int,
    val satuan: String = "pcs",
    val foto: String? = null
)

@Serializable
data class PosInventoryStatistics(
    @SerialName("total_barang") val totalBarang: Int,
    @SerialName("total_stok") val totalStok: Long,
    @SerialName("total_stok_menipis") val totalStokMenipis: Int,
    @SerialName("total_stok_habis") val totalStokHabis: Int,
    @SerialName("stok_menipis") val stokMenipis: List<PosStockStatisticItem> = emptyList(),
    @SerialName("stok_habis") val stokHabis: List<PosStockStatisticItem> = emptyList()
)

@Serializable
data class PosActivityLog(
    val id: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("user_id") val userId: Long? = null,
    val username: String? = null,
    val action: String,
    @SerialName("http_method") val httpMethod: String,
    val resource: String,
    @SerialName("resource_id") val resourceId: String? = null,
    val path: String,
    @SerialName("status_code") val statusCode: Int,
    @SerialName("ip_address") val ipAddress: String? = null,
    val summary: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class PosActivityLogListResponse(
    val total: Int,
    val page: Int,
    val limit: Int,
    val data: List<PosActivityLog>
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
    val email: String? = null,
    @SerialName("jumlah_barang") val jumlahBarang: Int = 0,
    @SerialName("jumlah_masuk_kumulatif") val jumlahMasukKumulatif: Int = 0,
    @SerialName("is_primary") val isPrimary: Boolean = false
) {
    val displayName: String
        get() = namaSupplier?.trim()?.takeIf { it.isNotEmpty() }
            ?: nama.trim().ifEmpty { "Supplier #$id" }

    val codeForLabel: String
        get() = kodeSupplier?.trim()?.takeIf { it.isNotEmpty() }
            ?: kode?.trim()?.takeIf { it.isNotEmpty() }
            ?: displayName
}

@Serializable
internal data class PosSupplierEnvelope(val data: List<PosSupplier>)

@Serializable
data class PosProductMeta(
    val suppliers: List<PosSupplier> = emptyList(),
    val satuan: List<String> = emptyList()
)

@Serializable
internal data class PosProductCreateRequest(
    val sku: String?,
    val nama: String,
    val merek: String?,
    @SerialName("supplier_id") val supplierId: Long?,
    @SerialName("harga_modal") val hargaModal: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String?,
    @SerialName("harga_jual_kode") val hargaJualKode: String?,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("stok_minimum") val stokMinimum: Int,
    val satuan: String,
    val deskripsi: String?,
    val foto: String?,
    @SerialName("stok_awal") val stokAwal: Int
)

data class PosProductCreateInput(
    val sku: String?,
    val nama: String,
    val merek: String?,
    val supplierId: Long?,
    val hargaBeli: Long,
    val hargaBeliKode: String?,
    val hargaJual: Long,
    val stokMinimum: Int,
    val stokAwal: Int,
    val satuan: String,
    val deskripsi: String?
)

@Serializable
internal data class PosStockAdjustmentRequest(
    @SerialName("barang_id") val barangId: Long,
    @SerialName("jumlah") val jumlah: Int,
    @SerialName("harga_satuan") val hargaSatuan: Long,
    val keterangan: String
)

@Serializable
internal data class PosProductUpdateRequest(
    val nama: String,
    @SerialName("harga_modal") val hargaModal: Long,
    @SerialName("harga_jual_kode") val hargaJualKode: String
)

@Serializable
data class PosProductEditInput(
    val sku: String,
    val nama: String,
    val merek: String?,
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
    @SerialName("supplier_id") val supplierId: Long?,
    @SerialName("harga_modal") val hargaModal: Long,
    @SerialName("harga_beli_kode") val hargaBeliKode: String?,
    @SerialName("harga_jual_kode") val hargaJualKode: String,
    @SerialName("harga_jual") val hargaJual: Long,
    @SerialName("stok_minimum") val stokMinimum: Int,
    val satuan: String,
    val deskripsi: String?
)
