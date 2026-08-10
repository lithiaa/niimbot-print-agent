package com.niimbot.printagent.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.niimbot.printagent.R
import com.niimbot.printagent.ble.NiimbotBluetoothManager
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class DashboardFragment : Fragment() {

    @Inject
    lateinit var database: AppDatabase

    private var tvPrinterStatus: TextView? = null
    private var tvStatusDot: TextView? = null
    private var tvMac: TextView? = null
    private var tvTotalCount: TextView? = null
    private var tvTotalMeta: TextView? = null
    private var tvPendingCount: TextView? = null
    private var tvPendingMeta: TextView? = null
    private var tvPrintingCount: TextView? = null
    private var tvDoneCount: TextView? = null
    private var tvDoneMeta: TextView? = null
    private var tvFailedCount: TextView? = null
    private var tvFailedMeta: TextView? = null
    private var tvUptime: TextView? = null
    private var activityChart: StatusBarChartView? = null
    private var printerGauge: ConnectionGaugeView? = null
    private var recentTrendChart: RecentTrendChartView? = null
    private var tvServerEndpoint: TextView? = null
    private var tvServerStatus: TextView? = null

    private var pendingJobs: List<PrintJob> = emptyList()
    private var printingJobs: List<PrintJob> = emptyList()
    private var doneJobs: List<PrintJob> = emptyList()
    private var failedJobs: List<PrintJob> = emptyList()
    private var connectionState = NiimbotBluetoothManager.STATE_DISCONNECTED
    private var uptimeJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvPrinterStatus = view.findViewById(R.id.tv_printer_status)
        tvStatusDot = view.findViewById(R.id.tv_status_dot)
        tvMac = view.findViewById(R.id.tv_mac)
        tvTotalCount = view.findViewById(R.id.tv_total_count)
        tvTotalMeta = view.findViewById(R.id.tv_total_meta)
        tvPendingCount = view.findViewById(R.id.tv_pending_count)
        tvPendingMeta = view.findViewById(R.id.tv_pending_meta)
        tvPrintingCount = view.findViewById(R.id.tv_printing_count)
        tvDoneCount = view.findViewById(R.id.tv_done_count)
        tvDoneMeta = view.findViewById(R.id.tv_done_meta)
        tvFailedCount = view.findViewById(R.id.tv_failed_count)
        tvFailedMeta = view.findViewById(R.id.tv_failed_meta)
        tvUptime = view.findViewById(R.id.tv_uptime)
        activityChart = view.findViewById(R.id.chart_activity)
        printerGauge = view.findViewById(R.id.chart_printer_gauge)
        recentTrendChart = view.findViewById(R.id.chart_recent_trend)
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
        val bleManager = (requireActivity().applicationContext as com.niimbot.printagent.NiimbotPrintApplication)
            .getNiimbotManager()

        bleManager.connectionStateLive.observe(viewLifecycleOwner) { state ->
            connectionState = state
            val statusText = when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED -> "Connected"
                NiimbotBluetoothManager.STATE_CONNECTING -> "Connecting..."
                NiimbotBluetoothManager.STATE_DISCONNECTED -> "Disconnected"
                else -> "Unknown"
            }
            val statusColor = when (state) {
                NiimbotBluetoothManager.STATE_CONNECTED -> R.color.success
                NiimbotBluetoothManager.STATE_CONNECTING -> R.color.warning
                NiimbotBluetoothManager.STATE_DISCONNECTED -> R.color.error
                else -> R.color.text_muted
            }
            tvPrinterStatus?.text = statusText
            tvStatusDot?.setTextColor(ContextCompat.getColor(requireContext(), statusColor))
            printerGauge?.setConnectionState(state)
        }

        database.printerConfigDao().getConfig().observe(viewLifecycleOwner) { config ->
            tvMac?.text = config?.macAddress ?: "No printer paired"
        }

        database.printJobDao().getByStatus(PrintStatus.PENDING).observe(viewLifecycleOwner) { jobs ->
            pendingJobs = jobs ?: emptyList()
            tvPendingCount?.text = pendingJobs.size.toString()
            updateDashboard()
        }

        database.printJobDao().getByStatus(PrintStatus.PRINTING).observe(viewLifecycleOwner) { jobs ->
            printingJobs = jobs ?: emptyList()
            tvPrintingCount?.text = "Printing: ${printingJobs.size}"
            updateDashboard()
        }

        database.printJobDao().getByStatus(PrintStatus.DONE).observe(viewLifecycleOwner) { jobs ->
            doneJobs = jobs ?: emptyList()
            tvDoneCount?.text = doneJobs.size.toString()
            updateDashboard()
        }

        database.printJobDao().getByStatus(PrintStatus.FAILED).observe(viewLifecycleOwner) { jobs ->
            failedJobs = jobs ?: emptyList()
            tvFailedCount?.text = failedJobs.size.toString()
            updateDashboard()
        }
    }

    private fun updateDashboard() {
        val total = pendingJobs.size + printingJobs.size + doneJobs.size + failedJobs.size
        val donePercent = if (total == 0) 0 else (doneJobs.size * 100 / total)
        tvTotalCount?.text = total.toString()
        tvTotalMeta?.text = if (total == 0) "No jobs yet" else "$donePercent% done"
        tvPendingMeta?.text = "${printingJobs.size} printing"
        tvDoneMeta?.text = "$donePercent% of total"
        tvFailedMeta?.text = if (failedJobs.isEmpty()) "No failures" else "Needs attention"

        activityChart?.setCounts(
            pendingJobs.size,
            printingJobs.size,
            doneJobs.size,
            failedJobs.size
        )
        recentTrendChart?.setJobs(pendingJobs + printingJobs + doneJobs + failedJobs)
        printerGauge?.setHasJobs(total > 0)
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
        uptimeJob?.cancel()
        uptimeJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val uptimeMs = System.currentTimeMillis() - appStartTime
                tvUptime?.text = formatUptime(uptimeMs / 1000)
                delay(10_000)
            }
        }
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}

abstract class DashboardChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    protected val primary = ContextCompat.getColor(context, R.color.primary)
    protected val primaryLight = ContextCompat.getColor(context, R.color.primary_light)
    protected val success = ContextCompat.getColor(context, R.color.success_light)
    protected val error = ContextCompat.getColor(context, R.color.error)
    protected val info = ContextCompat.getColor(context, R.color.info)
    protected val muted = ContextCompat.getColor(context, R.color.text_muted)
    protected val secondary = ContextCompat.getColor(context, R.color.text_secondary)
    protected val divider = ContextCompat.getColor(context, R.color.divider)
    protected val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    protected fun emptyState(canvas: Canvas, text: String) {
        labelPaint.color = muted
        canvas.drawText(text, width / 2f - labelPaint.measureText(text) / 2f, height / 2f, labelPaint)
        labelPaint.color = secondary
    }

    protected fun chartBounds(): RectF {
        val density = resources.displayMetrics.density
        return RectF(12f * density, 12f * density, width - 12f * density, height - 30f * density)
    }
}

class StatusBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DashboardChartView(context, attrs) {
    private val counts = intArrayOf(0, 0, 0, 0)
    private val names = arrayOf("Pending", "Printing", "Done", "Failed")
    private val colors: IntArray
        get() = intArrayOf(info, primaryLight, success, error)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setCounts(pending: Int, printing: Int, done: Int, failed: Int) {
        counts[0] = pending
        counts[1] = printing
        counts[2] = done
        counts[3] = failed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = counts.sum()
        if (total == 0) {
            emptyState(canvas, "No job activity yet")
            return
        }

        val bounds = chartBounds()
        val maxCount = max(1, counts.maxOrNull() ?: 1)
        val slotWidth = bounds.width() / counts.size
        val density = resources.displayMetrics.density
        val labelBaseline = height - 8f * density
        for (index in counts.indices) {
            val barHeight = bounds.height() * counts[index] / maxCount
            val left = bounds.left + slotWidth * index + slotWidth * 0.25f
            val right = bounds.left + slotWidth * index + slotWidth * 0.75f
            val top = bounds.bottom - barHeight
            barPaint.color = colors[index]
            canvas.drawRoundRect(RectF(left, top, right, bounds.bottom), 6f * density, 6f * density, barPaint)
            labelPaint.color = secondary
            canvas.drawText(names[index], left, labelBaseline, labelPaint)
            labelPaint.color = muted
            val value = counts[index].toString()
            canvas.drawText(value, (left + right) / 2f - labelPaint.measureText(value) / 2f, top - 6f * density, labelPaint)
        }
    }
}

class ConnectionGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DashboardChartView(context, attrs) {
    private var connectionState = NiimbotBluetoothManager.STATE_DISCONNECTED
    private var hasJobs = false
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun setConnectionState(state: Int) {
        connectionState = state
        invalidate()
    }

    fun setHasJobs(value: Boolean) {
        hasJobs = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height - 18f * density
        val radius = (width / 2f - 28f * density).coerceAtMost(height - 36f * density)
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        arcPaint.strokeWidth = 16f * density
        arcPaint.strokeCap = Paint.Cap.ROUND
        arcPaint.color = divider
        canvas.drawArc(rect, 180f, -180f, false, arcPaint)

        val sweep = when (connectionState) {
            NiimbotBluetoothManager.STATE_CONNECTED -> 180f
            NiimbotBluetoothManager.STATE_CONNECTING -> 90f
            else -> 0f
        }
        if (sweep > 0f) {
            arcPaint.color = if (connectionState == NiimbotBluetoothManager.STATE_CONNECTED) success else ContextCompat.getColor(context, R.color.warning)
            canvas.drawArc(rect, 180f, -sweep, false, arcPaint)
        }

        val status = when (connectionState) {
            NiimbotBluetoothManager.STATE_CONNECTED -> "Ready"
            NiimbotBluetoothManager.STATE_CONNECTING -> "Connecting"
            else -> "Offline"
        }
        labelPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        labelPaint.textSize = 20f * resources.displayMetrics.scaledDensity
        labelPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText(status, centerX - labelPaint.measureText(status) / 2f, centerY - 10f * density, labelPaint)
        labelPaint.color = secondary
        labelPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        labelPaint.typeface = android.graphics.Typeface.DEFAULT
        val detail = if (hasJobs) "Queue is active" else "No jobs in queue"
        canvas.drawText(detail, centerX - labelPaint.measureText(detail) / 2f, centerY + 14f * density, labelPaint)
    }
}

class RecentTrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DashboardChartView(context, attrs) {
    private var jobs: List<PrintJob> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setJobs(value: List<PrintJob>) {
        jobs = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (jobs.isEmpty()) {
            emptyState(canvas, "No recent jobs")
            return
        }

        val density = resources.displayMetrics.density
        val bounds = chartBounds()
        val now = System.currentTimeMillis()
        val totalBuckets = IntArray(6)
        val doneBuckets = IntArray(6)
        jobs.forEach { job ->
            val ageHours = ((now - job.createdAt.time).coerceAtLeast(0L) / 3_600_000L).toInt()
            if (ageHours < 24) {
                val bucket = 5 - (ageHours / 4).coerceIn(0, 5)
                totalBuckets[bucket]++
                if (job.status == PrintStatus.DONE) doneBuckets[bucket]++
            }
        }

        if (totalBuckets.sum() == 0) {
            emptyState(canvas, "No jobs in the last 24 hours")
            return
        }

        val maxCount = max(1, totalBuckets.maxOrNull() ?: 1)
        val step = bounds.width() / (totalBuckets.size - 1)
        drawLine(canvas, bounds, totalBuckets, maxCount, step, primary)
        drawLine(canvas, bounds, doneBuckets, maxCount, step, success)

        labelPaint.color = secondary
        labelPaint.textSize = 10f * resources.displayMetrics.scaledDensity
        for (index in totalBuckets.indices) {
            val label = if (index == totalBuckets.lastIndex) "Now" else "-${(5 - index) * 4}h"
            val x = bounds.left + step * index
            canvas.drawText(label, x - labelPaint.measureText(label) / 2f, height - 8f * density, labelPaint)
        }
        labelPaint.textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private fun drawLine(canvas: Canvas, bounds: RectF, values: IntArray, maxCount: Int, step: Float, color: Int) {
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = bounds.left + step * index
            val y = bounds.bottom - bounds.height() * value / maxCount
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.color = color
        canvas.drawPath(path, linePaint)
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        values.forEachIndexed { index, value ->
            val x = bounds.left + step * index
            val y = bounds.bottom - bounds.height() * value / maxCount
            canvas.drawCircle(x, y, 4f * resources.displayMetrics.density, pointPaint)
        }
    }
}
