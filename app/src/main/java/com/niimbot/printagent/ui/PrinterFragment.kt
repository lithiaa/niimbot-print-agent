package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.service.PrintForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PrinterFragment : Fragment() {

    private lateinit var database: AppDatabase
    private var tvPrinterName: TextView? = null
    private var tvPrinterMac: TextView? = null
    private var tvPrinterModel: TextView? = null
    private var tvConnectionStatus: TextView? = null
    private var tvBattery: TextView? = null
    private var btnScan: Button? = null
    private var btnTestPrint: Button? = null
    private var progressBar: ProgressBar? = null
    private var rvDevices: RecyclerView? = null
    private var deviceAdapter: DeviceAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_printer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getInstance(requireContext())
        
        tvPrinterName = view.findViewById(R.id.tv_printer_name)
        tvPrinterMac = view.findViewById(R.id.tv_printer_mac)
        tvPrinterModel = view.findViewById(R.id.tv_printer_model)
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status)
        tvBattery = view.findViewById(R.id.tv_battery)
        btnScan = view.findViewById(R.id.btn_scan)
        btnTestPrint = view.findViewById(R.id.btn_test_print)
        progressBar = view.findViewById(R.id.progress_scan)
        rvDevices = view.findViewById(R.id.rv_devices)
        
        rvDevices?.layoutManager = LinearLayoutManager(requireContext())
        deviceAdapter = DeviceAdapter()
        rvDevices?.adapter = deviceAdapter
        
        observePrinterConfig()
        observeBleState()
        setupClickListeners()
    }
    
    private fun observePrinterConfig() {
        database.printerConfigDao().getConfig().observe(viewLifecycleOwner) { config ->
            config?.let {
                tvPrinterName?.text = it.name
                tvPrinterMac?.text = it.macAddress ?: "Not paired"
                tvPrinterModel?.text = it.model
            }
        }
    }
    
    private fun observeBleState() {
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()
        
        bleManager.connectionStateLive.observe(viewLifecycleOwner) { state ->
            val statusText = when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED -> "🟢 Connected"
                NiimbotBluetoothManager.STATE_CONNECTING -> "🟡 Connecting..."
                NiimbotBluetoothManager.STATE_DISCONNECTED -> "🔴 Disconnected"
                else -> "⚫ Unknown"
            }
            tvConnectionStatus?.text = statusText
            btnTestPrint?.isEnabled = state == NiimbotBluetoothManager.STATE_CONNECTED
        }
        
        // Observe discovered devices
        bleManager.discoveredDevicesLive.observe(viewLifecycleOwner) { devices ->
            deviceAdapter?.submitList(devices)
            progressBar?.visibility = if (devices.isNotEmpty()) View.GONE else View.VISIBLE
        }
    }
    
    private fun setupClickListeners() {
        btnScan?.setOnClickListener {
            val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
                .getNiimbotManager()
            progressBar?.visibility = View.VISIBLE
            btnScan?.isEnabled = false
            bleManager.startScan()
            
            // Auto-stop scan after 10s
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(10000)
                bleManager.stopScan()
                btnScan?.isEnabled = true
            }
        }
        
        btnTestPrint?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), PrintForegroundService::class.java)
            intent.action = PrintForegroundService.ACTION_TEST_PRINT
            intent.putExtra(PrintForegroundService.EXTRA_TEST_DATA, "TEST FROM UI")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        }
        
        deviceAdapter?.onItemClick = { device ->
            pairPrinter(device)
        }
    }
    
    private fun pairPrinter(device: android.bluetooth.BluetoothDevice) {
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()
        
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
        
        bleManager.connect(device.address) { success ->
            if (success) {
                prefs.edit().putString("printer_mac", device.address).apply()
                prefs.edit().putString("printer_name", device.name ?: "Niimbot B1 Pro").apply()
                
                // Update database
                CoroutineScope(Dispatchers.IO).launch {
                    val config = com.niimbot.printagent.data.PrinterConfig(
                        macAddress = device.address,
                        model = "B1",
                        name = device.name ?: "Niimbot B1 Pro",
                        isDefault = true
                    )
                    database.printerConfigDao().insert(config)
                }
                
                requireActivity().runOnUiThread {
                    android.widget.Toast.makeText(requireContext(), "Paired with ${device.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                requireActivity().runOnUiThread {
                    android.widget.Toast.makeText(requireContext(), "Pairing failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// ===================== Device Adapter =====================

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    
    private var devices: List<android.bluetooth.BluetoothDevice> = emptyList()
    var onItemClick: ((android.bluetooth.BluetoothDevice) -> Unit)? = null
    
    fun submitList(newDevices: List<android.bluetooth.BluetoothDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
    }
    
    override fun getItemCount(): Int = devices.size
    
    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_device_name)
        private val tvMac: TextView = view.findViewById(R.id.tv_device_mac)
        
        fun bind(device: android.bluetooth.BluetoothDevice) {
            tvName.text = device.name ?: "Unknown Device"
            tvMac.text = device.address
            itemView.setOnClickListener { onItemClick?.invoke(device) }
        }
    }
}