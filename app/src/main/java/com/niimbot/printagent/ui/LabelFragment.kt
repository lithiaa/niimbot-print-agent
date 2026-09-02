package com.niimbot.printagent.ui

import android.content.Intent
import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.niimbot.printagent.R
import com.niimbot.printagent.NiimbotPrintApplication
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.ble.NiimbotLabelMetadataClient
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.label.LabelData
import com.niimbot.printagent.label.LabelDate
import com.niimbot.printagent.label.LabelField
import com.niimbot.printagent.label.LabelFormInput
import com.niimbot.printagent.label.LabelFormRules
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.label.LabelLayout
import com.niimbot.printagent.label.LabelSkuGenerator
import com.niimbot.printagent.label.LabelSize
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosConflictChoice
import com.niimbot.printagent.pos.PosSubmissionOutcome
import com.niimbot.printagent.pos.PosSubmissionWorkflow
import com.niimbot.printagent.pos.PosProductRules
import com.niimbot.printagent.pos.PosProduct
import com.niimbot.printagent.pos.PosSupplier
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@AndroidEntryPoint
class LabelFragment : Fragment() {
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient
    @Inject lateinit var labelMetadataClient: NiimbotLabelMetadataClient

    private lateinit var tilSku: TextInputLayout
    private lateinit var tilNama: TextInputLayout
    private lateinit var tilHargaBeli: TextInputLayout
    private lateinit var tilHargaJual: TextInputLayout
    private lateinit var tilQty: TextInputLayout
    private lateinit var tilItemQty: TextInputLayout
    private lateinit var tilSupplier: TextInputLayout
    private lateinit var tilTanggalMasuk: TextInputLayout
    private lateinit var tilJumlahBarangMasuk: TextInputLayout
    private lateinit var etSku: EditText
    private lateinit var etNama: AutoCompleteTextView
    private lateinit var etKodeHargaBeli: EditText
    private lateinit var etHargaBeli: EditText
    private lateinit var etHargaJual: EditText
    private lateinit var etQty: EditText
    private lateinit var etItemQty: EditText
    private lateinit var etTanggalMasuk: EditText
    private lateinit var dropdownSupplier: AutoCompleteTextView
    private lateinit var etJumlahBarangMasuk: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var previewContainer: FrameLayout
    private lateinit var labelScrollView: NestedScrollView
    private lateinit var switchPos: SwitchMaterial
    private lateinit var dropdownLabelSize: AutoCompleteTextView
    private lateinit var dropdownLabelLayout: AutoCompleteTextView
    private lateinit var btnPreview: View
    private lateinit var btnPrint: View
    private lateinit var btnScanSku: View
    private lateinit var btnResetForm: View
    private lateinit var contentContainer: LinearLayout
    private lateinit var formCard: MaterialCardView
    private lateinit var previewCard: MaterialCardView
    private lateinit var labelOptionsRow: LinearLayout
    private lateinit var qtySupplierRow: LinearLayout
    private var productSearchJob: Job? = null
    private var productSuggestions: List<PosProduct> = emptyList()
    private var supplierSuggestions: List<PosSupplier> = emptyList()
    private var selectedSupplierCode: String? = null
    private var applyingProductSuggestion = false
    private val availableLabelSizes = LabelSize.entries.toMutableList()
    private var metadataConsentPromptShown = false
    private var isTabletLayout = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_label, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureResponsiveLayout()
        setupLabelOptions()
        setupSupplierDropdown()
        restoreDraft()
        setupProductAutocomplete()
        updateIncomingStockState()

        listOf(etSku, etNama, etKodeHargaBeli, etHargaBeli, etHargaJual, etQty, etItemQty, etTanggalMasuk).forEach { editText ->
            editText.doAfterTextChanged {
                saveDraft()
                updatePreview(showErrors = false)
            }
        }
        etNama.doAfterTextChanged { text ->
            if (!applyingProductSuggestion) scheduleProductSearch(text?.toString().orEmpty())
        }
        etJumlahBarangMasuk.doAfterTextChanged {
            tilJumlahBarangMasuk.error = null
            saveDraft()
        }
        dropdownLabelSize.setOnItemClickListener { _, _, _, _ ->
            saveDraft()
            updatePreview(showErrors = false)
        }
        dropdownLabelLayout.setOnItemClickListener { _, _, _, _ ->
            saveDraft()
            updatePreview(showErrors = false)
        }
        dropdownSupplier.setOnItemClickListener { parent, _, position, _ ->
            val selectedLabel = parent.getItemAtPosition(position)?.toString()
            selectedSupplierCode = supplierSuggestions
                .firstOrNull { supplierSuggestionLabel(it) == selectedLabel }
                ?.codeForLabel
            saveDraft()
            updatePreview(showErrors = false)
        }
        switchPos.setOnCheckedChangeListener { _, _ ->
            updateIncomingStockState()
            saveDraft()
        }
        btnScanSku.setOnClickListener { scanSku() }
        etTanggalMasuk.setOnClickListener { showEntryDatePicker() }
        tilTanggalMasuk.setEndIconOnClickListener { showEntryDatePicker() }
        btnResetForm.setOnClickListener { confirmResetForm() }
        btnPreview.setOnClickListener { updatePreview(showErrors = true) }
        btnPrint.setOnClickListener { submit() }
        observePrinterConnection()
        updatePreview(showErrors = false)
    }

    private fun bindViews(view: View) {
        tilSku = view.findViewById(R.id.til_label_sku)
        tilNama = view.findViewById(R.id.til_label_nama)
        tilHargaBeli = view.findViewById(R.id.til_label_harga_beli)
        tilHargaJual = view.findViewById(R.id.til_label_harga_jual)
        tilQty = view.findViewById(R.id.til_label_qty)
        tilItemQty = view.findViewById(R.id.til_label_item_qty)
        tilSupplier = view.findViewById(R.id.til_label_supplier)
        tilTanggalMasuk = view.findViewById(R.id.til_label_tanggal_masuk)
        tilJumlahBarangMasuk = view.findViewById(R.id.til_jumlah_barang_masuk)
        etSku = view.findViewById(R.id.et_label_sku)
        etNama = view.findViewById(R.id.et_label_nama)
        etKodeHargaBeli = view.findViewById(R.id.et_label_kode_harga_beli)
        etHargaBeli = view.findViewById(R.id.et_label_harga_beli)
        etHargaJual = view.findViewById(R.id.et_label_harga_jual)
        etQty = view.findViewById(R.id.et_label_qty)
        etItemQty = view.findViewById(R.id.et_label_item_qty)
        etTanggalMasuk = view.findViewById(R.id.et_label_tanggal_masuk)
        dropdownSupplier = view.findViewById(R.id.dropdown_label_supplier)
        etJumlahBarangMasuk = view.findViewById(R.id.et_jumlah_barang_masuk)
        ivPreview = view.findViewById(R.id.iv_create_label_preview)
        previewContainer = view.findViewById(R.id.preview_label_container)
        labelScrollView = view.findViewById(R.id.label_scroll_view)
        switchPos = view.findViewById(R.id.switch_add_to_pos)
        dropdownLabelSize = view.findViewById(R.id.dropdown_label_size)
        dropdownLabelLayout = view.findViewById(R.id.dropdown_label_layout)
        btnPreview = view.findViewById(R.id.btn_update_label_preview)
        btnPrint = view.findViewById(R.id.btn_create_and_print)
        btnScanSku = view.findViewById(R.id.btn_scan_label_sku)
        btnResetForm = view.findViewById(R.id.btn_reset_label_form)
        contentContainer = view.findViewById(R.id.label_content_container)
        formCard = view.findViewById(R.id.card_label_form)
        previewCard = view.findViewById(R.id.card_label_preview)
        labelOptionsRow = view.findViewById(R.id.row_label_options)
        qtySupplierRow = view.findViewById(R.id.row_label_qty_supplier)
    }

    private fun observePrinterConnection() {
        niimbotManager().connectionStateLive.observe(viewLifecycleOwner) { state ->
            if (state == NiimbotBluetoothManager.STATE_CONNECTED) {
                refreshDetectedLabelSize()
            }
        }
    }

    private fun refreshDetectedLabelSize() {
        val manager = niimbotManager()
        if (manager.connectionStateLive.value != NiimbotBluetoothManager.STATE_CONNECTED) {
            return
        }
        manager.readLabelRollIdentity { roll, _ ->
            view?.post {
                if (!isAdded) return@post
                if (roll != null) resolveAndApplyLabelSize(roll.barcode)
            }
        }
    }

    private fun resolveAndApplyLabelSize(barcode: String) {
        val preferences = requireContext().getSharedPreferences(METADATA_PREFERENCES, 0)
        if (!preferences.getBoolean(NIIMBOT_METADATA_CONSENT, false)) {
            if (!metadataConsentPromptShown) {
                metadataConsentPromptShown = true
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.label_size_detection_consent_title)
                    .setMessage(R.string.label_size_detection_consent_message)
                    .setNegativeButton(R.string.not_now, null)
                    .setPositiveButton(R.string.allow_detection) { _, _ ->
                        preferences.edit().putBoolean(NIIMBOT_METADATA_CONSENT, true).apply()
                        fetchAndApplyLabelSize(barcode)
                    }
                    .show()
            }
            return
        }
        fetchAndApplyLabelSize(barcode)
    }

    private fun fetchAndApplyLabelSize(barcode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val detected = labelMetadataClient.getLabelSize(barcode)
            if (!isAdded || detected == null) return@launch
            val size = LabelSize.detected(detected.widthMm, detected.heightMm)
            if (size !in availableLabelSizes) availableLabelSizes += size
            updateLabelSizeAdapter()
            dropdownLabelSize.setText(size.displayName, false)
            saveDraft()
            updatePreview(showErrors = false)
        }
    }

    private fun niimbotManager(): NiimbotBluetoothManager =
        (requireActivity().applicationContext as NiimbotPrintApplication).getNiimbotManager()

    private fun confirmResetForm() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_label_form_title)
            .setMessage(R.string.reset_label_form_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset_label_form) { _, _ -> resetForm() }
            .show()
    }

    private fun resetForm() {
        requireContext().getSharedPreferences(DRAFT_PREFERENCES, 0).edit().clear().apply()
        etSku.text?.clear()
        etNama.setText("", false)
        etKodeHargaBeli.text?.clear()
        etHargaBeli.text?.clear()
        etHargaJual.text?.clear()
        etQty.setText("1")
        etItemQty.setText("1")
        etTanggalMasuk.setText(LabelDate.todayIso())
        dropdownSupplier.setText("", false)
        selectedSupplierCode = null
        etJumlahBarangMasuk.setText("0")
        switchPos.isChecked = false
        dropdownLabelSize.setText(LabelSize.MM_50_X_30.displayName, false)
        dropdownLabelLayout.setText(LabelLayout.STANDARD.displayName, false)
        showValidationErrors(emptyMap())
        saveDraft()
        updatePreview(showErrors = false)
        Toast.makeText(requireContext(), R.string.reset_label_form_done, Toast.LENGTH_SHORT).show()
    }

    private fun configureResponsiveLayout() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        isTabletLayout = isTablet
        val gap = (16 * resources.displayMetrics.density).toInt()
        configureFormRow(labelOptionsRow, isTablet, gap)
        configureFormRow(qtySupplierRow, isTablet, gap)
        contentContainer.orientation = if (isTablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        if (isTablet) {
            if (contentContainer.indexOfChild(formCard) > contentContainer.indexOfChild(previewCard)) {
                contentContainer.removeView(formCard)
                contentContainer.addView(formCard, 0)
            }
            formCard.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.64f).apply {
                marginEnd = gap / 2
            }
            previewCard.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f).apply {
                marginStart = gap / 2
            }
        } else {
            if (contentContainer.indexOfChild(previewCard) > contentContainer.indexOfChild(formCard)) {
                contentContainer.removeView(previewCard)
                contentContainer.addView(previewCard, 0)
            }
            previewCard.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            formCard.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            btnPreview.visibility = View.GONE
            previewContainer.layoutParams = previewContainer.layoutParams.apply {
                height = dp(150)
            }
            previewCard.elevation = dp(10).toFloat()
            labelScrollView.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    val stickyStart = contentContainer.top + previewCard.top
                    previewCard.translationY = (scrollY - stickyStart).coerceAtLeast(0).toFloat()
                }
            )
        }
    }

    private fun configureFormRow(row: LinearLayout, horizontal: Boolean, gap: Int) {
        row.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            child.layoutParams = LinearLayout.LayoutParams(
                if (horizontal) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (horizontal) 1f else 0f
            ).apply {
                if (horizontal) {
                    marginStart = if (index == 0) 0 else gap / 2
                    marginEnd = if (index == row.childCount - 1) 0 else gap / 2
                } else if (index > 0) {
                    topMargin = gap / 2
                }
            }
        }
    }

    private fun showEntryDatePicker() {
        val calendar = Calendar.getInstance()
        val current = etTanggalMasuk.text.toString().trim()
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(current)
        }.getOrNull()?.let(calendar::setTime)
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                etTanggalMasuk.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
                tilTanggalMasuk.error = null
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setupProductAutocomplete() {
        etNama.threshold = 2
        updateProductSearchHelper()
        etNama.setOnItemClickListener { parent, _, position, _ ->
            val selectedLabel = parent.getItemAtPosition(position)?.toString()
            productSuggestions.firstOrNull { productSuggestionLabel(it) == selectedLabel }
                ?.let(::applyProductSuggestion)
        }
    }

    private fun setupSupplierDropdown() {
        dropdownSupplier.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_label_dropdown, emptyList<String>())
        )
        loadSuppliers()
    }

    private fun loadSuppliers() {
        val integrationKey = configStore.getIntegrationKey()
        if (integrationKey.isNullOrBlank()) {
            tilSupplier.helperText = getString(R.string.label_supplier_key_required)
            return
        }
        tilSupplier.helperText = getString(R.string.label_supplier_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = posApiClient.listSuppliers(configStore.getBaseUrl(), integrationKey)) {
                is PosApiResult.Success -> {
                    supplierSuggestions = result.value.sortedBy { it.displayName.lowercase(Locale("id", "ID")) }
                    dropdownSupplier.setAdapter(
                        ArrayAdapter(
                            requireContext(),
                            R.layout.item_label_dropdown,
                            supplierSuggestions.map(::supplierSuggestionLabel)
                        )
                    )
                    val selectedText = dropdownSupplier.text.toString()
                    supplierSuggestions.firstOrNull { supplierSuggestionLabel(it) == selectedText }
                        ?.let { selectedSupplierCode = it.codeForLabel }
                    tilSupplier.helperText = getString(R.string.label_supplier_hint)
                }
                PosApiResult.NotFound -> {
                    supplierSuggestions = emptyList()
                    tilSupplier.helperText = getString(R.string.label_supplier_hint)
                }
                is PosApiResult.Failure -> {
                    supplierSuggestions = emptyList()
                    tilSupplier.helperText = getString(R.string.label_supplier_load_failed, result.message)
                }
            }
        }
    }

    private fun supplierSuggestionLabel(supplier: PosSupplier): String =
        if (supplier.codeForLabel == supplier.displayName) {
            supplier.displayName
        } else {
            "${supplier.displayName} - ${supplier.codeForLabel}"
        }

    private fun scheduleProductSearch(query: String) {
        productSearchJob?.cancel()
        if (query.trim().length < 2) {
            updateProductSuggestions(emptyList())
            return
        }
        val key = configStore.getIntegrationKey()
        if (key.isNullOrBlank()) {
            Log.w(TAG, "Product search skipped: integration key is not configured")
            updateProductSearchHelper()
            updateProductSuggestions(emptyList())
            return
        }
        productSearchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            when (val result = posApiClient.searchProducts(configStore.getBaseUrl(), key, query)) {
                is PosApiResult.Success -> {
                    Log.d(TAG, "Product search returned ${result.value.size} result(s)")
                    tilNama.helperText = getString(R.string.label_product_search_hint)
                    updateProductSuggestions(result.value)
                }
                PosApiResult.NotFound -> updateProductSuggestions(emptyList())
                is PosApiResult.Failure -> {
                    Log.w(TAG, "Product search failed: ${result.message}")
                    tilNama.helperText = getString(R.string.label_product_search_failed)
                    updateProductSuggestions(emptyList())
                }
            }
        }
    }

    private fun updateProductSuggestions(products: List<PosProduct>) {
        productSuggestions = products
        val labels = products.map(::productSuggestionLabel)
        etNama.setAdapter(ArrayAdapter(requireContext(), R.layout.item_label_dropdown, labels))
        if (labels.isNotEmpty() && etNama.hasFocus()) etNama.showDropDown()
    }

    private fun productSuggestionLabel(product: PosProduct): String =
        "${product.nama} - ${product.sku}"

    private fun updateProductSearchHelper() {
        tilNama.helperText = getString(
            if (configStore.getIntegrationKey().isNullOrBlank()) {
                R.string.label_product_search_key_required
            } else {
                R.string.label_product_search_hint
            }
        )
    }

    private fun applyProductSuggestion(product: PosProduct) {
        applyingProductSuggestion = true
        etSku.setText(PosProductRules.normalizeSku(product.sku))
        etNama.setText(product.nama, false)
        etHargaBeli.setText(product.hargaBeli.toString())
        etHargaJual.setText(product.hargaJual.toString())
        etKodeHargaBeli.setText(product.hargaBeliKode.orEmpty())
        etTanggalMasuk.setText(LabelDate.fromTimestamp(product.createdAt) ?: LabelDate.todayIso())
        applyingProductSuggestion = false
        clearResolvedValidationErrors(emptyMap())
        updatePreview(showErrors = false)
    }

    override fun onPause() {
        saveDraft()
        super.onPause()
    }

    private fun scanSku() {
        GmsBarcodeScanning.getClient(requireContext())
            .startScan()
            .addOnSuccessListener { barcode ->
                val scannedSku = barcode.rawValue?.let(PosProductRules::normalizeSku).orEmpty()
                if (scannedSku.isNotEmpty()) {
                    etSku.setText(scannedSku)
                    etSku.setSelection(scannedSku.length)
                    tilSku.error = null
                }
            }
            .addOnCanceledListener { /* The user intentionally closed the scanner. */ }
            .addOnFailureListener {
                showError(getString(R.string.label_scan_failed))
            }
    }

    private fun ensureSku(): String {
        val existing = PosProductRules.normalizeSku(etSku.text.toString())
        if (existing.isNotEmpty()) return existing
        val productName = etNama.text.toString().trim()
        if (productName.isEmpty()) return ""
        val generated = LabelSkuGenerator.generate(productName)
        etSku.setText(generated)
        Toast.makeText(
            requireContext(),
            getString(R.string.label_sku_generated, generated),
            Toast.LENGTH_SHORT
        ).show()
        return generated
    }

    private fun currentInput(addToPos: Boolean = switchPos.isChecked) = LabelFormInput(
        sku = etSku.text.toString(),
        nama = etNama.text.toString(),
        hargaBeli = etHargaBeli.text.toString(),
        hargaJual = etHargaJual.text.toString(),
        qty = etQty.text.toString(),
        jumlahBarangMasuk = etJumlahBarangMasuk.text.toString(),
        addToPos = addToPos,
        labelSize = selectedLabelSize(),
        labelLayout = selectedLabelLayout(),
        kodeHargaBeli = etKodeHargaBeli.text.toString(),
        itemQty = etItemQty.text.toString(),
        supplierCode = selectedSupplierCode.orEmpty(),
        tanggalMasuk = etTanggalMasuk.text.toString()
    )

    private fun updatePreview(showErrors: Boolean): LabelData? {
        // Stock quantity must never affect label rendering or preview validation.
        val validation = LabelFormRules.validate(currentInput(addToPos = false))
        if (showErrors) {
            showValidationErrors(validation.errors)
        } else {
            clearResolvedValidationErrors(validation.errors)
        }
        val data = validation.data
        val previewNama = etNama.text.toString().trim().ifEmpty { getString(R.string.label_preview_name_placeholder) }
        val previewSku = PosProductRules.normalizeSku(etSku.text.toString()).ifEmpty {
            if (etNama.text.toString().isBlank()) "000000" else LabelSkuGenerator.generate(previewNama, "AUTO")
        }
        val previewHargaBeli = etHargaBeli.text.toString().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val previewHargaJual = etHargaJual.text.toString().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val bitmap = LabelGenerator.generateLabel(
                nama = previewNama,
                hargaJual = previewHargaJual,
                hargaBeli = previewHargaBeli,
                sku = previewSku,
                labelSize = selectedLabelSize(),
                labelLayout = selectedLabelLayout(),
                kodeHargaBeli = etKodeHargaBeli.text.toString(),
                itemQty = etItemQty.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                supplierCode = selectedSupplierCode,
                tanggalMasuk = etTanggalMasuk.text.toString()
            )
        ivPreview.setImageBitmap(bitmap)
        updatePreviewDimensions(selectedLabelSize())
        return data
    }

    private fun submit() {
        ensureSku()
        val validation = LabelFormRules.validate(currentInput())
        showValidationErrors(validation.errors)
        val form = validation.data ?: return
        updatePreview(showErrors = false)
        val operationId = UUID.randomUUID().toString()

        if (!switchPos.isChecked) {
            enqueue(form)
            return
        }

        val key = configStore.getIntegrationKey()
        if (key.isNullOrBlank()) {
            showError(getString(R.string.pos_key_required))
            return
        }
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = PosSubmissionWorkflow(posApiClient).submit(
                configStore.getBaseUrl(),
                key,
                form,
                operationId
            )
            handlePosOutcome(result)
        }
    }

    private fun handlePosOutcome(result: PosSubmissionOutcome) {
        when (result) {
            is PosSubmissionOutcome.ReadyToQueue -> enqueue(
                result.labelData,
                result.stockAdded,
                result.currentStock
            )
            is PosSubmissionOutcome.Conflict -> {
                setBusy(false)
                showConflictDialog(result)
            }
            is PosSubmissionOutcome.Failure -> {
                setBusy(false)
                showError(result.message)
            }
            PosSubmissionOutcome.Cancelled -> setBusy(false)
        }
    }

    private fun resolveConflict(
        conflict: PosSubmissionOutcome.Conflict,
        choice: PosConflictChoice
    ) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = PosSubmissionWorkflow(posApiClient).resolveConflict(conflict, choice)
            handlePosOutcome(result)
        }
    }

    private fun showConflictDialog(conflict: PosSubmissionOutcome.Conflict) {
        val form = conflict.form
        val product = conflict.product
        val currency = NumberFormat.getNumberInstance(Locale("id", "ID"))
        val comparison = getString(
            R.string.pos_conflict_comparison,
            product.sku,
            product.nama,
            currency.format(product.hargaBeli),
            currency.format(product.hargaJual),
            form.sku,
            form.nama,
            currency.format(form.hargaBeli),
            currency.format(form.hargaJual)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pos_conflict_title)
            .setMessage(comparison)
            .setNegativeButton(R.string.use_pos_and_print) { _, _ ->
                resolveConflict(conflict, PosConflictChoice.USE_POS)
            }
            .setPositiveButton(R.string.update_pos_and_print) { _, _ ->
                resolveConflict(conflict, PosConflictChoice.UPDATE_POS)
            }
            .setNeutralButton(android.R.string.cancel) { _, _ ->
                handlePosOutcome(PosSubmissionOutcome.Cancelled)
            }
            .show()
    }

    private fun enqueue(
        data: LabelData,
        stockAdded: Int? = null,
        currentStock: Int? = null
    ) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val job = PrintJob(
                nama = data.nama,
                hargaJual = data.hargaJual,
                hargaBeli = data.hargaBeli,
                kodeHargaBeli = data.kodeHargaBeli,
                sku = data.sku,
                qty = data.qty,
                labelSize = data.labelSize.name,
                labelLayout = data.labelLayout.name,
                itemQty = data.itemQty,
                supplierCode = data.supplierCode,
                tanggalMasuk = data.tanggalMasuk
            )
            val jobId = database.printJobDao().insert(job)
            if (jobId <= 0) {
                setBusy(false)
                showError(getString(R.string.queue_failed))
                return@launch
            }
            database.printLogDao().insert(PrintLog(printJobId = jobId, action = LogAction.QUEUED))
            val intent = Intent(requireContext(), PrintForegroundService::class.java).apply {
                action = PrintForegroundService.ACTION_ENQUEUE
                putExtra(PrintForegroundService.EXTRA_JOB_ID, jobId)
            }
            ContextCompat.startForegroundService(requireContext(), intent)
            setBusy(false)
            val message = if (stockAdded != null && currentStock != null) {
                getString(
                    R.string.label_queued_with_stock,
                    data.qty,
                    stockAdded,
                    currentStock
                )
            } else {
                getString(R.string.label_queued, data.qty)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showValidationErrors(errors: Map<LabelField, String>) {
        tilSku.error = errors[LabelField.SKU]
        tilNama.error = errors[LabelField.NAMA]
        tilHargaBeli.error = errors[LabelField.HARGA_BELI]
        tilHargaJual.error = errors[LabelField.HARGA_JUAL]
        tilQty.error = errors[LabelField.QTY]
        tilItemQty.error = errors[LabelField.ITEM_QTY]
        tilJumlahBarangMasuk.error = errors[LabelField.JUMLAH_BARANG_MASUK]
        tilTanggalMasuk.error = errors[LabelField.TANGGAL_MASUK]
    }

    private fun clearResolvedValidationErrors(errors: Map<LabelField, String>) {
        if (LabelField.SKU !in errors) tilSku.error = null
        if (LabelField.NAMA !in errors) tilNama.error = null
        if (LabelField.HARGA_BELI !in errors) tilHargaBeli.error = null
        if (LabelField.HARGA_JUAL !in errors) tilHargaJual.error = null
        if (LabelField.QTY !in errors) tilQty.error = null
        if (LabelField.ITEM_QTY !in errors) tilItemQty.error = null
        if (LabelField.JUMLAH_BARANG_MASUK !in errors) tilJumlahBarangMasuk.error = null
        if (LabelField.TANGGAL_MASUK !in errors) tilTanggalMasuk.error = null
    }

    private fun updateIncomingStockState() {
        tilJumlahBarangMasuk.visibility = View.VISIBLE
        tilJumlahBarangMasuk.isEnabled = switchPos.isChecked
        etJumlahBarangMasuk.isEnabled = switchPos.isChecked
        if (!switchPos.isChecked && etJumlahBarangMasuk.text.toString() != "0") {
            etJumlahBarangMasuk.setText("0")
        }
        if (!switchPos.isChecked) tilJumlahBarangMasuk.error = null
    }

    private fun setupLabelOptions() {
        updateLabelSizeAdapter()
        dropdownLabelLayout.setAdapter(ArrayAdapter(requireContext(), R.layout.item_label_dropdown, LabelLayout.entries.map { it.displayName }))
        dropdownLabelSize.setText(LabelSize.MM_50_X_30.displayName, false)
        dropdownLabelLayout.setText(LabelLayout.STANDARD.displayName, false)
    }

    private fun updateLabelSizeAdapter() {
        dropdownLabelSize.setAdapter(
            ArrayAdapter(
                requireContext(),
                R.layout.item_label_dropdown,
                availableLabelSizes.map { it.displayName }
            )
        )
    }

    private fun restoreDraft() {
        val draft = requireContext().getSharedPreferences(DRAFT_PREFERENCES, 0)
        etSku.setText(draft.getString(DRAFT_SKU, "").orEmpty())
        etNama.setText(draft.getString(DRAFT_NAMA, "").orEmpty(), false)
        etKodeHargaBeli.setText(draft.getString(DRAFT_KODE_HARGA_BELI, "").orEmpty())
        etHargaBeli.setText(draft.getString(DRAFT_HARGA_BELI, "").orEmpty())
        etHargaJual.setText(draft.getString(DRAFT_HARGA_JUAL, "").orEmpty())
        etQty.setText(draft.getString(DRAFT_QTY, "1").orEmpty().ifBlank { "1" })
        etItemQty.setText(draft.getString(DRAFT_ITEM_QTY, "1").orEmpty().ifBlank { "1" })
        etTanggalMasuk.setText(
            draft.getString(DRAFT_TANGGAL_MASUK, LabelDate.todayIso())
                .orEmpty()
                .takeIf(LabelDate::isValid)
                ?: LabelDate.todayIso()
        )
        dropdownSupplier.setText(draft.getString(DRAFT_SUPPLIER_DISPLAY, "").orEmpty(), false)
        selectedSupplierCode = draft.getString(DRAFT_SUPPLIER_CODE, null)
        etJumlahBarangMasuk.setText(
            draft.getString(DRAFT_JUMLAH_BARANG_MASUK, "0").orEmpty().ifBlank { "0" }
        )
        switchPos.isChecked = draft.getBoolean(DRAFT_ADD_TO_POS, false)
        val size = draft.getString(DRAFT_LABEL_SIZE, null)?.let(LabelSize::fromName)
            ?: LabelSize.MM_50_X_30
        if (size !in availableLabelSizes) {
            availableLabelSizes += size
            updateLabelSizeAdapter()
        }
        val layout = LabelLayout.entries.firstOrNull { it.name == draft.getString(DRAFT_LABEL_LAYOUT, null) }
            ?: LabelLayout.STANDARD
        dropdownLabelSize.setText(size.displayName, false)
        dropdownLabelLayout.setText(layout.displayName, false)
    }

    private fun saveDraft() {
        if (!this::etSku.isInitialized) return
        requireContext().getSharedPreferences(DRAFT_PREFERENCES, 0).edit()
            .putString(DRAFT_SKU, etSku.text.toString())
            .putString(DRAFT_NAMA, etNama.text.toString())
            .putString(DRAFT_KODE_HARGA_BELI, etKodeHargaBeli.text.toString())
            .putString(DRAFT_HARGA_BELI, etHargaBeli.text.toString())
            .putString(DRAFT_HARGA_JUAL, etHargaJual.text.toString())
            .putString(DRAFT_QTY, etQty.text.toString())
            .putString(DRAFT_ITEM_QTY, etItemQty.text.toString())
            .putString(DRAFT_TANGGAL_MASUK, etTanggalMasuk.text.toString())
            .putString(DRAFT_SUPPLIER_DISPLAY, dropdownSupplier.text.toString())
            .putString(DRAFT_SUPPLIER_CODE, selectedSupplierCode)
            .putString(DRAFT_JUMLAH_BARANG_MASUK, etJumlahBarangMasuk.text.toString())
            .putBoolean(DRAFT_ADD_TO_POS, switchPos.isChecked)
            .putString(DRAFT_LABEL_SIZE, selectedLabelSize().name)
            .putString(DRAFT_LABEL_LAYOUT, selectedLabelLayout().name)
            .apply()
    }

    private fun selectedLabelSize(): LabelSize = availableLabelSizes.firstOrNull {
        it.displayName == dropdownLabelSize.text.toString()
    } ?: LabelSize.MM_50_X_30

    private fun selectedLabelLayout(): LabelLayout = LabelLayout.entries.firstOrNull {
        it.displayName == dropdownLabelLayout.text.toString()
    } ?: LabelLayout.STANDARD

    private fun updatePreviewDimensions(size: LabelSize) {
        previewContainer.post { applyPreviewDimensions(size) }
    }

    private fun applyPreviewDimensions(size: LabelSize) {
        val density = resources.displayMetrics.density
        val maxWidthDp = if (isTabletLayout) 520 else 300
        val maxHeightDp = if (isTabletLayout) 240 else 118
        val containerWidth = (previewContainer.width - previewContainer.paddingLeft -
            previewContainer.paddingRight).coerceAtLeast(1)
        val maxWidth = (maxWidthDp * density).toInt()
            .coerceAtMost(containerWidth)
        val maxHeight = (maxHeightDp * density).toInt()
        var width = maxWidth
        var height = (width * size.heightMm / size.widthMm.toFloat()).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * size.widthMm / size.heightMm.toFloat()).toInt()
        }
        ivPreview.layoutParams = FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setBusy(busy: Boolean) {
        btnPrint.isEnabled = !busy
        btnPreview.isEnabled = !busy
        btnScanSku.isEnabled = !busy
        btnResetForm.isEnabled = !busy
        switchPos.isEnabled = !busy
        tilItemQty.isEnabled = !busy
        tilSupplier.isEnabled = !busy
        dropdownSupplier.isEnabled = !busy
        tilJumlahBarangMasuk.isEnabled = !busy && switchPos.isChecked
        etJumlahBarangMasuk.isEnabled = !busy && switchPos.isChecked
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cannot_print_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val TAG = "LabelFragment"
        const val DRAFT_PREFERENCES = "label_draft"
        const val METADATA_PREFERENCES = "label_metadata_preferences"
        const val DRAFT_SKU = "sku"
        const val DRAFT_NAMA = "nama"
        const val DRAFT_KODE_HARGA_BELI = "kode_harga_beli"
        const val DRAFT_HARGA_BELI = "harga_beli"
        const val DRAFT_HARGA_JUAL = "harga_jual"
        const val DRAFT_QTY = "qty"
        const val DRAFT_ITEM_QTY = "item_qty"
        const val DRAFT_TANGGAL_MASUK = "tanggal_masuk"
        const val DRAFT_SUPPLIER_DISPLAY = "supplier_display"
        const val DRAFT_SUPPLIER_CODE = "supplier_code"
        const val DRAFT_JUMLAH_BARANG_MASUK = "jumlah_barang_masuk"
        const val DRAFT_ADD_TO_POS = "add_to_pos"
        const val DRAFT_LABEL_SIZE = "label_size"
        const val DRAFT_LABEL_LAYOUT = "label_layout"
        const val NIIMBOT_METADATA_CONSENT = "niimbot_metadata_consent"
    }
}
