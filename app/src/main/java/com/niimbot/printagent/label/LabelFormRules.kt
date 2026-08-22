package com.niimbot.printagent.label

import com.niimbot.printagent.pos.PosProductRules

data class LabelFormInput(
    val sku: String,
    val nama: String,
    val hargaBeli: String,
    val hargaJual: String,
    val qty: String
)

data class LabelData(
    val sku: String,
    val nama: String,
    val hargaBeli: Long,
    val hargaJual: Long,
    val qty: Int
)

enum class LabelField {
    SKU, NAMA, HARGA_BELI, HARGA_JUAL, QTY
}

data class LabelValidationResult(
    val data: LabelData?,
    val errors: Map<LabelField, String>
)

object LabelFormRules {
    fun validate(input: LabelFormInput): LabelValidationResult {
        val errors = linkedMapOf<LabelField, String>()
        val sku = PosProductRules.normalizeSku(input.sku)
        val nama = input.nama.trim()
        val hargaBeli = input.hargaBeli.trim().toLongOrNull()
        val hargaJual = input.hargaJual.trim().toLongOrNull()
        val qty = input.qty.trim().toIntOrNull()

        if (sku.isEmpty()) errors[LabelField.SKU] = "SKU wajib diisi"
        if (nama.isEmpty()) errors[LabelField.NAMA] = "Nama wajib diisi"
        if (hargaBeli == null || hargaBeli < 0) {
            errors[LabelField.HARGA_BELI] = "Harga beli harus berupa angka nol atau lebih"
        }
        if (hargaJual == null || hargaJual < 0) {
            errors[LabelField.HARGA_JUAL] = "Harga jual harus berupa angka nol atau lebih"
        }
        if (qty == null || qty <= 0) errors[LabelField.QTY] = "Qty minimal 1"

        val data = if (errors.isEmpty()) {
            LabelData(sku, nama, hargaBeli!!, hargaJual!!, qty!!)
        } else {
            null
        }
        return LabelValidationResult(data, errors)
    }
}
