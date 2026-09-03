package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.label.LabelDate
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosCategory
import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosProductEditInput
import com.niimbot.printagent.pos.PosProductMeta
import com.niimbot.printagent.pos.PosProductRules
import com.niimbot.printagent.pos.PosSupplier
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProductDetailFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var progress: ProgressBar
    private lateinit var content: View
    private lateinit var error: TextView
    private lateinit var title: TextView
    private lateinit var sku: TextView
    private lateinit var stock: TextView
    private lateinit var status: TextView
    private lateinit var brand: TextView
    private lateinit var category: TextView
    private lateinit var supplier: TextView
    private lateinit var prices: TextView
    private lateinit var minimumStock: TextView
    private lateinit var unit: TextView
    private lateinit var description: TextView
    private lateinit var createdAt: TextView
    private lateinit var updatedAt: TextView
    private lateinit var editButton: View
    private lateinit var addStockButton: View
    private lateinit var subtractStockButton: View

    private val productId: Long by lazy { requireArguments().getLong(ARG_PRODUCT_ID) }
    private var product: PosProduct? = null
    private var metadata: PosProductMeta = PosProductMeta()
    private val currency = NumberFormat.getNumberInstance(Locale("id", "ID"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_product_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        view.findViewById<View>(R.id.btn_product_detail_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        editButton.setOnClickListener { product?.let(::showEditDialog) }
        addStockButton.setOnClickListener { product?.let(::showAddStockDialog) }
        subtractStockButton.setOnClickListener { showMissingStockOutApi() }
        loadProduct()
    }

    private fun bindViews(view: View) {
        progress = view.findViewById(R.id.progress_product_detail)
        content = view.findViewById(R.id.product_detail_content)
        error = view.findViewById(R.id.tv_product_detail_error)
        title = view.findViewById(R.id.tv_product_detail_name)
        sku = view.findViewById(R.id.tv_product_detail_sku)
        stock = view.findViewById(R.id.tv_product_detail_stock)
        status = view.findViewById(R.id.tv_product_detail_status)
        brand = view.findViewById(R.id.tv_product_detail_brand)
        category = view.findViewById(R.id.tv_product_detail_category)
        supplier = view.findViewById(R.id.tv_product_detail_supplier)
        prices = view.findViewById(R.id.tv_product_detail_prices)
        minimumStock = view.findViewById(R.id.tv_product_detail_min_stock)
        unit = view.findViewById(R.id.tv_product_detail_unit)
        description = view.findViewById(R.id.tv_product_detail_description)
        createdAt = view.findViewById(R.id.tv_product_detail_created)
        updatedAt = view.findViewById(R.id.tv_product_detail_updated)
        editButton = view.findViewById(R.id.btn_product_detail_edit)
        addStockButton = view.findViewById(R.id.btn_product_detail_add_stock)
        subtractStockButton = view.findViewById(R.id.btn_product_detail_subtract_stock)
    }

    private fun loadProduct() {
        val key = configStore.getIntegrationKey()
        if (key.isNullOrBlank()) {
            showError(getString(R.string.product_info_key_required))
            return
        }
        progress.visibility = View.VISIBLE
        content.visibility = View.GONE
        error.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = posApiClient.getProductById(configStore.getBaseUrl(), key, productId)) {
                is PosApiResult.Success -> {
                    product = result.value
                    render(result.value)
                    progress.visibility = View.GONE
                    content.visibility = View.VISIBLE
                    loadMetadata(key)
                }
                PosApiResult.NotFound -> showError(getString(R.string.product_not_found))
                is PosApiResult.Failure -> showError(result.message)
            }
        }
    }

    private suspend fun loadMetadata(key: String) {
        when (val result = posApiClient.getProductMeta(configStore.getBaseUrl(), key)) {
            is PosApiResult.Success -> metadata = result.value
            else -> Unit
        }
    }

    private fun render(item: PosProduct) {
        title.text = item.nama
        sku.text = getString(R.string.product_sku_value, item.sku)
        stock.text = getString(R.string.product_stock_value, item.stok, item.satuan)
        status.text = item.stokStatus?.replaceFirstChar { it.titlecase(Locale("id", "ID")) }
            ?: getString(R.string.product_status_unknown)
        brand.text = item.merek.orDash()
        category.text = item.kategori?.nama.orDash()
        supplier.text = item.supplier?.let { supplierItem ->
            if (supplierItem.codeForLabel == supplierItem.displayName) supplierItem.displayName
            else "${supplierItem.codeForLabel} · ${supplierItem.displayName}"
        }.orDash()
        prices.text = getString(
            R.string.product_detail_price_value,
            currency.format(item.hargaBeli),
            currency.format(item.hargaJual),
            item.hargaBeliKode.orDash()
        )
        minimumStock.text = item.stokMinimum.toString()
        unit.text = item.satuan
        description.text = item.deskripsi.orDash()
        createdAt.text = formatTimestamp(item.createdAt)
        updatedAt.text = formatTimestamp(item.updatedAt)
    }

    private fun showEditDialog(item: PosProduct) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_product_full, null)
        val skuInput = dialogView.findViewById<EditText>(R.id.et_edit_product_sku)
        val nameInput = dialogView.findViewById<EditText>(R.id.et_edit_product_name)
        val brandInput = dialogView.findViewById<EditText>(R.id.et_edit_product_brand)
        val categoryInput = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_edit_product_category)
        val supplierInput = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_edit_product_supplier)
        val buyPriceInput = dialogView.findViewById<EditText>(R.id.et_edit_product_buy_price)
        val buyCodeInput = dialogView.findViewById<EditText>(R.id.et_edit_product_buy_code)
        val sellPriceInput = dialogView.findViewById<EditText>(R.id.et_edit_product_sell_price)
        val minStockInput = dialogView.findViewById<EditText>(R.id.et_edit_product_min_stock)
        val unitInput = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_edit_product_unit)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.et_edit_product_description)
        val nameLayout = dialogView.findViewById<TextInputLayout>(R.id.til_edit_product_name)
        val buyPriceLayout = dialogView.findViewById<TextInputLayout>(R.id.til_edit_product_buy_price)
        val sellPriceLayout = dialogView.findViewById<TextInputLayout>(R.id.til_edit_product_sell_price)

        val categoryOptions = listOf<PosCategory?>(null) + metadata.categories
        val supplierOptions = listOf<PosSupplier?>(null) + metadata.suppliers
        val unitOptions = (metadata.satuan + item.satuan).filter { it.isNotBlank() }.distinct()
        categoryInput.setAdapter(dropdownAdapter(categoryOptions.map { it?.nama ?: getString(R.string.product_none) }))
        supplierInput.setAdapter(dropdownAdapter(supplierOptions.map { it?.displayName ?: getString(R.string.product_none) }))
        unitInput.setAdapter(dropdownAdapter(unitOptions))

        skuInput.setText(item.sku)
        nameInput.setText(item.nama)
        brandInput.setText(item.merek.orEmpty())
        categoryInput.setText(item.kategori?.nama ?: getString(R.string.product_none), false)
        supplierInput.setText(item.supplier?.displayName ?: getString(R.string.product_none), false)
        buyPriceInput.setText(item.hargaBeli.toString())
        buyCodeInput.setText(item.hargaBeliKode.orEmpty())
        sellPriceInput.setText(item.hargaJual.toString())
        minStockInput.setText(item.stokMinimum.toString())
        unitInput.setText(item.satuan, false)
        descriptionInput.setText(item.deskripsi.orEmpty())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.product_edit_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.product_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                buyPriceLayout.error = null
                sellPriceLayout.error = null
                val normalizedSku = PosProductRules.normalizeSku(skuInput.text.toString())
                val name = nameInput.text.toString().trim()
                val buyPrice = buyPriceInput.text.toString().toLongOrNull()
                val sellPrice = sellPriceInput.text.toString().toLongOrNull()
                val minimum = minStockInput.text.toString().toIntOrNull()
                if (normalizedSku.isBlank() || name.isBlank()) nameLayout.error = getString(R.string.product_name_required)
                if (buyPrice == null || buyPrice < 0) buyPriceLayout.error = getString(R.string.product_price_invalid)
                if (sellPrice == null || sellPrice < 0) sellPriceLayout.error = getString(R.string.product_price_invalid)
                if (normalizedSku.isBlank() || name.isBlank() || buyPrice == null || buyPrice < 0 || sellPrice == null || sellPrice < 0 || minimum == null || minimum < 0) {
                    return@setOnClickListener
                }
                val categoryId = categoryOptions.firstOrNull { it?.nama == categoryInput.text.toString() }?.id
                val supplierId = supplierOptions.firstOrNull { it?.displayName == supplierInput.text.toString() }?.id
                val input = PosProductEditInput(
                    sku = normalizedSku,
                    nama = name,
                    merek = brandInput.text.toString().trim().ifEmpty { null },
                    kategoriId = categoryId,
                    supplierId = supplierId,
                    hargaBeli = buyPrice,
                    hargaBeliKode = buyCodeInput.text.toString().trim().ifEmpty { null },
                    hargaJual = sellPrice,
                    stokMinimum = minimum,
                    satuan = unitInput.text.toString().trim().ifEmpty { item.satuan },
                    deskripsi = descriptionInput.text.toString().trim().ifEmpty { null }
                )
                updateProduct(input, dialog)
            }
        }
        dialog.show()
    }

    private fun updateProduct(input: PosProductEditInput, dialog: androidx.appcompat.app.AlertDialog) {
        val key = configStore.getIntegrationKey() ?: return
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = posApiClient.updateProductById(configStore.getBaseUrl(), key, productId, input)) {
                is PosApiResult.Success -> {
                    product = result.value
                    render(result.value)
                    dialog.dismiss()
                    Toast.makeText(requireContext(), R.string.product_update_success, Toast.LENGTH_SHORT).show()
                }
                PosApiResult.NotFound -> showToast(getString(R.string.product_not_found), dialog)
                is PosApiResult.Failure -> showToast(result.message, dialog)
            }
        }
    }

    private fun showAddStockDialog(item: PosProduct) {
        val view = layoutInflater.inflate(R.layout.dialog_adjust_stock, null)
        val quantityInput = view.findViewById<EditText>(R.id.et_adjust_stock_quantity)
        val priceInput = view.findViewById<EditText>(R.id.et_adjust_stock_price)
        val quantityLayout = view.findViewById<TextInputLayout>(R.id.til_adjust_stock_quantity)
        val priceLayout = view.findViewById<TextInputLayout>(R.id.til_adjust_stock_price)
        priceInput.setText(item.hargaBeli.toString())
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.product_add_stock)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.product_stock_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                quantityLayout.error = null
                priceLayout.error = null
                val quantity = quantityInput.text.toString().toIntOrNull()
                val price = priceInput.text.toString().toLongOrNull()
                if (quantity == null || quantity <= 0) quantityLayout.error = getString(R.string.product_stock_quantity_invalid)
                if (price == null || price < 0) priceLayout.error = getString(R.string.product_price_invalid)
                if (quantity == null || quantity <= 0 || price == null || price < 0) return@setOnClickListener
                addStock(item, quantity, price, dialog)
            }
        }
        dialog.show()
    }

    private fun addStock(item: PosProduct, quantity: Int, price: Long, dialog: androidx.appcompat.app.AlertDialog) {
        val key = configStore.getIntegrationKey() ?: return
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.addStock(
                    configStore.getBaseUrl(),
                    key,
                    item.sku,
                    quantity,
                    price,
                    UUID.randomUUID().toString()
                )
            ) {
                is PosApiResult.Success -> {
                    product = result.value
                    render(result.value)
                    dialog.dismiss()
                    Toast.makeText(requireContext(), R.string.product_stock_updated, Toast.LENGTH_SHORT).show()
                }
                PosApiResult.NotFound -> showToast(getString(R.string.product_not_found), dialog)
                is PosApiResult.Failure -> showToast(result.message, dialog)
            }
        }
    }

    private fun showMissingStockOutApi() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.product_subtract_stock)
            .setMessage(R.string.product_stock_out_api_missing)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dropdownAdapter(values: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), R.layout.item_label_dropdown, values)

    private fun showToast(message: String, dialog: androidx.appcompat.app.AlertDialog) {
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = true
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        content.visibility = View.GONE
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun formatTimestamp(value: String?): String {
        val date = LabelDate.fromTimestamp(value)?.let(LabelDate::display)
        val time = value?.substringAfter('T', "")?.take(5)?.takeIf { it.length == 5 }
        return listOfNotNull(date, time).joinToString(" ").ifEmpty { "—" }
    }

    private fun String?.orDash(): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: "—"

    companion object {
        private const val ARG_PRODUCT_ID = "product_id"

        fun newInstance(productId: Long) = ProductDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_PRODUCT_ID, productId) }
        }
    }
}
