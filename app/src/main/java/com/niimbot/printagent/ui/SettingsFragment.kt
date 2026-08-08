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
import androidx.lifecycle.Observer
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.service.PrintForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @javax.inject.Inject
    lateinit var database: AppDatabase
    
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        bindViews(view)
        setupClickListeners()
        loadSettings()
    }
    
    private fun bindViews(view: View) {
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
    }
    

    
    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
        
        etServerPort?.setText(prefs.getInt("server_port", 8080).toString())
        etTunnelUrl?.setText(prefs.getString("tunnel_url", ""))
        swTailscale?.isChecked = prefs.getBoolean("tailscale_enabled", false)
        swLanOnly?.isChecked = prefs.getBoolean("lan_only", true)
    }
    
    private fun setupClickListeners() {
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
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = LabelGenerator.generateLabel(
                nama = "Sample Product Name",
                hargaJual = 25000,
                hargaBeli = 15000,
                sku = "SPL001",
                satuan = "pcs",
                barcodeData = "SPL001"
            )
            
            requireActivity().runOnUiThread {
                ivLabelPreview?.setImageBitmap(bitmap)
                Toast.makeText(requireContext(), "Preview generated", Toast.LENGTH_SHORT).show()
            }
        }
    }
}