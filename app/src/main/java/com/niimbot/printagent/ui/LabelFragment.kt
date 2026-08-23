package com.niimbot.printagent.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.LogAction
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.label.LabelData
import com.niimbot.printagent.label.LabelField
import com.niimbot.printagent.label.LabelFormInput
import com.niimbot.printagent.label.LabelFormRules
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosConflictChoice
import com.niimbot.printagent.pos.PosSubmissionOutcome
import com.niimbot.printagent.pos.PosSubmissionWorkflow
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LabelFragment : Fragment() {
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var tilSku: TextInputLayout
    private lateinit var tilNama: TextInputLayout
    private lateinit var tilHargaBeli: TextInputLayout
    private lateinit var tilHargaJual: TextInputLayout
    private lateinit var tilQty: TextInputLayout
    private lateinit var tilJumlahBarangMasuk: TextInputLayout
    private lateinit var etSku: EditText
    private lateinit var etNama: EditText
    private lateinit var etHargaBeli: EditText
    private lateinit var etHargaJual: EditText
    private lateinit var etQty: EditText
    private lateinit var etJumlahBarangMasuk: EditText
    private lateinit var ivPreview: ImageView
    private lateinit var switchPos: SwitchMaterial
    private lateinit var btnPreview: View
    private lateinit var btnPrint: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_label, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        etQty.setText("1")
        etJumlahBarangMasuk.setText("0")
        updateIncomingStockState()

        listOf(etSku, etNama, etHargaBeli, etHargaJual, etQty).forEach { editText ->
            editText.doAfterTextChanged { updatePreview(showErrors = false) }
        }
        etJumlahBarangMasuk.doAfterTextChanged {
            tilJumlahBarangMasuk.error = null
        }
        switchPos.setOnCheckedChangeListener { _, _ ->
            updateIncomingStockState()
        }
        btnPreview.setOnClickListener { updatePreview(showErrors = true) }
        btnPrint.setOnClickListener { submit() }
    }

    private fun bindViews(view: View) {
        tilSku = view.findViewById(R.id.til_label_sku)
        tilNama = view.findViewById(R.id.til_label_nama)
        tilHargaBeli = view.findViewById(R.id.til_label_harga_beli)
        tilHargaJual = view.findViewById(R.id.til_label_harga_jual)
        tilQty = view.findViewById(R.id.til_label_qty)
        tilJumlahBarangMasuk = view.findViewById(R.id.til_jumlah_barang_masuk)
        etSku = view.findViewById(R.id.et_label_sku)
        etNama = view.findViewById(R.id.et_label_nama)
        etHargaBeli = view.findViewById(R.id.et_label_harga_beli)
        etHargaJual = view.findViewById(R.id.et_label_harga_jual)
        etQty = view.findViewById(R.id.et_label_qty)
        etJumlahBarangMasuk = view.findViewById(R.id.et_jumlah_barang_masuk)
        ivPreview = view.findViewById(R.id.iv_create_label_preview)
        switchPos = view.findViewById(R.id.switch_add_to_pos)
        btnPreview = view.findViewById(R.id.btn_update_label_preview)
        btnPrint = view.findViewById(R.id.btn_create_and_print)
    }

    private fun currentInput(addToPos: Boolean = switchPos.isChecked) = LabelFormInput(
        sku = etSku.text.toString(),
        nama = etNama.text.toString(),
        hargaBeli = etHargaBeli.text.toString(),
        hargaJual = etHargaJual.text.toString(),
        qty = etQty.text.toString(),
        jumlahBarangMasuk = etJumlahBarangMasuk.text.toString(),
        addToPos = addToPos
    )

    private fun updatePreview(showErrors: Boolean): LabelData? {
        // Stock quantity must never affect label rendering or preview validation.
        val validation = LabelFormRules.validate(currentInput(addToPos = false))
        if (showErrors) showValidationErrors(validation.errors)
        val data = validation.data ?: return null
        ivPreview.setImageBitmap(
            LabelGenerator.generateLabel(
                nama = data.nama,
                hargaJual = data.hargaJual,
                hargaBeli = data.hargaBeli,
                sku = data.sku
            )
        )
        return data
    }

    private fun submit() {
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
                sku = data.sku,
                qty = data.qty
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
        tilJumlahBarangMasuk.error = errors[LabelField.JUMLAH_BARANG_MASUK]
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

    private fun setBusy(busy: Boolean) {
        btnPrint.isEnabled = !busy
        btnPreview.isEnabled = !busy
        switchPos.isEnabled = !busy
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
}
