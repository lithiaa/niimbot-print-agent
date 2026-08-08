package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject
    lateinit var database: AppDatabase

    private var tvPrinterStatus: TextView? = null
    private var tvPendingCount: TextView? = null
    private var tvPrintingCount: TextView? = null
    private var tvDoneCount: TextView? = null
    private var tvFailedCount: TextView? = null
    private var tvUptime: TextView? = null
    private var tvServerEndpoint: TextView? = null
    private var tvServerStatus: TextView? = null

    private var uptimeJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvPrinterStatus = view.findViewById(R.id.tv_printer_status)
        tvPendingCount  = view.findViewById(R.id.tv_pending_count)
        tvPrintingCount = view.findViewById(R.id.tv_printing_count)
        tvDoneCount     = view.findViewById(R.id.tv_done_count)
        tvFailedCount   = view.findViewById(R.id.tv_failed_count)
        tvUptime        = view.findViewById(R.id.tv_uptime)
        tvServerEndpoint = view.findViewById(R.id.tv_server_endpoint)
        tvServerStatus  = view.findViewById(R.id.tv_server_status)

        observeData()
        showServerInfo()
    }

    override fun onResume() {
        super.onResume()
        startUptimeTicker()
    }

    override fun onPause() {
        super.onPause()
        uptimeJob?.cancel()
    }

    private fun observeData() {
        // Printer connection status — get bleManager from Application
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()

        bleManager.connectionStateLive.observe(viewLifecycleOwner) { state ->
            val statusText = when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED    -> "🟢 Connected"
                NiimbotBluetoothManager.STATE_CONNECTING   -> "🟡 Connecting..."
                NiimbotBluetoothManager.STATE_DISCONNECTED -> "🔴 Disconnected"
                else -> "⚫ Unknown"
            }
            tvPrinterStatus?.text = statusText
        }

        // Queue counts
        database.printJobDao().getByStatus(PrintStatus.PENDING).observe(viewLifecycleOwner) { jobs ->
            tvPendingCount?.text = (jobs?.size ?: 0).toString()
        }

        database.printJobDao().getByStatus(PrintStatus.PRINTING).observe(viewLifecycleOwner) { jobs ->
            tvPrintingCount?.text = (jobs?.size ?: 0).toString()
        }

        database.printJobDao().getByStatus(PrintStatus.DONE).observe(viewLifecycleOwner) { jobs ->
            tvDoneCount?.text = (jobs?.size ?: 0).toString()
        }

        database.printJobDao().getByStatus(PrintStatus.FAILED).observe(viewLifecycleOwner) { jobs ->
            tvFailedCount?.text = (jobs?.size ?: 0).toString()
        }
    }

    private fun showServerInfo() {
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
        val port = prefs.getInt("server_port", 8080)
        val ip = getDeviceIpAddress()
        tvServerEndpoint?.text = "$ip:$port"
        tvServerStatus?.text = "🟢 Running"
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }

    companion object {
        val appStartTime = System.currentTimeMillis()
    }

    private fun startUptimeTicker() {
        uptimeJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val uptimeMs = System.currentTimeMillis() - appStartTime
                tvUptime?.text = formatUptime(uptimeMs / 1000)
                delay(10_000) // refresh every 10 seconds
            }
        }
    }

    private fun formatUptime(seconds: Long): String {
        val days    = seconds / 86400
        val hours   = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0  -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else      -> "${minutes}m"
        }
    }
}