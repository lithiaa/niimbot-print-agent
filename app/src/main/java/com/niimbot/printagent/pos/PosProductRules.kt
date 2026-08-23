package com.niimbot.printagent.pos

import com.niimbot.printagent.label.LabelData
import java.util.Locale

enum class PosLookupDecision {
    PRINT_POS,
    SHOW_CONFLICT
}

object PosProductRules {
    fun normalizeSku(sku: String): String = sku.trim().uppercase(Locale.ROOT)

    fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    fun decideExisting(form: LabelData, product: PosProduct): PosLookupDecision {
        val isSame = normalizeSku(form.sku) == normalizeSku(product.sku) &&
            form.nama == product.nama &&
            form.hargaBeli == product.hargaBeli &&
            form.hargaJual == product.hargaJual
        return if (isSame) PosLookupDecision.PRINT_POS else PosLookupDecision.SHOW_CONFLICT
    }

    fun toLabelData(product: PosProduct, qty: Int, jumlahBarangMasuk: Int): LabelData = LabelData(
        sku = normalizeSku(product.sku),
        nama = product.nama,
        hargaBeli = product.hargaBeli,
        hargaJual = product.hargaJual,
        qty = qty,
        jumlahBarangMasuk = jumlahBarangMasuk
    )
}
