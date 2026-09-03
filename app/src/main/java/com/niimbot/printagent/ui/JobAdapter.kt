package com.niimbot.printagent.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.niimbot.printagent.R
import com.niimbot.printagent.data.PrintJob
import com.niimbot.printagent.data.PrintStatus
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

class JobAdapter : RecyclerView.Adapter<JobAdapter.JobViewHolder>() {
    private var jobs: List<PrintJob> = emptyList()

    fun submitList(newJobs: List<PrintJob>) {
        jobs = newJobs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_print_job, parent, false)
        return JobViewHolder(view)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) = holder.bind(jobs[position])

    override fun getItemCount(): Int = jobs.size

    inner class JobViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tv_job_nama)
        private val sku: TextView = view.findViewById(R.id.tv_job_sku)
        private val price: TextView = view.findViewById(R.id.tv_job_harga)
        private val qty: TextView = view.findViewById(R.id.tv_job_qty)
        private val status: TextView = view.findViewById(R.id.tv_job_status)
        private val time: TextView = view.findViewById(R.id.tv_job_time)
        private val retry: TextView = view.findViewById(R.id.tv_job_retry)

        fun bind(job: PrintJob) {
            name.text = job.nama
            sku.text = "SKU: ${job.sku}"
            price.text = "Rp ${NumberFormat.getNumberInstance(Locale.GERMANY).format(job.hargaJual)}"
            qty.text = "Jumlah: ${job.qty}"
            when (job.status) {
                PrintStatus.PENDING -> setStatus("Menunggu", "#F59E0B")
                PrintStatus.PRINTING -> setStatus("Sedang dicetak", "#3B82F6")
                PrintStatus.DONE -> setStatus("Selesai", "#10B981")
                PrintStatus.FAILED -> setStatus("Gagal", "#EF4444")
                PrintStatus.CANCELLED -> setStatus("Dibatalkan", "#6B7280")
            }
            time.text = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(job.createdAt)
            retry.visibility = if (job.retryCount > 0 || job.errorMessage != null) View.VISIBLE else View.GONE
            retry.text = buildString {
                if (job.retryCount > 0) append("Percobaan ulang: ${job.retryCount}/3")
                job.errorMessage?.let {
                    if (isNotEmpty()) append(" - ")
                    append(localizeLegacyPrintMessage(it))
                }
            }
        }

        private fun setStatus(label: String, color: String) {
            status.text = label
            status.setTextColor(Color.parseColor(color))
        }
    }
}
