package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosProduct
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProductInfoFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private lateinit var searchInput: EditText
    private lateinit var filterDropdown: AutoCompleteTextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadMoreButton: Button
    private lateinit var adapter: ProductInfoAdapter
    private var products: List<PosProduct> = emptyList()
    private var totalProducts = 0
    private var currentPage = 1
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var selectedFilter = ProductStockFilter.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_product_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchInput = view.findViewById(R.id.et_product_search)
        filterDropdown = view.findViewById(R.id.dropdown_product_filter)
        recyclerView = view.findViewById(R.id.rv_product_info)
        swipeRefresh = view.findViewById(R.id.swipe_product_info)
        emptyView = view.findViewById(R.id.tv_product_info_empty)
        summaryView = view.findViewById(R.id.tv_product_info_summary)
        progressBar = view.findViewById(R.id.progress_product_info)
        loadMoreButton = view.findViewById(R.id.btn_load_more_products)

        adapter = ProductInfoAdapter(::openDetail)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupFilterDropdown()

        swipeRefresh.setOnRefreshListener { loadProducts(reset = true) }
        loadMoreButton.setOnClickListener { loadProducts(reset = false) }
        searchInput.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(350)
                loadProducts(reset = true)
            }
        }
        loadProducts(reset = true)
    }

    private fun setupFilterDropdown() {
        val options = listOf(
            ProductStockFilter.ALL to getString(R.string.product_filter_all),
            ProductStockFilter.SAFE to getString(R.string.product_filter_safe),
            ProductStockFilter.LOW to getString(R.string.product_filter_low),
            ProductStockFilter.OUT_OF_STOCK to getString(R.string.product_filter_out)
        )
        filterDropdown.setAdapter(
            ArrayAdapter(
                requireContext(),
                R.layout.item_label_dropdown,
                options.map { it.second }
            )
        )
        filterDropdown.setText(options.first().second, false)
        filterDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFilter = options[position].first
            applyCurrentFilter()
        }
    }

    private fun openDetail(product: PosProduct) {
        val productId = product.id ?: return
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProductDetailFragment.newInstance(productId))
            .addToBackStack("product_detail_$productId")
            .commit()
    }

    private fun loadProducts(reset: Boolean) {
        val key = configStore.getIntegrationKey()
        if (key.isNullOrBlank()) {
            showEmpty(getString(R.string.product_info_key_required))
            return
        }
        if (reset) {
            loadJob?.cancel()
            currentPage = 1
            products = emptyList()
            adapter.submitList(emptyList())
        } else {
            currentPage += 1
        }
        setLoading(true, reset)
        val requestedPage = currentPage
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.listProducts(
                    configStore.getBaseUrl(),
                    key,
                    query = searchInput.text.toString(),
                    page = requestedPage,
                    limit = PAGE_SIZE
                )
            ) {
                is PosApiResult.Success -> {
                    val page = result.value
                    totalProducts = page.total
                    products = if (reset) page.data else (products + page.data).distinctBy { it.id ?: it.sku }
                    applyCurrentFilter()
                    loadMoreButton.visibility = if (products.size < totalProducts) View.VISIBLE else View.GONE
                }
                PosApiResult.NotFound -> showEmpty(getString(R.string.product_info_empty))
                is PosApiResult.Failure -> {
                    if (!reset) currentPage = (requestedPage - 1).coerceAtLeast(1)
                    showEmpty(result.message)
                }
            }
            setLoading(false, reset)
        }
    }

    private fun applyCurrentFilter() {
        val filteredProducts = ProductInfoFilter.apply(products, selectedFilter)
        adapter.submitList(filteredProducts)
        summaryView.text = if (selectedFilter == ProductStockFilter.ALL) {
            getString(R.string.product_info_count, products.size, totalProducts)
        } else {
            getString(
                R.string.product_info_filtered_count,
                filteredProducts.size,
                products.size,
                totalProducts
            )
        }
        emptyView.text = getString(
            if (products.isEmpty()) R.string.product_info_empty else R.string.product_info_filter_empty
        )
        emptyView.visibility = if (filteredProducts.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        loadMoreButton.visibility = View.GONE
        setLoading(false, reset = true)
    }

    private fun setLoading(loading: Boolean, reset: Boolean) {
        progressBar.visibility = if (loading && reset) View.VISIBLE else View.GONE
        swipeRefresh.isRefreshing = loading && !reset
        loadMoreButton.isEnabled = !loading
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

private class ProductInfoAdapter(
    private val onDetail: (PosProduct) -> Unit
) : RecyclerView.Adapter<ProductInfoAdapter.ProductViewHolder>() {
    private var products: List<PosProduct> = emptyList()
    private val currency = NumberFormat.getNumberInstance(Locale("id", "ID"))

    fun submitList(items: List<PosProduct>) {
        products = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_product_info, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) = holder.bind(products[position])

    override fun getItemCount(): Int = products.size

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tv_product_name)
        private val sku: TextView = view.findViewById(R.id.tv_product_sku)
        private val price: TextView = view.findViewById(R.id.tv_product_price)
        private val stock: TextView = view.findViewById(R.id.tv_product_stock)
        private val detail: View = view.findViewById(R.id.btn_edit_product)

        fun bind(product: PosProduct) {
            name.text = product.nama
            sku.text = itemView.context.getString(R.string.product_sku_value, product.sku)
            price.text = itemView.context.getString(
                R.string.product_price_summary,
                currency.format(product.hargaBeli),
                currency.format(product.hargaJual)
            )
            stock.text = itemView.context.getString(R.string.product_stock_value, product.stok, product.satuan)
            itemView.setOnClickListener { onDetail(product) }
            detail.setOnClickListener { onDetail(product) }
        }
    }
}
