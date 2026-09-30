package com.niimbot.printagent.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.XPrinterBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosInventoryStatistics
import com.niimbot.printagent.pos.PosStockStatisticItem
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject lateinit var database: AppDatabase
    @Inject lateinit var xPrinterManager: XPrinterBluetoothManager
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: MaterialButton
    private lateinit var statisticsStatus: TextView
    private lateinit var totalProducts: TextView
    private lateinit var totalStock: TextView
    private lateinit var lowStockCount: TextView
    private lateinit var outOfStockCount: TextView
    private lateinit var lowStockContainer: LinearLayout
    private lateinit var outOfStockContainer: LinearLayout
    private lateinit var lowStockEmpty: TextView
    private lateinit var outOfStockEmpty: TextView
    private lateinit var printerStatus: TextView
    private lateinit var printerStatusDot: View
    private lateinit var printerMac: TextView

    private var statisticsJob: Job? = null
    private val numberFormat = NumberFormat.getIntegerInstance(Locale("id", "ID"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.swipe_dashboard)
        progressBar = view.findViewById(R.id.progress_dashboard_statistics)
        retryButton = view.findViewById(R.id.btn_retry_dashboard_statistics)
        statisticsStatus = view.findViewById(R.id.tv_dashboard_statistics_status)
        totalProducts = view.findViewById(R.id.tv_total_products)
        totalStock = view.findViewById(R.id.tv_total_stock)
        lowStockCount = view.findViewById(R.id.tv_low_stock_count)
        outOfStockCount = view.findViewById(R.id.tv_out_of_stock_count)
        lowStockContainer = view.findViewById(R.id.container_low_stock)
        outOfStockContainer = view.findViewById(R.id.container_out_of_stock)
        lowStockEmpty = view.findViewById(R.id.tv_low_stock_empty)
        outOfStockEmpty = view.findViewById(R.id.tv_out_of_stock_empty)
        printerStatus = view.findViewById(R.id.tv_printer_status)
        printerStatusDot = view.findViewById(R.id.view_status_dot)
        printerMac = view.findViewById(R.id.tv_mac)

        swipeRefresh.setColorSchemeResources(R.color.primary)
        swipeRefresh.setOnRefreshListener(::loadStatistics)
        retryButton.setOnClickListener { loadStatistics() }

        observePrinterStatus()
        loadStatistics()
    }

    private fun observePrinterStatus() {
        xPrinterManager.connectionStateLive.observe(viewLifecycleOwner) { state ->
            val statusText = when (state) {
                XPrinterBluetoothManager.STATE_CONNECTED -> R.string.printer_connected
                XPrinterBluetoothManager.STATE_CONNECTING -> R.string.printer_connecting
                else -> R.string.printer_disconnected
            }
            val statusColor = when (state) {
                XPrinterBluetoothManager.STATE_CONNECTED -> R.color.success
                XPrinterBluetoothManager.STATE_CONNECTING -> R.color.warning
                else -> R.color.error
            }
            printerStatus.setText(statusText)
            printerStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), statusColor)
            )
        }

        database.printerConfigDao().getConfig().observe(viewLifecycleOwner) { config ->
            printerMac.text = config?.macAddress ?: getString(R.string.printer_not_paired)
        }
    }

    private fun loadStatistics() {
        val accessToken = configStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            showStatisticsMessage(getString(R.string.pos_login_required), showRetry = false)
            showUnavailableStatistics()
            return
        }

        statisticsJob?.cancel()
        setStatisticsLoading(true)
        statisticsJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = posApiClient.getInventoryStatistics(configStore.getBaseUrl(), accessToken)
            setStatisticsLoading(false)
            when (result) {
                is PosApiResult.Success -> renderStatistics(result.value)
                PosApiResult.SessionExpired -> {
                    configStore.clearSession()
                    showUnavailableStatistics()
                    showStatisticsMessage(getString(R.string.pos_session_expired), showRetry = false)
                }
                PosApiResult.NotFound -> {
                    showUnavailableStatistics()
                    showStatisticsMessage(getString(R.string.dashboard_statistics_not_found), showRetry = true)
                }
                is PosApiResult.Failure -> {
                    showStatisticsMessage(result.message, showRetry = true)
                }
            }
        }
    }

    private fun setStatisticsLoading(loading: Boolean) {
        progressBar.visibility = if (loading && !swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
        if (!loading) swipeRefresh.isRefreshing = false
        retryButton.visibility = View.GONE
        if (loading) statisticsStatus.setText(R.string.dashboard_statistics_loading)
    }

    private fun renderStatistics(statistics: PosInventoryStatistics) {
        totalProducts.text = numberFormat.format(statistics.totalBarang)
        totalStock.text = numberFormat.format(statistics.totalStok)
        lowStockCount.text = numberFormat.format(statistics.totalStokMenipis)
        outOfStockCount.text = numberFormat.format(statistics.totalStokHabis)

        renderStockItems(
            container = lowStockContainer,
            emptyView = lowStockEmpty,
            items = statistics.stokMenipis,
            accentColor = R.color.warning,
            accentBackground = 0xFFFFF7E6.toInt()
        )
        renderStockItems(
            container = outOfStockContainer,
            emptyView = outOfStockEmpty,
            items = statistics.stokHabis,
            accentColor = R.color.error,
            accentBackground = 0xFFFFEEF1.toInt()
        )

        val updatedAt = SimpleDateFormat("HH.mm", Locale("id", "ID")).format(Date())
        statisticsStatus.text = getString(R.string.dashboard_statistics_updated, updatedAt)
        statisticsStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        retryButton.visibility = View.GONE
    }

    private fun renderStockItems(
        container: LinearLayout,
        emptyView: TextView,
        items: List<PosStockStatisticItem>,
        accentColor: Int,
        accentBackground: Int
    ) {
        container.removeAllViews()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val foreground = ContextCompat.getColor(requireContext(), accentColor)

        val visibleItems = DashboardStockListRules.visibleItems(items)
        visibleItems.forEach { item ->
            val itemView = layoutInflater.inflate(R.layout.item_dashboard_stock_alert, container, false)
            val initialCard = itemView.findViewById<MaterialCardView>(R.id.card_stock_initial)
            val initial = itemView.findViewById<TextView>(R.id.tv_stock_initial)
            initialCard.setCardBackgroundColor(accentBackground)
            initial.setTextColor(foreground)
            initial.text = item.nama.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            itemView.findViewById<TextView>(R.id.tv_stock_name).text = item.nama
            itemView.findViewById<TextView>(R.id.tv_stock_sku).text =
                getString(R.string.dashboard_stock_sku, item.sku)
            itemView.findViewById<TextView>(R.id.tv_stock_value).apply {
                setTextColor(foreground)
                text = getString(
                    R.string.dashboard_stock_item_value,
                    numberFormat.format(item.stok),
                    item.satuan,
                    numberFormat.format(item.stokMinimum)
                )
            }
            container.addView(itemView)
        }

        val hiddenCount = items.size - visibleItems.size
        if (hiddenCount > 0) {
            val summary = layoutInflater.inflate(R.layout.item_dashboard_stock_more, container, false)
            summary.findViewById<TextView>(R.id.tv_stock_more).text = getString(
                R.string.dashboard_stock_more,
                DashboardStockListRules.MAX_VISIBLE_ITEMS,
                items.size
            )
            summary.setOnClickListener {
                requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    R.id.bottom_navigation
                ).selectedItemId = R.id.nav_product_info
            }
            container.addView(summary)
        }
    }

    private fun showUnavailableStatistics() {
        totalProducts.text = "—"
        totalStock.text = "—"
        lowStockCount.text = "—"
        outOfStockCount.text = "—"
        lowStockContainer.removeAllViews()
        outOfStockContainer.removeAllViews()
        lowStockEmpty.visibility = View.VISIBLE
        outOfStockEmpty.visibility = View.VISIBLE
    }

    private fun showStatisticsMessage(message: String, showRetry: Boolean) {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        statisticsStatus.text = message
        statisticsStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
        retryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        statisticsJob?.cancel()
        super.onDestroyView()
    }
}
