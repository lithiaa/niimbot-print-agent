package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintLog
import com.niimbot.printagent.data.LogAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LogsFragment : Fragment() {

    private lateinit var database: AppDatabase
    private var rvLogs: RecyclerView? = null
    private var tvEmpty: TextView? = null
    private var logAdapter: LogAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getInstance(requireContext())
        
        rvLogs = view.findViewById(R.id.rv_logs)
        tvEmpty = view.findViewById(R.id.tv_empty)
        
        rvLogs?.layoutManager = LinearLayoutManager(requireContext())
        logAdapter = LogAdapter()
        rvLogs?.adapter = logAdapter
        
        observeLogs()
    }
    
    private fun observeLogs() {
        database.printLogDao().getAllPaged(100, 0).observe(viewLifecycleOwner) { logs ->
            logAdapter?.submitList(logs ?: emptyList())
            tvEmpty?.visibility = if (logs?.isNotEmpty() == true) View.GONE else View.VISIBLE
        }
    }
}

// ===================== Log Adapter =====================

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
    
    private var logs: List<PrintLog> = emptyList()
    
    fun submitList(newLogs: List<PrintLog>) {
        logs = newLogs
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]
        holder.bind(log)
    }
    
    override fun getItemCount(): Int = logs.size
    
    inner class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAction: TextView = view.findViewById(R.id.tv_log_action)
        private val tvJobId: TextView = view.findViewById(R.id.tv_log_job_id)
        private val tvMessage: TextView = view.findViewById(R.id.tv_log_message)
        private val tvTime: TextView = view.findViewById(R.id.tv_log_time)
        
        fun bind(log: PrintLog) {
            // Action with icon
            val actionIcon = when (log.action) {
                LogAction.QUEUED -> "📝 "
                LogAction.PRINTING_STARTED -> "🖨 "
                LogAction.PRINTING_COMPLETED -> "✅ "
                LogAction.PRINTING_FAILED -> "❌ "
                LogAction.RECONNECT_ATTEMPT -> "🔄 "
                LogAction.RECONNECT_SUCCESS -> "🟢 "
                LogAction.RECONNECT_FAILED -> "🔴 "
                LogAction.QUEUE_CLEARED -> "🗑 "
            }
            tvAction.text = "$actionIcon${log.action}"
            
            tvJobId.text = "Job #${log.printJobId}"
            tvMessage.text = log.message ?: log.errorDetail ?: "—"
            tvTime.text = formatTime(log.createdAt)
            
            // Color based on action
            val color = when (log.action) {
                LogAction.PRINTING_COMPLETED -> android.graphics.Color.parseColor("#10B981")
                LogAction.PRINTING_FAILED, LogAction.RECONNECT_FAILED -> android.graphics.Color.parseColor("#EF4444")
                LogAction.PRINTING_STARTED -> android.graphics.Color.parseColor("#3B82F6")
                else -> android.graphics.Color.parseColor("#1F2937")
            }
            tvAction.setTextColor(color)
        }
    }
    
    private fun formatTime(date: java.util.Date): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss dd/MM", java.util.Locale.getDefault())
        return sdf.format(date)
    }
}