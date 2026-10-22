package com.niimbot.printagent.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.XPrinterBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.data.PrinterConfig
import com.niimbot.printagent.label.BuyerNoticeLabelGenerator
import com.niimbot.printagent.label.ShippingLabelPdf
import com.niimbot.printagent.label.ShippingLabelPdfDocument
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@AndroidEntryPoint
class PrinterFragment : Fragment() {
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var xPrinterManager: XPrinterBluetoothManager

    private var tvPrinterName: TextView? = null
    private var tvPrinterMac: TextView? = null
    private var tvPrinterModel: TextView? = null
    private var tvConnectionStatus: TextView? = null
    private var tvBattery: TextView? = null
    private var btnScan: Button? = null
    private var btnTestPrint: Button? = null
    private var btnBuyerNotice: Button? = null
    private var btnPrintPdf: Button? = null
    private var progressBar: ProgressBar? = null
    private var rvDevices: RecyclerView? = null
    private var tvDiscoveredLabel: TextView? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var btnForgetPrinter: Button? = null
    private var rvPrintQueue: RecyclerView? = null
    private var tvPrintQueueEmpty: TextView? = null
    private var printQueueAdapter: JobAdapter? = null
    private lateinit var printerTypeDropdown: AutoCompleteTextView
    private var selectedChoice = PrinterChoice.XPRINTER_203

    private val openPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::preparePdfPreview)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_printer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupPrinterTypeDropdown()
        setupDeviceList()
        observePrinterConfig()
        observeConnectionState()
        setupClickListeners()
        observePrintQueue()
    }

    private fun bindViews(view: View) {
        tvPrinterName = view.findViewById(R.id.tv_printer_name)
        tvPrinterMac = view.findViewById(R.id.tv_printer_mac)
        tvPrinterModel = view.findViewById(R.id.tv_printer_model)
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status)
        tvBattery = view.findViewById(R.id.tv_battery)
        btnScan = view.findViewById(R.id.btn_scan)
        btnTestPrint = view.findViewById(R.id.btn_test_print)
        btnBuyerNotice = view.findViewById(R.id.btn_buyer_notice)
        btnPrintPdf = view.findViewById(R.id.btn_print_pdf)
        progressBar = view.findViewById(R.id.progress_scan)
        rvDevices = view.findViewById(R.id.rv_devices)
        tvDiscoveredLabel = view.findViewById(R.id.tv_discovered_label)
        btnForgetPrinter = view.findViewById(R.id.btn_forget_printer)
        rvPrintQueue = view.findViewById(R.id.rv_printer_queue)
        tvPrintQueueEmpty = view.findViewById(R.id.tv_printer_queue_empty)
        printerTypeDropdown = view.findViewById(R.id.dropdown_printer_type)
    }

    private fun setupPrinterTypeDropdown() {
        printerTypeDropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_label_dropdown, PrinterChoice.entries.map { it.label })
        )
        printerTypeDropdown.setText(selectedChoice.label, false)
        btnScan?.setText(R.string.load_paired_xprinters)
        printerTypeDropdown.setOnClickListener { printerTypeDropdown.showDropDown() }
        printerTypeDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString()
            selectedChoice = PrinterChoice.entries.firstOrNull { it.label == label } ?: PrinterChoice.XPRINTER_203
            deviceAdapter?.submitList(emptyList())
            rvDevices?.visibility = View.GONE
            tvDiscoveredLabel?.visibility = View.GONE
            btnScan?.setText(R.string.load_paired_xprinters)
            updateConnectionUi()
        }
    }

    private fun setupDeviceList() {
        rvDevices?.layoutManager = LinearLayoutManager(requireContext())
        deviceAdapter = DeviceAdapter().also { adapter ->
            adapter.onItemClick = ::pairPrinter
        }
        rvDevices?.adapter = deviceAdapter
        rvPrintQueue?.layoutManager = LinearLayoutManager(requireContext())
        rvPrintQueue?.isNestedScrollingEnabled = false
        printQueueAdapter = JobAdapter(::confirmCancelPrint)
        rvPrintQueue?.adapter = printQueueAdapter
    }

    private fun observePrintQueue() {
        database.printJobDao().getRecentByStatuses(
            statuses = listOf(
                PrintStatus.PENDING,
                PrintStatus.PRINTING,
                PrintStatus.FAILED,
                PrintStatus.DONE,
                PrintStatus.CANCELLED
            ),
            limit = PRINT_HISTORY_LIMIT
        ).observe(viewLifecycleOwner) { jobs ->
            val items = jobs.orEmpty()
            printQueueAdapter?.submitList(items)
            tvPrintQueueEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            rvPrintQueue?.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmCancelPrint(job: PrintJob) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.job_cancel_title)
            .setMessage(getString(R.string.job_cancel_message, job.nama))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.job_cancel_confirm) { _, _ -> cancelPrint(job) }
            .showWithBoxedButtons()
    }

    private fun cancelPrint(job: PrintJob) {
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                database.printJobDao().updateStatusIfCurrent(
                    id = job.id,
                    expectedStatus = PrintStatus.PENDING,
                    newStatus = PrintStatus.CANCELLED,
                    error = null,
                    updatedAt = Date()
                )
            }
            val message = if (changed == 1) {
                R.string.job_cancel_success
            } else {
                R.string.job_cancel_unavailable
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun observePrinterConfig() {
        database.printerConfigDao().getConfig().observe(viewLifecycleOwner) { config ->
            if (config == null) {
                tvPrinterName?.setText(R.string.printer_not_paired)
                tvPrinterMac?.text = "—"
                tvPrinterModel?.text = "—"
                btnForgetPrinter?.visibility = View.GONE
                return@observe
            }
            if (config.printerType != TYPE_XPRINTER) {
                requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove("printer_mac")
                    .remove("printer_name")
                    .putString("printer_type", TYPE_XPRINTER)
                    .putInt("printer_dpi", 203)
                    .apply()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    database.printerConfigDao().clear()
                }
                xPrinterManager.disconnect()
                return@observe
            }
            selectedChoice = PrinterChoice.from(config.printerDpi)
            printerTypeDropdown.setText(selectedChoice.label, false)
            tvPrinterName?.text = config.name
            tvPrinterMac?.text = config.macAddress ?: "—"
            tvPrinterModel?.text = config.model
            btnForgetPrinter?.visibility = if (config.macAddress != null) View.VISIBLE else View.GONE
            btnScan?.setText(R.string.load_paired_xprinters)
            updateConnectionUi()
        }
    }

    private fun observeConnectionState() {
        xPrinterManager.connectionStateLive.observe(viewLifecycleOwner) { updateConnectionUi() }
    }

    private fun updateConnectionUi() {
        val state = xPrinterManager.connectionStateLive.value ?: XPrinterBluetoothManager.STATE_DISCONNECTED
        val connectedState = XPrinterBluetoothManager.STATE_CONNECTED
        val connectingState = XPrinterBluetoothManager.STATE_CONNECTING
        tvConnectionStatus?.text = when (state) {
            connectedState -> getString(R.string.printer_connected)
            connectingState -> getString(R.string.printer_connecting)
            else -> getString(R.string.printer_disconnected)
        }
        btnTestPrint?.isEnabled = state == connectedState
        // This button opens a preview first, so keep the preview available even while disconnected.
        btnBuyerNotice?.isEnabled = true
        btnPrintPdf?.isEnabled = true
        tvBattery?.setText(R.string.printer_battery_unavailable)
    }

    private fun setupClickListeners() {
        btnScan?.setOnClickListener {
            loadPairedXPrinters()
        }
        btnTestPrint?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), PrintForegroundService::class.java).apply {
                action = PrintForegroundService.ACTION_TEST_PRINT
                putExtra(PrintForegroundService.EXTRA_TEST_DATA, "UJI DARI APLIKASI")
            }
            androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent)
        }
        btnBuyerNotice?.setOnClickListener { showBuyerNoticePreview() }
        btnPrintPdf?.setOnClickListener { openPdfLauncher.launch(arrayOf("application/pdf")) }
        btnForgetPrinter?.setOnClickListener { forgetPrinter() }
    }

    private fun preparePdfPreview(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        btnPrintPdf?.isEnabled = false
        Toast.makeText(requireContext(), R.string.pdf_receipt_reading, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = pdfDisplayName(uri)
                    ShippingLabelPdfDocument.open(requireContext().contentResolver, uri).use { document ->
                        PdfPreview(
                            uri = uri,
                            displayName = name,
                            pageCount = document.pageCount,
                            firstPage = document.renderPage(0, ShippingLabelPdf.PREVIEW_DPI)
                        )
                    }
                }
            }
            btnPrintPdf?.isEnabled = true
            result.onSuccess(::showPdfPreview).onFailure { error ->
                val detail = error.message ?: getString(R.string.unknown_error)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.pdf_receipt_invalid, detail),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pdfDisplayName(uri: Uri): String {
        val resolver = requireContext().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "resi.pdf"
    }

    private fun showPdfPreview(pdf: PdfPreview) {
        val confirmationView = layoutInflater.inflate(R.layout.dialog_print_confirmation, null)
        confirmationView.findViewById<ImageView>(R.id.iv_confirm_print_preview).apply {
            setImageBitmap(pdf.firstPage)
            contentDescription = getString(R.string.pdf_receipt_preview_description)
        }
        confirmationView.findViewById<TextView>(R.id.tv_confirm_print_details).text = getString(
            R.string.pdf_receipt_details,
            pdf.displayName,
            pdf.pageCount
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pdf_receipt_preview_title)
            .setView(confirmationView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(getString(R.string.pdf_receipt_print, pdf.pageCount), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (xPrinterManager.connectionStateLive.value != XPrinterBluetoothManager.STATE_CONNECTED) {
                    Toast.makeText(
                        requireContext(),
                        R.string.pdf_receipt_printer_required,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                printPdf(pdf)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { pdf.firstPage.recycle() }
        dialog.showWithBoxedButtons()
    }

    private fun printPdf(pdf: PdfPreview) {
        val intent = Intent(requireContext(), PrintForegroundService::class.java).apply {
            action = PrintForegroundService.ACTION_PRINT_PDF
            data = pdf.uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(PrintForegroundService.EXTRA_PDF_NAME, pdf.displayName)
        }
        androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent)
        Toast.makeText(
            requireContext(),
            getString(R.string.pdf_receipt_started, pdf.pageCount),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showBuyerNoticePreview() {
        val preview = BuyerNoticeLabelGenerator.generate(
            BitmapFactory.decodeResource(resources, R.drawable.lithia_project_logo)
        )
        val confirmationView = layoutInflater.inflate(R.layout.dialog_print_confirmation, null)
        confirmationView.findViewById<ImageView>(R.id.iv_confirm_print_preview).apply {
            setImageBitmap(preview)
            contentDescription = getString(R.string.buyer_notice_preview_description)
        }
        confirmationView.findViewById<TextView>(R.id.tv_confirm_print_details).text =
            getString(R.string.buyer_notice_print_details)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.buyer_notice_preview_title)
            .setView(confirmationView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm_and_print) { _, _ -> enqueueBuyerNotice() }
            .showWithBoxedButtons()
    }

    private fun enqueueBuyerNotice() {
        val intent = android.content.Intent(requireContext(), PrintForegroundService::class.java).apply {
            action = PrintForegroundService.ACTION_PRINT_BUYER_NOTICE
        }
        androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent)
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedXPrinters() {
        progressBar?.visibility = View.GONE
        val devices = xPrinterManager.pairedDevices()
        showDevices(devices)
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), R.string.xprinter_pair_in_android_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDevices(devices: List<BluetoothDevice>) {
        deviceAdapter?.submitList(devices)
        val visible = devices.isNotEmpty()
        rvDevices?.visibility = if (visible) View.VISIBLE else View.GONE
        tvDiscoveredLabel?.visibility = if (visible) View.VISIBLE else View.GONE
        progressBar?.visibility = View.GONE
    }

    private fun pairPrinter(device: BluetoothDevice) {
        pairXPrinter(device)
    }

    @SuppressLint("MissingPermission")
    private fun pairXPrinter(device: BluetoothDevice) {
        xPrinterManager.connect(device.address) { success, error ->
            if (success) savePrinter(device, selectedChoice)
            showPairingResult(success, device.name, error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun savePrinter(device: BluetoothDevice, choice: PrinterChoice) {
        val name = device.name ?: choice.label
        requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("printer_mac", device.address)
            .putString("printer_name", name)
            .putString("printer_type", choice.type)
            .putInt("printer_dpi", choice.dpi)
            .apply()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            database.printerConfigDao().insert(
                PrinterConfig(
                    macAddress = device.address,
                    model = "XPrinter TSPL ${choice.dpi} DPI",
                    name = name,
                    printerType = choice.type,
                    printerDpi = choice.dpi,
                    isDefault = true
                )
            )
        }
    }

    private fun showPairingResult(success: Boolean, name: String?, error: String?) {
        activity?.runOnUiThread {
            val message = if (success) {
                getString(R.string.printer_pair_success, name ?: selectedChoice.label)
            } else {
                getString(R.string.printer_pair_failed, error ?: getString(R.string.unknown_error))
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun forgetPrinter() {
        requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("printer_mac")
            .remove("printer_name")
            .remove("printer_type")
            .remove("printer_dpi")
            .apply()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { database.printerConfigDao().clear() }
        xPrinterManager.disconnect()
        Toast.makeText(requireContext(), R.string.printer_forgotten, Toast.LENGTH_SHORT).show()
    }

    private enum class PrinterChoice(val label: String, val type: String, val dpi: Int) {
        XPRINTER_203("XPrinter Bluetooth TSPL · 203 DPI", TYPE_XPRINTER, 203),
        XPRINTER_300("XPrinter Bluetooth TSPL · 300 DPI", TYPE_XPRINTER, 300);

        companion object {
            fun from(dpi: Int): PrinterChoice = when {
                dpi >= 300 -> XPRINTER_300
                else -> XPRINTER_203
            }
        }
    }

    private companion object {
        const val TYPE_XPRINTER = "XPRINTER"
        const val PRINT_HISTORY_LIMIT = 20
    }

    private data class PdfPreview(
        val uri: Uri,
        val displayName: String,
        val pageCount: Int,
        val firstPage: android.graphics.Bitmap
    )
}

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    private var devices: List<BluetoothDevice> = emptyList()
    var onItemClick: ((BluetoothDevice) -> Unit)? = null

    fun submitList(newDevices: List<BluetoothDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) = holder.bind(devices[position])

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tv_device_name)
        private val mac: TextView = view.findViewById(R.id.tv_device_mac)

        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            name.text = device.name ?: itemView.context.getString(R.string.unknown_device)
            mac.text = device.address
            itemView.setOnClickListener { onItemClick?.invoke(device) }
        }
    }
}
