package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosProductRules
import com.niimbot.printagent.service.PrintForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @javax.inject.Inject
    lateinit var database: AppDatabase

    @javax.inject.Inject
    lateinit var integrationConfigStore: IntegrationConfigStore

    @javax.inject.Inject
    lateinit var posApiClient: PosApiClient

    private var etPosBaseUrl: EditText? = null
    private var etPosIntegrationKey: EditText? = null
    private var btnSavePosConfig: Button? = null
    private var btnTestPosConnection: Button? = null
    
    // Server config
    private var etServerPort: EditText? = null
    private var btnSavePort: Button? = null
    
    // Tunnel config
    private var etTunnelUrl: EditText? = null
    private var swTailscale: Switch? = null
    private var swLanOnly: Switch? = null
    private var btnSaveTunnel: Button? = null
    
    // Label preview
    private var ivLabelPreview: ImageView? = null
    private var btnGeneratePreview: Button? = null
    
    // Printer config
    private var tvPairedPrinter: TextView? = null
    private var btnForgetPrinter: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        bindViews(view)
        observePrinterConfig()
        setupClickListeners()
        loadSettings()
    }
    
    private fun bindViews(view: View) {
        etPosBaseUrl = view.findViewById(R.id.et_pos_base_url)
        etPosIntegrationKey = view.findViewById(R.id.et_pos_integration_key)
        btnSavePosConfig = view.findViewById(R.id.btn_save_pos_config)
        btnTestPosConnection = view.findViewById(R.id.btn_test_pos_connection)

        // Server
        etServerPort = view.findViewById(R.id.et_server_port)
        btnSavePort = view.findViewById(R.id.btn_save_port)
        
        // Tunnel
        etTunnelUrl = view.findViewById(R.id.et_tunnel_url)
        swTailscale = view.findViewById(R.id.sw_tailscale)
        swLanOnly = view.findViewById(R.id.sw_lan_only)
        btnSaveTunnel = view.findViewById(R.id.btn_save_tunnel)
        
        // Label preview
        ivLabelPreview = view.findViewById(R.id.iv_label_preview)
        btnGeneratePreview = view.findViewById(R.id.btn_generate_preview)
        
        // Printer
        tvPairedPrinter = view.findViewById(R.id.tv_paired_printer)
        btnForgetPrinter = view.findViewById(R.id.btn_forget_printer)
    }
    
    private fun observePrinterConfig() {
        database.printerConfigDao().getConfig().observe(viewLifecycleOwner) { config ->
            config?.let {
                tvPairedPrinter?.text = if (it.macAddress != null) {
                    "${it.name} (${it.macAddress})"
                } else {
                    "No printer paired"
                }
                btnForgetPrinter?.visibility = if (it.macAddress != null) View.VISIBLE else View.GONE
            }
        }
    }
    
    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
        
        etServerPort?.setText(prefs.getInt("server_port", 8080).toString())
        etTunnelUrl?.setText(prefs.getString("tunnel_url", ""))
        swTailscale?.isChecked = prefs.getBoolean("tailscale_enabled", false)
        swLanOnly?.isChecked = prefs.getBoolean("lan_only", true)
        etPosBaseUrl?.setText(integrationConfigStore.getBaseUrl())
        etPosIntegrationKey?.setText(
            if (integrationConfigStore.hasIntegrationKey()) IntegrationConfigStore.MASKED_KEY else ""
        )
    }
    
    private fun setupClickListeners() {
        btnSavePosConfig?.setOnClickListener { savePosConfig() }
        btnTestPosConnection?.setOnClickListener { testPosConnection() }
        etPosIntegrationKey?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etPosIntegrationKey?.text.toString() == IntegrationConfigStore.MASKED_KEY) {
                etPosIntegrationKey?.text?.clear()
            }
        }

        // Server port
        btnSavePort?.setOnClickListener {
            val port = etServerPort?.text?.toString()?.toIntOrNull() ?: 8080
            val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("server_port", port).apply()
            
            // Restart print server with new port
            restartPrintServer(port)
            
            Toast.makeText(requireContext(), "Port saved: $port", Toast.LENGTH_SHORT).show()
        }
        
        // Tunnel config
        btnSaveTunnel?.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("tunnel_url", etTunnelUrl?.text.toString())
                .putBoolean("tailscale_enabled", swTailscale?.isChecked ?: false)
                .putBoolean("lan_only", swLanOnly?.isChecked ?: true)
                .apply()
            
            Toast.makeText(requireContext(), "Tunnel config saved", Toast.LENGTH_SHORT).show()
        }
        
        // Label preview
        btnGeneratePreview?.setOnClickListener {
            generateLabelPreview()
        }
        
        // Forget printer
        btnForgetPrinter?.setOnClickListener {
            forgetPrinter()
        }
    }
    
    private fun restartPrintServer(port: Int) {
        val context = requireContext()
        val intent = android.content.Intent(context, PrintForegroundService::class.java)
        intent.putExtra("restart_port", port)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    private fun generateLabelPreview() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                LabelGenerator.generateLabel(
                    nama = "Sample Product Name",
                    hargaJual = 25000,
                    hargaBeli = 18000,
                    sku = "SPL001",
                    satuan = "pcs",
                    barcodeData = "SPL001"
                )
            }
            ivLabelPreview?.setImageBitmap(bitmap)
            Toast.makeText(requireContext(), "Preview generated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePosConfig() {
        val baseUrl = PosProductRules.normalizeBaseUrl(etPosBaseUrl?.text.toString())
        if (baseUrl.toHttpUrlOrNull() == null) {
            etPosBaseUrl?.error = getString(R.string.pos_url_invalid)
            return
        }
        etPosBaseUrl?.error = null
        integrationConfigStore.setBaseUrl(baseUrl)
        val enteredKey = etPosIntegrationKey?.text.toString()
        if (enteredKey.isNotBlank() && enteredKey != IntegrationConfigStore.MASKED_KEY) {
            integrationConfigStore.setIntegrationKey(enteredKey)
        }
        etPosIntegrationKey?.setText(
            if (integrationConfigStore.hasIntegrationKey()) IntegrationConfigStore.MASKED_KEY else ""
        )
        Toast.makeText(requireContext(), R.string.pos_config_saved, Toast.LENGTH_SHORT).show()
    }

    private fun testPosConnection() {
        val baseUrl = PosProductRules.normalizeBaseUrl(etPosBaseUrl?.text.toString())
        if (baseUrl.toHttpUrlOrNull() == null) {
            etPosBaseUrl?.error = getString(R.string.pos_url_invalid)
            return
        }
        val enteredKey = etPosIntegrationKey?.text.toString()
        val key = if (enteredKey.isNotBlank() && enteredKey != IntegrationConfigStore.MASKED_KEY) {
            enteredKey.trim()
        } else {
            integrationConfigStore.getIntegrationKey()
        }
        if (key.isNullOrBlank()) {
            etPosIntegrationKey?.error = getString(R.string.pos_test_key_required)
            return
        }
        etPosIntegrationKey?.error = null
        btnTestPosConnection?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { posApiClient.testConnection(baseUrl, key) }
                .getOrElse { PosApiResult.Failure(it.message ?: getString(R.string.pos_request_failed)) }
            btnTestPosConnection?.isEnabled = true
            val message = when (result) {
                is PosApiResult.Success -> getString(R.string.pos_test_success)
                is PosApiResult.Failure -> result.message
                PosApiResult.NotFound -> getString(R.string.pos_test_success)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun forgetPrinter() {
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .remove("printer_mac")
            .remove("printer_name")
            .apply()
        
        // Clear database
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            database.printerConfigDao().clear()
        }
        
        // Disconnect BLE
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()
        bleManager.disconnect()
        
        Toast.makeText(requireContext(), "Printer forgotten", Toast.LENGTH_SHORT).show()
    }
}
