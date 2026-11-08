package com.niimbot.printagent.pos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PosSupplierOrder(
    val id: Long,
    @SerialName("nama_barang") val namaBarang: String,
    @SerialName("jumlah_barang") val jumlahBarang: Int,
    val status: String
)

@Serializable
data class PosSupplierOrderInput(
    @SerialName("nama_barang") val namaBarang: String,
    @SerialName("jumlah_barang") val jumlahBarang: Int,
    val status: String
)

@Serializable
internal data class PosSupplierOrderEnvelope(val data: PosSupplierOrder)

@Serializable
internal data class PosSupplierOrderListEnvelope(val data: List<PosSupplierOrder>)

@Serializable
internal data class PosSupplierOrderItemsEnvelope(val items: List<PosSupplierOrder>)

enum class PosSupplierOrderStatus(val apiValue: String) {
    PENDING("pending"),
    ORDERED("dipesan"),
    RECEIVED("diterima"),
    CANCELLED("dibatalkan");

    companion object {
        fun fromApi(value: String): PosSupplierOrderStatus? = entries.firstOrNull {
            it.apiValue == value.trim().lowercase()
        }
    }
}

object PosSupplierOrderRules {
    const val MAX_NAME_LENGTH = 200

    fun normalizeName(value: String): String? = value.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    fun parsePositiveQuantity(value: String): Int? = value.trim()
        .toIntOrNull()
        ?.takeIf { it > 0 }

    fun normalizeStatus(value: String): String? = PosSupplierOrderStatus.fromApi(value)?.apiValue

    fun matchesName(order: PosSupplierOrder, query: String): Boolean =
        query.trim().isEmpty() || order.namaBarang.contains(query.trim(), ignoreCase = true)
}
