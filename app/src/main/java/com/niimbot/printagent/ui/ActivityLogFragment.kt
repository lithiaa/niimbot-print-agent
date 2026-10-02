package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.niimbot.printagent.R
import com.niimbot.printagent.label.LabelDate
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosActivityLog
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@AndroidEntryPoint
class ActivityLogFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var pageView: TextView
    private lateinit var adapter: ActivityLogAdapter
    private var currentPage = 1
    private var totalLogs = 0
    private var loadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_activity_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.rv_activity_logs)
        swipeRefresh = view.findViewById(R.id.swipe_activity_logs)
        progress = view.findViewById(R.id.progress_activity_logs)
        emptyView = view.findViewById(R.id.tv_activity_logs_empty)
        summaryView = view.findViewById(R.id.tv_activity_logs_summary)
        previousButton = view.findViewById(R.id.btn_previous_activity_logs)
        nextButton = view.findViewById(R.id.btn_next_activity_logs)
        pageView = view.findViewById(R.id.tv_activity_log_page)
        adapter = ActivityLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btn_activity_logs_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        swipeRefresh.setOnRefreshListener { loadLogs(currentPage) }
        previousButton.setOnClickListener { loadLogs(currentPage - 1, initial = true) }
        nextButton.setOnClickListener { loadLogs(currentPage + 1, initial = true) }
        loadLogs(1, initial = true)
    }

    private fun loadLogs(page: Int, initial: Boolean = false) {
        val accessToken = configStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            showEmpty(getString(R.string.pos_login_required))
            return
        }
        val requestedPage = page.coerceAtLeast(1)
        loadJob?.cancel()
        setLoading(true, initial)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.listActivityLogs(
                    configStore.getBaseUrl(),
                    accessToken,
                    page = requestedPage,
                    limit = PAGE_SIZE
                )
            ) {
                is PosApiResult.Success -> {
                    currentPage = result.value.page.coerceAtLeast(1)
                    totalLogs = result.value.total.coerceAtLeast(0)
                    adapter.submitList(result.value.data)
                    renderSummary(result.value.data.size)
                }
                PosApiResult.NotFound -> showEmpty(getString(R.string.activity_logs_empty))
                PosApiResult.SessionExpired -> {
                    configStore.clearSession()
                    showEmpty(getString(R.string.pos_session_expired))
                }
                is PosApiResult.Failure -> {
                    if (adapter.itemCount == 0) showEmpty(result.message)
                    else Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
            setLoading(false, initial)
        }
    }

    private fun renderSummary(itemCount: Int) {
        val first = if (itemCount == 0) 0 else ((currentPage - 1) * PAGE_SIZE) + 1
        val last = if (itemCount == 0) 0 else first + itemCount - 1
        summaryView.text = getString(R.string.activity_logs_count, first, last, totalLogs)
        emptyView.text = getString(R.string.activity_logs_empty)
        emptyView.visibility = if (itemCount == 0) View.VISIBLE else View.GONE
        updatePagination()
    }

    private fun showEmpty(message: String) {
        adapter.submitList(emptyList())
        currentPage = 1
        totalLogs = 0
        summaryView.text = getString(R.string.activity_logs_count, 0, 0, 0)
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        updatePagination()
        setLoading(false, initial = true)
    }

    private fun updatePagination() {
        val totalPages = if (totalLogs == 0) 1 else (totalLogs + PAGE_SIZE - 1) / PAGE_SIZE
        pageView.text = getString(R.string.product_page_indicator, currentPage, totalPages)
        previousButton.isEnabled = currentPage > 1
        nextButton.isEnabled = currentPage < totalPages
    }

    private fun setLoading(loading: Boolean, initial: Boolean) {
        progress.visibility = if (loading && initial) View.VISIBLE else View.GONE
        swipeRefresh.isRefreshing = loading && !initial
        val totalPages = if (totalLogs == 0) 1 else (totalLogs + PAGE_SIZE - 1) / PAGE_SIZE
        previousButton.isEnabled = !loading && currentPage > 1
        nextButton.isEnabled = !loading && currentPage < totalPages
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

private class ActivityLogAdapter : RecyclerView.Adapter<ActivityLogAdapter.LogViewHolder>() {
    private var logs: List<PosActivityLog> = emptyList()

    fun submitList(items: List<PosActivityLog>) {
        logs = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(logs[position])

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val action: TextView = view.findViewById(R.id.tv_activity_log_action)
        private val status: TextView = view.findViewById(R.id.tv_activity_log_status)
        private val meta: TextView = view.findViewById(R.id.tv_activity_log_meta)
        private val detail: TextView = view.findViewById(R.id.tv_activity_log_detail)

        fun bind(item: PosActivityLog) {
            val context = itemView.context
            val actionName = item.action.replace('_', ' ').lowercase(Locale("id", "ID"))
                .replaceFirstChar { it.titlecase(Locale("id", "ID")) }
            val resourceName = item.resource.replace('_', ' ')
            action.text = context.getString(R.string.activity_log_action_value, actionName, resourceName)
            status.text = context.getString(R.string.activity_log_status_value, item.statusCode)
            meta.text = context.getString(
                R.string.activity_log_meta_value,
                item.username?.takeIf { it.isNotBlank() } ?: context.getString(R.string.activity_log_system_user),
                formatTimestamp(item.createdAt)
            )
            detail.text = summaryText(item)
        }

        private fun summaryText(item: PosActivityLog): String {
            val safeSummary = item.summary.entries
                .filterNot { (key, _) -> SENSITIVE_KEYS.any { key.contains(it, ignoreCase = true) } }
                .joinToString(" · ") { (key, value) ->
                    "${key.replace('_', ' ')}: ${value.displayValue()}"
                }
                .take(MAX_SUMMARY_LENGTH)
            if (safeSummary.isNotBlank()) return safeSummary
            val resourceId = item.resourceId?.takeIf { it.isNotBlank() }?.let { " #$it" }.orEmpty()
            return "${item.httpMethod} ${item.path}$resourceId"
        }

        private fun JsonElement.displayValue(): String = when (this) {
            is JsonPrimitive -> content
            else -> toString()
        }

        private fun formatTimestamp(value: String): String {
            val date = LabelDate.fromTimestamp(value)?.let(LabelDate::display)
            val time = value.substringAfter('T', "").take(5).takeIf { it.length == 5 }
            return listOfNotNull(date, time).joinToString(" ").ifEmpty { value }
        }

        private companion object {
            const val MAX_SUMMARY_LENGTH = 300
            val SENSITIVE_KEYS = listOf("password", "token", "secret", "authorization")
        }
    }
}
