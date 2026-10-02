package com.niimbot.printagent.ui

import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosProductCreateInput
import com.niimbot.printagent.pos.PosProductEditInput
import com.niimbot.printagent.pos.PosProductMeta
import com.niimbot.printagent.pos.PosProductRules
import com.niimbot.printagent.pos.PosSupplier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ProductEditFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private val productId: Long? by lazy {
        arguments?.takeIf { it.containsKey(ARG_PRODUCT_ID) }?.getLong(ARG_PRODUCT_ID)
    }
    private val isCreateMode: Boolean
        get() = productId == null
    private var product: PosProduct? = null
    private var metadata: PosProductMeta = PosProductMeta()
    private var supplierOptions: List<PosSupplier?> = emptyList()

    private lateinit var form: View
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private lateinit var title: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var skuLayout: TextInputLayout
    private lateinit var skuInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var brandInput: EditText
    private lateinit var supplierInput: AutoCompleteTextView
    private lateinit var buyPriceInput: EditText
    private lateinit var buyCodeInput: EditText
    private lateinit var sellPriceInput: EditText
    private lateinit var minStockInput: EditText
    private lateinit var unitInput: AutoCompleteTextView
    private lateinit var descriptionInput: EditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var buyPriceLayout: TextInputLayout
    private lateinit var sellPriceLayout: TextInputLayout
    private lateinit var minimumStockLayout: TextInputLayout
    private lateinit var initialStockLayout: TextInputLayout
    private lateinit var initialStockInput: EditText
    private lateinit var photoList: RecyclerView
    private lateinit var photoEmpty: View
    private lateinit var photoStatus: TextView
    private lateinit var photoAdapter: EditableProductPhotoAdapter

    private val existingPhotos = mutableListOf<EditableProductPhoto.Existing>()
    private val pendingPhotos = mutableListOf<EditableProductPhoto.Pending>()
    private val deletedPhotoIds = linkedSetOf<Long>()
    private var deleteLegacyPhoto = false
    private var nextLocalPhotoId = 0L
    private var pendingCameraUri: Uri? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) readSelectedPhotos(uris)
    }

    private val photoCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            readSelectedPhotos(listOf(uri), cameraUri = uri)
        } else {
            ProductPhotoCamera.deleteOutput(requireContext(), uri)
            pendingCameraUri = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_product_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingCameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        bindViews(view)
        if (isCreateMode) {
            title.setText(R.string.product_create_title)
            saveButton.setText(R.string.product_create_save)
            initialStockLayout.visibility = View.VISIBLE
        }
        view.findViewById<View>(R.id.btn_product_edit_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        saveButton.setOnClickListener { saveProduct() }
        view.findViewById<View>(R.id.btn_edit_product_camera).setOnClickListener {
            photoStatus.setText(R.string.product_photo_batch_loading)
            launchCamera()
        }
        view.findViewById<View>(R.id.btn_edit_product_gallery).setOnClickListener {
            photoStatus.setText(R.string.product_photo_batch_loading)
            photoPicker.launch("image/*")
        }
        loadForm()
    }

    private fun bindViews(view: View) {
        form = view.findViewById(R.id.product_edit_form)
        progress = view.findViewById(R.id.progress_product_edit)
        error = view.findViewById(R.id.tv_product_edit_error)
        title = view.findViewById(R.id.tv_product_edit_title)
        saveButton = view.findViewById(R.id.btn_product_edit_save)
        skuLayout = view.findViewById(R.id.til_edit_product_sku)
        skuInput = view.findViewById(R.id.et_edit_product_sku)
        nameInput = view.findViewById(R.id.et_edit_product_name)
        brandInput = view.findViewById(R.id.et_edit_product_brand)
        supplierInput = view.findViewById(R.id.dropdown_edit_product_supplier)
        buyPriceInput = view.findViewById(R.id.et_edit_product_buy_price)
        buyCodeInput = view.findViewById(R.id.et_edit_product_buy_code)
        sellPriceInput = view.findViewById(R.id.et_edit_product_sell_price)
        minStockInput = view.findViewById(R.id.et_edit_product_min_stock)
        unitInput = view.findViewById(R.id.dropdown_edit_product_unit)
        descriptionInput = view.findViewById(R.id.et_edit_product_description)
        nameLayout = view.findViewById(R.id.til_edit_product_name)
        buyPriceLayout = view.findViewById(R.id.til_edit_product_buy_price)
        sellPriceLayout = view.findViewById(R.id.til_edit_product_sell_price)
        minimumStockLayout = view.findViewById(R.id.til_edit_product_min_stock)
        initialStockLayout = view.findViewById(R.id.til_create_product_initial_stock)
        initialStockInput = view.findViewById(R.id.et_create_product_initial_stock)
        photoList = view.findViewById(R.id.rv_edit_product_photos)
        photoEmpty = view.findViewById(R.id.edit_product_photo_empty)
        photoStatus = view.findViewById(R.id.tv_edit_product_photo_status)
        photoList.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        photoAdapter = EditableProductPhotoAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            loadPhoto = { photoUrl ->
                when (val result = posApiClient.downloadProductPhoto(configStore.getBaseUrl(), photoUrl)) {
                    is PosApiResult.Success -> withContext(Dispatchers.Default) {
                        ProductPhotoFiles.decodePreview(result.value, EDIT_PHOTO_SIZE_PX)
                    }
                    else -> null
                }
            },
            onRemove = ::removePhoto
        )
        photoList.adapter = photoAdapter
    }

    private fun loadForm() {
        val accessToken = configStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            showError(getString(R.string.pos_login_required))
            return
        }
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            if (isCreateMode) {
                metadata = when (val meta = posApiClient.getProductMeta(configStore.getBaseUrl(), accessToken)) {
                    is PosApiResult.Success -> meta.value
                    PosApiResult.SessionExpired -> {
                        expireSession()
                        return@launch
                    }
                    else -> PosProductMeta()
                }
                populateCreateForm()
                setLoading(false)
                return@launch
            }
            val existingProductId = productId ?: return@launch
            when (val result = posApiClient.getProductById(configStore.getBaseUrl(), accessToken, existingProductId)) {
                is PosApiResult.Success -> {
                    product = result.value
                    metadata = when (val meta = posApiClient.getProductMeta(configStore.getBaseUrl(), accessToken)) {
                        is PosApiResult.Success -> meta.value
                        PosApiResult.SessionExpired -> {
                            expireSession()
                            return@launch
                        }
                        else -> PosProductMeta()
                    }
                    populateForm(result.value)
                    setLoading(false)
                }
                PosApiResult.NotFound -> showError(getString(R.string.product_not_found))
                PosApiResult.SessionExpired -> expireSession()
                is PosApiResult.Failure -> showError(result.message)
            }
        }
    }

    private fun populateCreateForm() {
        supplierOptions = listOf<PosSupplier?>(null) + metadata.suppliers.distinctBy { it.id }
        val unitOptions = (metadata.satuan + DEFAULT_UNIT).filter { it.isNotBlank() }.distinct()
        supplierInput.setAdapter(dropdownAdapter(supplierOptions.map { it?.displayName ?: getString(R.string.product_none) }))
        unitInput.setAdapter(dropdownAdapter(unitOptions))

        supplierInput.setText(getString(R.string.product_none), false)
        buyPriceInput.setText("0")
        sellPriceInput.setText("0")
        minStockInput.setText(DEFAULT_MINIMUM_STOCK.toString())
        initialStockInput.setText("0")
        unitInput.setText(DEFAULT_UNIT, false)
        renderPhotos()
    }

    private fun populateForm(item: PosProduct) {
        supplierOptions = listOf<PosSupplier?>(null) +
            (metadata.suppliers + item.displaySuppliers).distinctBy { it.id }
        val unitOptions = (metadata.satuan + item.satuan).filter { it.isNotBlank() }.distinct()
        supplierInput.setAdapter(dropdownAdapter(supplierOptions.map { it?.displayName ?: getString(R.string.product_none) }))
        unitInput.setAdapter(dropdownAdapter(unitOptions))

        skuInput.setText(item.sku)
        nameInput.setText(item.nama)
        brandInput.setText(item.merek.orEmpty())
        val selectedSupplier = item.displaySuppliers.firstOrNull { it.isPrimary }
            ?: item.displaySuppliers.firstOrNull()
        supplierInput.setText(selectedSupplier?.displayName ?: getString(R.string.product_none), false)
        buyPriceInput.setText(item.hargaBeli.toString())
        buyCodeInput.setText(item.hargaBeliKode.orEmpty())
        sellPriceInput.setText(item.hargaJual.toString())
        minStockInput.setText(item.stokMinimum.toString())
        unitInput.setText(item.satuan, false)
        descriptionInput.setText(item.deskripsi.orEmpty())

        existingPhotos.clear()
        existingPhotos += item.displayPhotos.map { EditableProductPhoto.Existing(it) }
        pendingPhotos.clear()
        deletedPhotoIds.clear()
        deleteLegacyPhoto = false
        renderPhotos()
    }

    private fun saveProduct() {
        val accessToken = configStore.getAccessToken() ?: return
        skuLayout.error = null
        nameLayout.error = null
        buyPriceLayout.error = null
        sellPriceLayout.error = null
        minimumStockLayout.error = null
        initialStockLayout.error = null
        val normalizedSku = PosProductRules.normalizeSku(skuInput.text.toString())
        val name = nameInput.text.toString().trim()
        val buyPrice = buyPriceInput.text.toString().toLongOrNull()
        val sellPrice = sellPriceInput.text.toString().toLongOrNull()
        val minimum = minStockInput.text.toString().toIntOrNull()
        val initialStock = if (isCreateMode) initialStockInput.text.toString().toIntOrNull() else 0
        if (!isCreateMode && normalizedSku.isBlank()) skuLayout.error = getString(R.string.product_sku_required)
        if (name.isBlank()) nameLayout.error = getString(R.string.product_name_required)
        if (buyPrice == null || buyPrice < 0) buyPriceLayout.error = getString(R.string.product_price_invalid)
        if (sellPrice == null || sellPrice < 0) sellPriceLayout.error = getString(R.string.product_price_invalid)
        if (minimum == null || minimum < 0) {
            minimumStockLayout.error = getString(R.string.product_min_stock_invalid)
        }
        if (initialStock == null || initialStock < 0) {
            initialStockLayout.error = getString(R.string.product_initial_stock_invalid)
        }
        if ((!isCreateMode && normalizedSku.isBlank()) || name.isBlank() ||
            buyPrice == null || buyPrice < 0 || sellPrice == null || sellPrice < 0 ||
            minimum == null || minimum < 0 || initialStock == null || initialStock < 0
        ) return

        val supplierId = supplierOptions.firstOrNull { it?.displayName == supplierInput.text.toString() }?.id
        val brand = brandInput.text.toString().trim().ifEmpty { null }
        val buyCode = buyCodeInput.text.toString().trim().ifEmpty { null }
        val description = descriptionInput.text.toString().trim().ifEmpty { null }
        val unit = unitInput.text.toString().trim().ifEmpty { DEFAULT_UNIT }
        saveButton.isEnabled = false
        if (isCreateMode) {
            val input = PosProductCreateInput(
                sku = normalizedSku.ifEmpty { null },
                nama = name,
                merek = brand,
                supplierId = supplierId,
                hargaBeli = buyPrice,
                hargaBeliKode = buyCode,
                hargaJual = sellPrice,
                stokMinimum = minimum,
                stokAwal = initialStock,
                satuan = unit,
                deskripsi = description
            )
            viewLifecycleOwner.lifecycleScope.launch {
                when (val create = posApiClient.createProduct(configStore.getBaseUrl(), accessToken, input)) {
                    is PosApiResult.Success -> applyCreatedPhotoChanges(accessToken, create.value)
                    PosApiResult.NotFound -> showSaveFailure(getString(R.string.product_not_found))
                    PosApiResult.SessionExpired -> expireSession()
                    is PosApiResult.Failure -> showSaveFailure(create.message)
                }
            }
            return
        }

        val currentProduct = product ?: return
        val existingProductId = productId ?: return
        val input = PosProductEditInput(
            sku = normalizedSku,
            nama = name,
            merek = brand,
            supplierId = supplierId,
            hargaBeli = buyPrice,
            hargaBeliKode = buyCode,
            hargaJual = sellPrice,
            stokMinimum = minimum,
            satuan = unitInput.text.toString().trim().ifEmpty { currentProduct.satuan },
            deskripsi = description
        )
        viewLifecycleOwner.lifecycleScope.launch {
            when (val update = posApiClient.updateProductById(configStore.getBaseUrl(), accessToken, existingProductId, input)) {
                is PosApiResult.Success -> applyUpdatedPhotoChanges(
                    accessToken,
                    existingProductId,
                    currentProduct,
                    update.value
                )
                PosApiResult.NotFound -> showSaveFailure(getString(R.string.product_not_found))
                PosApiResult.SessionExpired -> expireSession()
                is PosApiResult.Failure -> showSaveFailure(update.message)
            }
        }
    }

    private suspend fun applyUpdatedPhotoChanges(
        accessToken: String,
        existingProductId: Long,
        previous: PosProduct,
        updated: PosProduct
    ) {
        val updatedProduct = updated.copy(
            foto = updated.foto ?: previous.foto,
            fotoUrl = updated.fotoUrl ?: previous.fotoUrl,
            photos = updated.photos.ifEmpty { previous.photos },
            suppliers = updated.suppliers.ifEmpty { previous.suppliers },
            primarySupplier = updated.primarySupplier ?: previous.primarySupplier,
            primarySupplierId = updated.primarySupplierId ?: previous.primarySupplierId
        )
        product = updatedProduct
        val uploads = pendingPhotos.map { it.upload }
        val hasPhotoChanges = uploads.isNotEmpty() || deletedPhotoIds.isNotEmpty() || deleteLegacyPhoto
        val result = if (hasPhotoChanges) {
            posApiClient.updateProductPhotos(
                baseUrl = configStore.getBaseUrl(),
                accessToken = accessToken,
                productId = existingProductId,
                deletePhotoIds = deletedPhotoIds.toList(),
                deleteLegacyPhoto = deleteLegacyPhoto,
                uploads = uploads
            )
        } else {
            PosApiResult.Success(updatedProduct)
        }
        when (result) {
            is PosApiResult.Success -> finishSuccessfully()
            PosApiResult.NotFound -> showSaveFailure(
                getString(R.string.product_photo_update_failed, getString(R.string.product_not_found))
            )
            PosApiResult.SessionExpired -> expireSession()
            is PosApiResult.Failure -> showSaveFailure(
                getString(R.string.product_photo_update_failed, result.message)
            )
        }
    }

    private suspend fun applyCreatedPhotoChanges(accessToken: String, created: PosProduct) {
        val uploads = pendingPhotos.map { it.upload }
        if (uploads.isEmpty()) {
            finishCreateSuccessfully()
            return
        }
        val createdProductId = created.id
        if (createdProductId == null) {
            finishCreateSuccessfully(getString(R.string.product_not_found))
            return
        }
        when (
            val result = posApiClient.updateProductPhotos(
                baseUrl = configStore.getBaseUrl(),
                accessToken = accessToken,
                productId = createdProductId,
                uploads = uploads
            )
        ) {
            is PosApiResult.Success -> finishCreateSuccessfully()
            PosApiResult.NotFound -> finishCreateSuccessfully(getString(R.string.product_not_found))
            PosApiResult.SessionExpired -> {
                configStore.clearSession()
                finishCreateSuccessfully(getString(R.string.pos_session_expired))
            }
            is PosApiResult.Failure -> finishCreateSuccessfully(result.message)
        }
    }

    private fun finishSuccessfully() {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle.EMPTY)
        Toast.makeText(requireContext(), R.string.product_update_success, Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    private fun finishCreateSuccessfully(photoFailure: String? = null) {
        parentFragmentManager.setFragmentResult(CREATE_RESULT_KEY, Bundle.EMPTY)
        val message = photoFailure?.let { getString(R.string.product_create_photo_failed, it) }
            ?: getString(R.string.product_create_success)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        parentFragmentManager.popBackStack()
    }

    private fun readSelectedPhotos(uris: List<Uri>, cameraUri: Uri? = null) {
        photoStatus.setText(R.string.product_photo_batch_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            var rejected = 0
            uris.forEach { uri ->
                when (val result = ProductPhotoFiles.read(requireContext(), uri)) {
                    is ProductPhotoReadResult.Success -> {
                        pendingPhotos += EditableProductPhoto.Pending(
                            localId = ++nextLocalPhotoId,
                            upload = result.upload,
                            preview = result.preview
                        )
                        added += 1
                    }
                    ProductPhotoReadResult.TooLarge,
                    ProductPhotoReadResult.Unsupported,
                    ProductPhotoReadResult.Unreadable -> rejected += 1
                }
            }
            if (cameraUri != null) {
                ProductPhotoCamera.deleteOutput(requireContext(), cameraUri)
                pendingCameraUri = null
            }
            renderPhotos()
            if (rejected > 0) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.product_photo_some_invalid, added, rejected),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun removePhoto(selected: EditableProductPhoto) {
        when (selected) {
            is EditableProductPhoto.Existing -> {
                existingPhotos.removeAll { it.stableKey == selected.stableKey }
                selected.photo.id?.let(deletedPhotoIds::add) ?: run { deleteLegacyPhoto = true }
            }
            is EditableProductPhoto.Pending -> pendingPhotos.removeAll { it.localId == selected.localId }
        }
        renderPhotos()
    }

    private fun renderPhotos() {
        val photos: List<EditableProductPhoto> = existingPhotos + pendingPhotos
        photoAdapter.submitList(photos)
        photoList.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        photoEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        photoStatus.text = if (photos.isEmpty()) {
            getString(R.string.product_photo_batch_help)
        } else {
            resources.getQuantityString(R.plurals.product_photo_edit_count, photos.size, photos.size)
        }
    }

    private fun launchCamera() {
        val uri = runCatching { ProductPhotoCamera.createOutputUri(requireContext()) }
            .getOrElse {
                Toast.makeText(requireContext(), R.string.product_photo_camera_unavailable, Toast.LENGTH_LONG).show()
                return
            }
        pendingCameraUri = uri
        runCatching { photoCamera.launch(uri) }
            .onFailure {
                ProductPhotoCamera.deleteOutput(requireContext(), uri)
                pendingCameraUri = null
                Toast.makeText(requireContext(), R.string.product_photo_camera_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        form.visibility = if (loading) View.GONE else View.VISIBLE
        saveButton.visibility = if (loading) View.GONE else View.VISIBLE
        error.visibility = View.GONE
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        form.visibility = View.GONE
        saveButton.visibility = View.GONE
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun showSaveFailure(message: String) {
        saveButton.isEnabled = true
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun expireSession() {
        configStore.clearSession()
        showError(getString(R.string.pos_session_expired))
    }

    private fun dropdownAdapter(values: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), R.layout.item_label_dropdown, values)

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val RESULT_KEY = "product_edit_saved"
        const val CREATE_RESULT_KEY = "product_create_saved"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val STATE_CAMERA_URI = "product_edit_photo_camera_uri"
        private const val EDIT_PHOTO_SIZE_PX = 512
        private const val DEFAULT_MINIMUM_STOCK = 5
        private const val DEFAULT_UNIT = "pcs"

        fun newInstance(productId: Long) = ProductEditFragment().apply {
            arguments = Bundle().apply { putLong(ARG_PRODUCT_ID, productId) }
        }

        fun newCreateInstance() = ProductEditFragment()
    }
}
