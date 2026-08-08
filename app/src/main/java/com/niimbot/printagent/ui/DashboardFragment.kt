package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintStatus
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private lateinit var database: AppDatabase
    private var tvPrinterStatus: TextView? = null
    private var tvPendingCount: TextView? = null
    private var tvPrintingCount: TextView? = null
    private var tvDoneCount: TextView? = null
    private var tvFailedCount: TextView? = null
    private var tvUptime: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getInstance(requireContext())
        
        tvPrinterStatus = view.findViewById(R.id.tv_printer_status)
        tvPendingCount = view.findViewById(R.id.tv_pending_count)
        tvPrintingCount = view.findViewById(R.id.tv_printing_count)
        tvDoneCount = view.findViewById(R.id.tv_done_count)
        tvFailedCount = view.findViewById(R.id.tv_failed_count)
        tvUptime = view.findViewById(R.id.tv_uptime)
        
        observeData()
    }
    
    private fun observeData() {
        // Printer connection status
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()
        
        bleManager.connectionStateLive.observe(viewLifecycleOwner) { state ->
            val statusText = when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED -> "🟢 Connected"
                NiimbotBluetoothManager.STATE_CONNECTING -> "🟡 Connecting..."
                NiimbotBluetoothManager.STATE_DISCONNECTED -> "🔴 Disconnected"
                else -> "⚫ Unknown"
            }
            tvPrinterStatus?.text = statusText
        }
        
        // Queue counts
        database.printJobDao().getByStatus(PrintStatus.PENDING).observe(viewLifecycleOwner) { jobs ->
            tvPendingCount?.text = jobs?.size.toString() ?: "0"
        }
        
        database.printJobDao().getByStatus(PrintStatus.PRINTING).observe(viewLifecycleOwner) { jobs ->
            tvPrintingCount?.text = jobs?.size.toString() ?: "0"
        }
        
        database.printJobDao().getByStatus(PrintStatus.DONE).observe(viewLifecycleOwner) { jobs ->
            tvDoneCount?.text = jobs?.size.toString() ?: "0"
        }
        
        database.printJobDao().getByStatus(PrintStatus.FAILED).observe(viewLifecycleOwner) { jobs ->
            tvFailedCount?.text = jobs?.size.toString() ?: "0"
        }
        
        // Uptime (refresh every 5 seconds)
        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val uptime = getUptime()
                tvUptime?.text = formatUptime(uptime)
                kotlinx.coroutines.delay(5000)
            }
        }
    }
    
    private fun getUptime(): Long {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/uptime")
            val input = process.inputStream.bufferedReader()
            val line = input.readLine() ?: return 0
            val uptimeSeconds = line.split(" ")[0].toDouble()
            input.close()
            uptimeSeconds.toLong()
        } catch (e: Exception) {
            0
        }
    }
    
    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return if (days > 0) "${days}d ${hours}h ${minutes}m"
        else if (hours > 0) "${hours}h ${minutes}m"
        else "${minutes}m"
    }
}