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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.data.AppDatabase
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PrintQueueFragment : Fragment() {

    private lateinit var database: AppDatabase
    private var rvJobs: RecyclerView? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var tvEmpty: TextView? = null
    private var jobAdapter: JobAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_print_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        database = AppDatabase.getInstance(requireContext())
        
        rvJobs = view.findViewById(R.id.rv_jobs)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        tvEmpty = view.findViewById(R.id.tv_empty)
        
        rvJobs?.layoutManager = LinearLayoutManager(requireContext())
        jobAdapter = JobAdapter()
        rvJobs?.adapter = jobAdapter
        
        swipeRefresh?.setOnRefreshListener { refreshJobs() }
        
        observeJobs()
    }
    
    private fun observeJobs() {
        database.printJobDao().getByStatuses(listOf(
            PrintStatus.PENDING,
            PrintStatus.PRINTING,
            PrintStatus.FAILED,
            PrintStatus.DONE
        )).observe(viewLifecycleOwner) { jobs ->
            jobAdapter?.submitList(jobs ?: emptyList())
            tvEmpty?.visibility = if (jobs?.isNotEmpty() == true) View.GONE else View.VISIBLE
            swipeRefresh?.isRefreshing = false
        }
    }
    
    private fun refreshJobs() {
        // Room LiveData auto-updates, just trigger a refresh
        swipeRefresh?.isRefreshing = false
    }
}

// ===================== Job Adapter =====================

class JobAdapter : RecyclerView.Adapter<JobAdapter.JobViewHolder>() {
    
    private var jobs: List<PrintJob> = emptyList()
    
    fun submitList(newJobs: List<PrintJob>) {
        jobs = newJobs
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): JobViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_print_job, parent, false)
        return JobViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        val job = jobs[position]
        holder.bind(job)
    }
    
    override fun getItemCount(): Int = jobs.size
    
    inner class JobViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvNama: TextView = view.findViewById(R.id.tv_job_nama)
        private val tvSku: TextView = view.findViewById(R.id.tv_job_sku)
        private val tvHarga: TextView = view.findViewById(R.id.tv_job_harga)
        private val tvQty: TextView = view.findViewById(R.id.tv_job_qty)
        private val tvStatus: TextView = view.findViewById(R.id.tv_job_status)
        private val tvTime: TextView = view.findViewById(R.id.tv_job_time)
        private val tvRetry: TextView = view.findViewById(R.id.tv_job_retry)
        
        fun bind(job: PrintJob) {
            tvNama.text = job.nama
            tvSku.text = "SKU: ${job.sku}"
            tvHarga.text = "Rp ${formatRupiah(job.hargaJual)}"
            tvQty.text = "Qty: ${job.qty}"
            
            // Status with color
            when (job.status) {
                PrintStatus.PENDING -> {
                    tvStatus.text = "⏳ Pending"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B")) // Amber
                }
                PrintStatus.PRINTING -> {
                    tvStatus.text = "🖨 Printing"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#3B82F6")) // Blue
                }
                PrintStatus.DONE -> {
                    tvStatus.text = "✅ Done"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#10B981")) // Green
                }
                PrintStatus.FAILED -> {
                    tvStatus.text = "❌ Failed"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#EF4444")) // Red
                }
                PrintStatus.CANCELLED -> {
                    tvStatus.text = "🚫 Cancelled"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#6B7280")) // Gray
                }
            }
            
            tvTime.text = formatTime(job.createdAt)
            
            if (job.retryCount > 0) {
                tvRetry.text = "Retry: ${job.retryCount}/3"
                tvRetry.visibility = View.VISIBLE
            } else {
                tvRetry.visibility = View.GONE
            }
            
            if (job.errorMessage != null) {
                tvRetry.text = "${tvRetry.text} - ${job.errorMessage}"
                tvRetry.visibility = View.VISIBLE
            }
        }
    }
    
    private fun formatRupiah(amount: Long): String {
        return java.text.NumberFormat.getNumberInstance(java.util.Locale.GERMANY).format(amount)
    }
    
    private fun formatTime(date: java.util.Date): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss dd/MM", java.util.Locale.getDefault())
        return sdf.format(date)
    }
}