package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.label.LabelDate
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosProductPhoto
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var photoList: RecyclerView
    private lateinit var photoEmpty: View
    private lateinit var photoCount: TextView
    private lateinit var photoAdapter: ProductDetailPhotoAdapter

    private val productId: Long by lazy { requireArguments().getLong(ARG_PRODUCT_ID) }
    private var product: PosProduct? = null
    private val currency = NumberFormat.getNumberInstance(Locale("id", "ID"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_product_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        parentFragmentManager.setFragmentResultListener(
            ProductEditFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ -> loadProduct() }
        view.findViewById<View>(R.id.btn_product_detail_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        editButton.setOnClickListener { openEditPage() }
        addStockButton.setOnClickListener { product?.let(::showAddStockDialog) }
        subtractStockButton.setOnClickListener { product?.let(::showSubtractStockDialog) }
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
        photoList = view.findViewById(R.id.rv_product_detail_photos)
        photoEmpty = view.findViewById(R.id.product_detail_photo_empty)
        photoCount = view.findViewById(R.id.tv_product_detail_photo_count)
        photoList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        photoAdapter = ProductDetailPhotoAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            loadPhoto = { photoUrl ->
                when (val result = posApiClient.downloadProductPhoto(configStore.getBaseUrl(), photoUrl)) {
                    is PosApiResult.Success -> withContext(Dispatchers.Default) {
                        ProductPhotoFiles.decodePreview(result.value, PRODUCT_DETAIL_PHOTO_SIZE_PX)
                    }
                    else -> null
                }
            },
            onPhotoClick = ::openPhotoFullscreen
        )
        photoList.adapter = photoAdapter
    }

    private fun loadProduct() {
        val accessToken = configStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            showError(getString(R.string.pos_login_required))
            return
        }
        progress.visibility = View.VISIBLE
        content.visibility = View.GONE
        error.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = posApiClient.getProductById(configStore.getBaseUrl(), accessToken, productId)) {
                is PosApiResult.Success -> {
                    product = result.value
                    render(result.value)
                    progress.visibility = View.GONE
                    content.visibility = View.VISIBLE
                }
                PosApiResult.NotFound -> showError(getString(R.string.product_not_found))
                PosApiResult.SessionExpired -> expireSession()
                is PosApiResult.Failure -> showError(result.message)
            }
        }
    }

    private fun render(item: PosProduct) {
        renderProductPhotos(item)
        title.text = item.nama
        sku.text = getString(R.string.product_sku_value, item.sku)
        stock.text = getString(R.string.product_stock_value, item.stok, item.satuan)
        status.text = item.stokStatus?.replaceFirstChar { it.titlecase(Locale("id", "ID")) }
            ?: getString(R.string.product_status_unknown)
        brand.text = item.merek.orDash()
        supplier.text = item.displaySuppliers.joinToString("\n") { supplierItem ->
            val label = if (supplierItem.codeForLabel == supplierItem.displayName) {
                supplierItem.displayName
            } else {
                "${supplierItem.codeForLabel} · ${supplierItem.displayName}"
            }
            if (supplierItem.isPrimary) getString(R.string.product_supplier_primary_value, label) else label
        }.ifEmpty { "—" }
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

    private fun openEditPage() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProductEditFragment.newInstance(productId))
            .addToBackStack("product_edit_$productId")
            .commit()
    }

    private fun openPhotoFullscreen(photo: PosProductPhoto) {
        val reference = photo.downloadReference ?: return
        val position = product?.displayPhotos?.indexOf(photo)?.takeIf { it >= 0 } ?: 0
        ProductPhotoFullscreenDialogFragment.newInstance(reference, position + 1)
            .show(parentFragmentManager, ProductPhotoFullscreenDialogFragment.TAG)
    }

    private fun renderProductPhotos(item: PosProduct) {
        val photos = item.displayPhotos
        photoAdapter.submitList(photos)
        photoList.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        photoEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        photoCount.text = resources.getQuantityString(R.plurals.product_photo_count, photos.size, photos.size)
        photoCount.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAddStockDialog(item: PosProduct) = showStockDialog(item, adding = true)

    private fun showSubtractStockDialog(item: PosProduct) = showStockDialog(item, adding = false)

    private fun showStockDialog(item: PosProduct, adding: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_adjust_stock, null)
        val quantityInput = view.findViewById<EditText>(R.id.et_adjust_stock_quantity)
        val priceInput = view.findViewById<EditText>(R.id.et_adjust_stock_price)
        val quantityLayout = view.findViewById<TextInputLayout>(R.id.til_adjust_stock_quantity)
        val priceLayout = view.findViewById<TextInputLayout>(R.id.til_adjust_stock_price)
        quantityLayout.hint = getString(
            if (adding) R.string.product_stock_quantity else R.string.product_stock_out_quantity
        )
        priceInput.setText((if (adding) item.hargaBeli else item.hargaJual).toString())
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (adding) R.string.product_add_stock else R.string.product_subtract_stock)
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
                if (quantity == null || quantity <= 0) {
                    quantityLayout.error = getString(R.string.product_stock_quantity_invalid)
                }
                if (price == null || price < 0) {
                    priceLayout.error = getString(R.string.product_price_invalid)
                }
                if (quantity == null || quantity <= 0 || price == null || price < 0) {
                    return@setOnClickListener
                }
                adjustStock(item, quantity, price, adding, dialog)
            }
        }
        dialog.showWithBoxedButtons()
    }

    private fun adjustStock(
        item: PosProduct,
        quantity: Int,
        price: Long,
        adding: Boolean,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        val accessToken = configStore.getAccessToken() ?: return
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val operationId = UUID.randomUUID().toString()
            val result = if (adding) {
                posApiClient.addStock(
                    configStore.getBaseUrl(), accessToken, item, quantity, price, operationId
                )
            } else {
                posApiClient.subtractStock(
                    configStore.getBaseUrl(), accessToken, item, quantity, price, operationId
                )
            }
            when (result) {
                is PosApiResult.Success -> {
                    product = result.value
                    render(result.value)
                    dialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        if (adding) R.string.product_stock_updated else R.string.product_stock_out_updated,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PosApiResult.NotFound -> showToast(getString(R.string.product_not_found), dialog)
                PosApiResult.SessionExpired -> {
                    dialog.dismiss()
                    expireSession()
                }
                is PosApiResult.Failure -> showToast(result.message, dialog)
            }
        }
    }

    private fun expireSession() {
        configStore.clearSession()
        showError(getString(R.string.pos_session_expired))
    }

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
        private const val PRODUCT_DETAIL_PHOTO_SIZE_PX = 768

        fun newInstance(productId: Long) = ProductDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_PRODUCT_ID, productId) }
        }
    }
}
