package com.niimbot.printagent.ui

import android.os.Bundle
import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosProductRules

internal data class LabelPrefill(
    val sku: String,
    val name: String,
    val purchasePrice: Long,
    val salePrice: Long,
    val purchasePriceCode: String,
    val supplierId: Long?,
    val supplierCode: String,
    val supplierDisplay: String,
    val createdAt: String?
)

internal object LabelPrefillContract {
    const val REQUEST_KEY = "label_prefill_product"

    private const val SKU = "sku"
    private const val NAME = "name"
    private const val PURCHASE_PRICE = "purchase_price"
    private const val SALE_PRICE = "sale_price"
    private const val PURCHASE_PRICE_CODE = "purchase_price_code"
    private const val SUPPLIER_ID = "supplier_id"
    private const val SUPPLIER_CODE = "supplier_code"
    private const val SUPPLIER_DISPLAY = "supplier_display"
    private const val CREATED_AT = "created_at"

    fun fromProduct(product: PosProduct): LabelPrefill {
        val supplier = product.supplier
        val supplierDisplay = supplier?.let {
            if (it.codeForLabel == it.displayName) it.displayName
            else "${it.displayName} - ${it.codeForLabel}"
        }.orEmpty()
        return LabelPrefill(
            sku = PosProductRules.normalizeSku(product.sku),
            name = product.nama,
            purchasePrice = product.hargaBeli,
            salePrice = product.hargaJual,
            purchasePriceCode = product.hargaBeliKode.orEmpty(),
            supplierId = supplier?.id,
            supplierCode = supplier?.codeForLabel.orEmpty(),
            supplierDisplay = supplierDisplay,
            createdAt = product.createdAt
        )
    }

    fun toBundle(product: PosProduct): Bundle {
        val prefill = fromProduct(product)
        return Bundle().apply {
            putString(SKU, prefill.sku)
            putString(NAME, prefill.name)
            putLong(PURCHASE_PRICE, prefill.purchasePrice)
            putLong(SALE_PRICE, prefill.salePrice)
            putString(PURCHASE_PRICE_CODE, prefill.purchasePriceCode)
            prefill.supplierId?.let { putLong(SUPPLIER_ID, it) }
            putString(SUPPLIER_CODE, prefill.supplierCode)
            putString(SUPPLIER_DISPLAY, prefill.supplierDisplay)
            putString(CREATED_AT, prefill.createdAt)
        }
    }

    fun fromBundle(bundle: Bundle): LabelPrefill = LabelPrefill(
        sku = bundle.getString(SKU).orEmpty(),
        name = bundle.getString(NAME).orEmpty(),
        purchasePrice = bundle.getLong(PURCHASE_PRICE),
        salePrice = bundle.getLong(SALE_PRICE),
        purchasePriceCode = bundle.getString(PURCHASE_PRICE_CODE).orEmpty(),
        supplierId = bundle.getLong(SUPPLIER_ID).takeIf { bundle.containsKey(SUPPLIER_ID) },
        supplierCode = bundle.getString(SUPPLIER_CODE).orEmpty(),
        supplierDisplay = bundle.getString(SUPPLIER_DISPLAY).orEmpty(),
        createdAt = bundle.getString(CREATED_AT)
    )
}
