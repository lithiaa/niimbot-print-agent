package com.niimbot.printagent.ui

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosSupplierOrder
import com.niimbot.printagent.pos.PosSupplierOrderInput
import com.niimbot.printagent.pos.PosSupplierOrderRules
import com.niimbot.printagent.pos.PosSupplierOrderStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SupplierOrdersFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var searchInput: EditText
    private lateinit var addButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private lateinit var errorPanel: View
    private lateinit var errorView: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var adapter: SupplierOrderAdapter
    private var orders: List<PosSupplierOrder> = emptyList()
    private var requestJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_supplier_orders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchInput = view.findViewById(R.id.et_supplier_order_search)
        addButton = view.findViewById(R.id.btn_add_supplier_order)
        recyclerView = view.findViewById(R.id.rv_supplier_orders)
        swipeRefresh = view.findViewById(R.id.swipe_supplier_orders)
        progress = view.findViewById(R.id.progress_supplier_orders)
        emptyView = view.findViewById(R.id.tv_supplier_orders_empty)
        summaryView = view.findViewById(R.id.tv_supplier_orders_summary)
        errorPanel = view.findViewById(R.id.panel_supplier_orders_error)
        errorView = view.findViewById(R.id.tv_supplier_orders_error)
        retryButton = view.findViewById(R.id.btn_retry_supplier_orders)

        adapter = SupplierOrderAdapter(
            onEdit = ::loadOrderForEdit,
            onDelete = ::confirmDelete
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btn_supplier_orders_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        addButton.setOnClickListener { showOrderForm(null) }
        TooltipCompat.setTooltipText(addButton, getString(R.string.supplier_order_add))
        retryButton.setOnClickListener { loadOrders(initial = true) }
        swipeRefresh.setOnRefreshListener { loadOrders(initial = false) }
        searchInput.doAfterTextChanged { renderOrders() }

        loadOrders(initial = true)
    }

    private fun loadOrders(initial: Boolean) {
        val token = configStore.getAccessToken()
        if (token.isNullOrBlank()) {
            showError(getString(R.string.pos_login_required))
            return
        }
        requestJob?.cancel()
        setLoading(true, initial)
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = posApiClient.listSupplierOrders(configStore.getBaseUrl(), token)) {
                is PosApiResult.Success -> {
                    orders = result.value.sortedByDescending { it.id }
                    renderOrders()
                }
                PosApiResult.NotFound -> {
                    orders = emptyList()
                    renderOrders()
                }
                PosApiResult.SessionExpired -> handleExpiredSession()
                is PosApiResult.Failure -> showError(result.message)
            }
            setLoading(false, initial)
        }
    }

    private fun renderOrders() {
        if (!::adapter.isInitialized) return
        val filtered = orders.filter { PosSupplierOrderRules.matchesName(it, searchInput.text.toString()) }
        adapter.submitList(filtered)
        errorPanel.visibility = View.GONE
        emptyView.text = getString(
            if (orders.isEmpty()) R.string.supplier_orders_empty
            else R.string.supplier_orders_search_empty
        )
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        summaryView.text = if (filtered.size == orders.size) {
            getString(R.string.supplier_orders_count, orders.size)
        } else {
            getString(R.string.supplier_orders_filtered_count, filtered.size, orders.size)
        }
    }

    private fun showError(message: String) {
        adapter.submitList(emptyList())
        emptyView.visibility = View.GONE
        errorView.text = message
        errorPanel.visibility = View.VISIBLE
        summaryView.text = getString(R.string.supplier_orders_title)
        setLoading(false, initial = true)
    }

    private fun setLoading(loading: Boolean, initial: Boolean) {
        progress.visibility = if (loading && initial) View.VISIBLE else View.GONE
        swipeRefresh.isRefreshing = loading && !initial
        addButton.isEnabled = !loading
        retryButton.isEnabled = !loading
        if (loading && initial) {
            emptyView.visibility = View.GONE
            errorPanel.visibility = View.GONE
        }
    }

    private fun loadOrderForEdit(order: PosSupplierOrder) {
        val token = configStore.getAccessToken() ?: return showError(getString(R.string.pos_login_required))
        requestJob?.cancel()
        setLoading(true, initial = false)
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.getSupplierOrder(
                    configStore.getBaseUrl(),
                    token,
                    order.id
                )
            ) {
                is PosApiResult.Success -> showOrderForm(result.value)
                PosApiResult.NotFound -> {
                    Toast.makeText(requireContext(), R.string.supplier_order_not_found, Toast.LENGTH_LONG).show()
                    loadOrders(initial = false)
                }
                PosApiResult.SessionExpired -> handleExpiredSession()
                is PosApiResult.Failure -> Toast.makeText(
                    requireContext(),
                    result.message,
                    Toast.LENGTH_LONG
                ).show()
            }
            setLoading(false, initial = false)
        }
    }

    private fun showOrderForm(order: PosSupplierOrder?) {
        val form = layoutInflater.inflate(R.layout.dialog_supplier_order, null)
        val nameLayout = form.findViewById<TextInputLayout>(R.id.layout_supplier_order_name)
        val quantityLayout = form.findViewById<TextInputLayout>(R.id.layout_supplier_order_quantity)
        val statusLayout = form.findViewById<TextInputLayout>(R.id.layout_supplier_order_status)
        val nameInput = form.findViewById<EditText>(R.id.et_supplier_order_name)
        val quantityInput = form.findViewById<EditText>(R.id.et_supplier_order_quantity)
        val statusInput = form.findViewById<AutoCompleteTextView>(R.id.act_supplier_order_status)
        val statusOptions = PosSupplierOrderStatus.entries
        val statusLabels = statusOptions.map(::statusLabel)
        statusInput.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, statusLabels))

        val selectedStatus = order?.let { PosSupplierOrderStatus.fromApi(it.status) }
            ?: PosSupplierOrderStatus.PENDING
        nameInput.setText(order?.namaBarang.orEmpty())
        quantityInput.setText(order?.jumlahBarang?.toString().orEmpty())
        statusInput.setText(statusLabel(selectedStatus), false)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (order == null) R.string.supplier_order_add_title else R.string.supplier_order_edit_title)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.supplier_order_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                quantityLayout.error = null
                statusLayout.error = null
                val rawName = nameInput.text.toString()
                val name = PosSupplierOrderRules.normalizeName(rawName)
                val quantity = PosSupplierOrderRules.parsePositiveQuantity(quantityInput.text.toString())
                val statusIndex = statusLabels.indexOf(statusInput.text.toString())
                val status = statusOptions.getOrNull(statusIndex)?.apiValue
                if (name == null) {
                    nameLayout.error = getString(
                        if (rawName.trim().isEmpty()) R.string.supplier_order_name_required
                        else R.string.supplier_order_name_too_long
                    )
                }
                if (quantity == null) {
                    quantityLayout.error = getString(R.string.supplier_order_quantity_invalid)
                }
                if (status == null) {
                    statusLayout.error = getString(R.string.supplier_order_status_invalid)
                }
                if (name == null || quantity == null || status == null) return@setOnClickListener
                saveOrder(dialog, order, PosSupplierOrderInput(name, quantity, status))
            }
        }
        dialog.showWithBoxedButtons()
    }

    private fun saveOrder(
        dialog: AlertDialog,
        order: PosSupplierOrder?,
        input: PosSupplierOrderInput
    ) {
        val token = configStore.getAccessToken()
        if (token.isNullOrBlank()) {
            dialog.dismiss()
            handleExpiredSession()
            return
        }
        val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val cancelButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
        requestJob?.cancel()
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = if (order == null) {
                posApiClient.createSupplierOrder(configStore.getBaseUrl(), token, input)
            } else {
                posApiClient.updateSupplierOrder(configStore.getBaseUrl(), token, order.id, input)
            }
            when (result) {
                is PosApiResult.Success -> {
                    dialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        if (order == null) R.string.supplier_order_create_success
                        else R.string.supplier_order_update_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadOrders(initial = false)
                }
                PosApiResult.NotFound -> {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), R.string.supplier_order_not_found, Toast.LENGTH_LONG).show()
                    loadOrders(initial = false)
                }
                PosApiResult.SessionExpired -> {
                    dialog.dismiss()
                    handleExpiredSession()
                }
                is PosApiResult.Failure -> {
                    saveButton.isEnabled = true
                    cancelButton.isEnabled = true
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete(order: PosSupplierOrder) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.supplier_order_delete_title)
            .setMessage(getString(R.string.supplier_order_delete_message, order.namaBarang))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.supplier_order_delete) { _, _ -> deleteOrder(order) }
            .showWithBoxedButtons()
    }

    private fun deleteOrder(order: PosSupplierOrder) {
        val token = configStore.getAccessToken() ?: return handleExpiredSession()
        requestJob?.cancel()
        setLoading(true, initial = false)
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.deleteSupplierOrder(
                    configStore.getBaseUrl(),
                    token,
                    order.id
                )
            ) {
                is PosApiResult.Success -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.supplier_order_delete_success,
                        Toast.LENGTH_SHORT
                    ).show()
                    loadOrders(initial = false)
                }
                PosApiResult.NotFound -> {
                    Toast.makeText(requireContext(), R.string.supplier_order_not_found, Toast.LENGTH_LONG).show()
                    loadOrders(initial = false)
                }
                PosApiResult.SessionExpired -> handleExpiredSession()
                is PosApiResult.Failure -> Toast.makeText(
                    requireContext(),
                    result.message,
                    Toast.LENGTH_LONG
                ).show()
            }
            setLoading(false, initial = false)
        }
    }

    private fun handleExpiredSession() {
        configStore.clearSession()
        showError(getString(R.string.pos_session_expired))
    }

    private fun statusLabel(status: PosSupplierOrderStatus): String = getString(
        when (status) {
            PosSupplierOrderStatus.PENDING -> R.string.supplier_order_status_pending
            PosSupplierOrderStatus.ORDERED -> R.string.supplier_order_status_ordered
            PosSupplierOrderStatus.RECEIVED -> R.string.supplier_order_status_received
            PosSupplierOrderStatus.CANCELLED -> R.string.supplier_order_status_cancelled
        }
    )
}

private class SupplierOrderAdapter(
    private val onEdit: (PosSupplierOrder) -> Unit,
    private val onDelete: (PosSupplierOrder) -> Unit
) : ListAdapter<PosSupplierOrder, SupplierOrderAdapter.OrderViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_supplier_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) = holder.bind(getItem(position))

    inner class OrderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tv_supplier_order_name)
        private val quantity: TextView = view.findViewById(R.id.tv_supplier_order_quantity)
        private val status: TextView = view.findViewById(R.id.tv_supplier_order_status)
        private val delete: View = view.findViewById(R.id.btn_delete_supplier_order)

        fun bind(order: PosSupplierOrder) {
            name.text = order.namaBarang
            quantity.text = itemView.context.getString(
                R.string.supplier_order_quantity_value,
                order.jumlahBarang
            )
            val statusValue = PosSupplierOrderStatus.fromApi(order.status)
            status.text = itemView.context.getString(
                when (statusValue) {
                    PosSupplierOrderStatus.PENDING -> R.string.supplier_order_status_pending
                    PosSupplierOrderStatus.ORDERED -> R.string.supplier_order_status_ordered
                    PosSupplierOrderStatus.RECEIVED -> R.string.supplier_order_status_received
                    PosSupplierOrderStatus.CANCELLED -> R.string.supplier_order_status_cancelled
                    null -> R.string.supplier_order_status_pending
                }
            )
            val color = ContextCompat.getColor(
                itemView.context,
                when (statusValue) {
                    PosSupplierOrderStatus.PENDING -> R.color.warning
                    PosSupplierOrderStatus.ORDERED -> R.color.info
                    PosSupplierOrderStatus.RECEIVED -> R.color.success
                    PosSupplierOrderStatus.CANCELLED -> R.color.error
                    null -> R.color.text_muted
                }
            )
            status.backgroundTintList = ColorStateList.valueOf(color)
            status.setTextColor(ContextCompat.getColor(itemView.context, R.color.white))
            itemView.setOnClickListener { onEdit(order) }
            delete.setOnClickListener { onDelete(order) }
            TooltipCompat.setTooltipText(delete, itemView.context.getString(R.string.supplier_order_delete))
        }
    }

    private companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PosSupplierOrder>() {
            override fun areItemsTheSame(oldItem: PosSupplierOrder, newItem: PosSupplierOrder): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PosSupplierOrder, newItem: PosSupplierOrder): Boolean =
                oldItem == newItem
        }
    }
}
