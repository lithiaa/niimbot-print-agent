package com.niimbot.printagent.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.niimbot.printagent.NiimbotPrintApplication
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.ble.XPrinterBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.data.PrinterConfig
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var progressBar: ProgressBar? = null
    private var rvDevices: RecyclerView? = null
    private var tvDiscoveredLabel: TextView? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var btnForgetPrinter: Button? = null
    private var rvPrintQueue: RecyclerView? = null
    private var tvPrintQueueEmpty: TextView? = null
    private var printQueueAdapter: JobAdapter? = null
    private lateinit var printerTypeDropdown: AutoCompleteTextView
    private var selectedChoice = PrinterChoice.NIIMBOT

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
        printerTypeDropdown.setOnClickListener { printerTypeDropdown.showDropDown() }
        printerTypeDropdown.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString()
            selectedChoice = PrinterChoice.entries.firstOrNull { it.label == label } ?: PrinterChoice.NIIMBOT
            deviceAdapter?.submitList(emptyList())
            rvDevices?.visibility = View.GONE
            tvDiscoveredLabel?.visibility = View.GONE
            btnScan?.text = getString(
                if (selectedChoice.type == TYPE_NIIMBOT) R.string.scan_printers
                else R.string.load_paired_xprinters
            )
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
        printQueueAdapter = JobAdapter()
        rvPrintQueue?.adapter = printQueueAdapter
    }

    private fun observePrintQueue() {
        database.printJobDao().getByStatuses(
            listOf(PrintStatus.PENDING, PrintStatus.PRINTING, PrintStatus.FAILED, PrintStatus.DONE)
        ).observe(viewLifecycleOwner) { jobs ->
            val items = jobs.orEmpty()
            printQueueAdapter?.submitList(items)
            tvPrintQueueEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            rvPrintQueue?.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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
            selectedChoice = PrinterChoice.from(config.printerType, config.printerDpi)
            printerTypeDropdown.setText(selectedChoice.label, false)
            tvPrinterName?.text = config.name
            tvPrinterMac?.text = config.macAddress ?: "—"
            tvPrinterModel?.text = config.model
            btnForgetPrinter?.visibility = if (config.macAddress != null) View.VISIBLE else View.GONE
            btnScan?.text = getString(
                if (selectedChoice.type == TYPE_NIIMBOT) R.string.scan_printers
                else R.string.load_paired_xprinters
            )
            updateConnectionUi()
        }
    }

    private fun observeConnectionState() {
        niimbotManager().connectionStateLive.observe(viewLifecycleOwner) { updateConnectionUi() }
        xPrinterManager.connectionStateLive.observe(viewLifecycleOwner) { updateConnectionUi() }
        niimbotManager().discoveredDevicesLive.observe(viewLifecycleOwner) { devices ->
            if (selectedChoice.type != TYPE_NIIMBOT) return@observe
            showDevices(devices)
        }
    }

    private fun updateConnectionUi() {
        val state = if (selectedChoice.type == TYPE_NIIMBOT) {
            niimbotManager().connectionStateLive.value ?: NiimbotBluetoothManager.STATE_DISCONNECTED
        } else {
            xPrinterManager.connectionStateLive.value ?: XPrinterBluetoothManager.STATE_DISCONNECTED
        }
        val connectedState = if (selectedChoice.type == TYPE_NIIMBOT) {
            NiimbotBluetoothManager.STATE_CONNECTED
        } else {
            XPrinterBluetoothManager.STATE_CONNECTED
        }
        val connectingState = if (selectedChoice.type == TYPE_NIIMBOT) {
            NiimbotBluetoothManager.STATE_CONNECTING
        } else {
            XPrinterBluetoothManager.STATE_CONNECTING
        }
        tvConnectionStatus?.text = when (state) {
            connectedState -> getString(R.string.printer_connected)
            connectingState -> getString(R.string.printer_connecting)
            else -> getString(R.string.printer_disconnected)
        }
        btnTestPrint?.isEnabled = state == connectedState
        tvBattery?.text = if (selectedChoice.type == TYPE_NIIMBOT) "—" else getString(R.string.printer_battery_unavailable)
    }

    private fun setupClickListeners() {
        btnScan?.setOnClickListener {
            if (selectedChoice.type == TYPE_NIIMBOT) scanNiimbot() else loadPairedXPrinters()
        }
        btnTestPrint?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), PrintForegroundService::class.java).apply {
                action = PrintForegroundService.ACTION_TEST_PRINT
                putExtra(PrintForegroundService.EXTRA_TEST_DATA, "UJI DARI APLIKASI")
            }
            androidx.core.content.ContextCompat.startForegroundService(requireContext(), intent)
        }
        btnForgetPrinter?.setOnClickListener { forgetPrinter() }
    }

    private fun scanNiimbot() {
        progressBar?.visibility = View.VISIBLE
        btnScan?.isEnabled = false
        niimbotManager().startScan()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(10_000)
            niimbotManager().stopScan()
            btnScan?.isEnabled = true
            progressBar?.visibility = View.GONE
        }
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
        if (selectedChoice.type == TYPE_NIIMBOT) pairNiimbot(device) else pairXPrinter(device)
    }

    @SuppressLint("MissingPermission")
    private fun pairNiimbot(device: BluetoothDevice) {
        niimbotManager().stopScan()
        niimbotManager().connect(device.address) { success ->
            if (success) savePrinter(device, PrinterChoice.NIIMBOT)
            showPairingResult(success, device.name, null)
        }
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
                    model = if (choice.type == TYPE_NIIMBOT) "B1" else "XPrinter TSPL ${choice.dpi} DPI",
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
        niimbotManager().disconnect()
        xPrinterManager.disconnect()
        Toast.makeText(requireContext(), R.string.printer_forgotten, Toast.LENGTH_SHORT).show()
    }

    private fun niimbotManager(): NiimbotBluetoothManager =
        (requireActivity().applicationContext as NiimbotPrintApplication).getNiimbotManager()

    private enum class PrinterChoice(val label: String, val type: String, val dpi: Int) {
        NIIMBOT("Niimbot BLE (B1/B1 Pro)", TYPE_NIIMBOT, 300),
        XPRINTER_203("XPrinter Bluetooth TSPL · 203 DPI", TYPE_XPRINTER, 203),
        XPRINTER_300("XPrinter Bluetooth TSPL · 300 DPI", TYPE_XPRINTER, 300);

        companion object {
            fun from(type: String, dpi: Int): PrinterChoice = when {
                type == TYPE_XPRINTER && dpi >= 300 -> XPRINTER_300
                type == TYPE_XPRINTER -> XPRINTER_203
                else -> NIIMBOT
            }
        }
    }

    private companion object {
        const val TYPE_NIIMBOT = "NIIMBOT"
        const val TYPE_XPRINTER = "XPRINTER"
    }
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
