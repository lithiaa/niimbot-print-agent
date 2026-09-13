package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var sortButton: MaterialButton
    private lateinit var filterButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyView: TextView
    private lateinit var summaryView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var pageView: TextView
    private lateinit var paginationView: View
    private lateinit var adapter: ProductInfoAdapter
    private var products: List<PosProduct> = emptyList()
    private var totalProducts = 0
    private var currentPage = 1
    private var currentPageSize = PAGE_SIZE
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var selectedFilter = ProductStockFilter.ALL
    private var selectedSort = ProductSort.NAME_ASC

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_product_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchInput = view.findViewById(R.id.et_product_search)
        sortButton = view.findViewById(R.id.btn_product_sort)
        filterButton = view.findViewById(R.id.btn_product_filter)
        recyclerView = view.findViewById(R.id.rv_product_info)
        swipeRefresh = view.findViewById(R.id.swipe_product_info)
        emptyView = view.findViewById(R.id.tv_product_info_empty)
        summaryView = view.findViewById(R.id.tv_product_info_summary)
        progressBar = view.findViewById(R.id.progress_product_info)
        previousButton = view.findViewById(R.id.btn_previous_products)
        nextButton = view.findViewById(R.id.btn_next_products)
        pageView = view.findViewById(R.id.tv_product_page)
        paginationView = view.findViewById(R.id.product_pagination)

        adapter = ProductInfoAdapter(::openDetail, ::openLabel)
        val spanCount = if (resources.configuration.smallestScreenWidthDp >= 600) 2 else 1
        recyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        recyclerView.adapter = adapter

        updateActionDescriptions()

        swipeRefresh.setOnRefreshListener { loadProducts(currentPage) }
        sortButton.setOnClickListener { showSortDialog() }
        filterButton.setOnClickListener { showFilterDialog() }
        previousButton.setOnClickListener { loadProducts(currentPage - 1) }
        nextButton.setOnClickListener { loadProducts(currentPage + 1) }
        searchInput.doAfterTextChanged {
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(350)
                loadProducts(1, initial = true)
            }
        }
        loadProducts(1, initial = true)
    }

    private fun showFilterDialog() {
        val options = listOf(
            ProductStockFilter.ALL to getString(R.string.product_filter_all),
            ProductStockFilter.SAFE to getString(R.string.product_filter_safe),
            ProductStockFilter.LOW to getString(R.string.product_filter_low),
            ProductStockFilter.OUT_OF_STOCK to getString(R.string.product_filter_out)
        )
        val selectedIndex = options.indexOfFirst { it.first == selectedFilter }.coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.product_filter_hint)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                selectedFilter = options[position].first
                updateActionDescriptions()
                dialog.dismiss()
                loadProducts(1, initial = true)
            }
        }
        dialog.show()
    }

    private fun showSortDialog() {
        val options = listOf(
            ProductSort.NAME_ASC to getString(R.string.product_sort_name_asc),
            ProductSort.NAME_DESC to getString(R.string.product_sort_name_desc),
            ProductSort.STOCK_ASC to getString(R.string.product_sort_stock_asc),
            ProductSort.STOCK_DESC to getString(R.string.product_sort_stock_desc),
            ProductSort.SELLING_PRICE_ASC to getString(R.string.product_sort_price_asc),
            ProductSort.SELLING_PRICE_DESC to getString(R.string.product_sort_price_desc)
        )
        val selectedIndex = options.indexOfFirst { it.first == selectedSort }.coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.product_sort_title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selectedIndex, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                selectedSort = options[position].first
                updateActionDescriptions()
                applyCurrentSort()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateActionDescriptions() {
        val filterName = when (selectedFilter) {
            ProductStockFilter.ALL -> R.string.product_filter_all
            ProductStockFilter.SAFE -> R.string.product_filter_safe
            ProductStockFilter.LOW -> R.string.product_filter_low
            ProductStockFilter.OUT_OF_STOCK -> R.string.product_filter_out
        }
        val sortName = when (selectedSort) {
            ProductSort.NAME_ASC -> R.string.product_sort_name_asc
            ProductSort.NAME_DESC -> R.string.product_sort_name_desc
            ProductSort.STOCK_ASC -> R.string.product_sort_stock_asc
            ProductSort.STOCK_DESC -> R.string.product_sort_stock_desc
            ProductSort.SELLING_PRICE_ASC -> R.string.product_sort_price_asc
            ProductSort.SELLING_PRICE_DESC -> R.string.product_sort_price_desc
        }
        filterButton.contentDescription = getString(
            R.string.product_filter_selected_description,
            getString(filterName)
        )
        sortButton.contentDescription = getString(
            R.string.product_sort_selected_description,
            getString(sortName)
        )
        TooltipCompat.setTooltipText(filterButton, filterButton.contentDescription)
        TooltipCompat.setTooltipText(sortButton, sortButton.contentDescription)
    }

    private fun openDetail(product: PosProduct) {
        val productId = product.id ?: return
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProductDetailFragment.newInstance(productId))
            .addToBackStack("product_detail_$productId")
            .commit()
    }

    private fun openLabel(product: PosProduct) {
        parentFragmentManager.setFragmentResult(
            LabelPrefillContract.REQUEST_KEY,
            LabelPrefillContract.toBundle(product)
        )
        (activity as? MainActivity)?.selectLabelTab()
    }

    private fun loadProducts(page: Int, initial: Boolean = false) {
        val accessToken = configStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            showEmpty(getString(R.string.pos_login_required))
            return
        }
        loadJob?.cancel()
        val requestedPage = page.coerceAtLeast(1)
        if (initial) {
            products = emptyList()
            adapter.submitList(emptyList())
        }
        setLoading(true, initial)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            when (
                val result = posApiClient.listProducts(
                    configStore.getBaseUrl(),
                    accessToken,
                    query = searchInput.text.toString(),
                    stockStatus = selectedFilter.apiValue,
                    page = requestedPage,
                    limit = PAGE_SIZE
                )
            ) {
                is PosApiResult.Success -> {
                    val response = result.value
                    currentPage = response.page.coerceAtLeast(1)
                    currentPageSize = response.limit.coerceAtLeast(1)
                    totalProducts = response.total.coerceAtLeast(0)
                    products = response.data
                    applyCurrentSort()
                }
                PosApiResult.NotFound -> {
                    currentPage = requestedPage
                    currentPageSize = PAGE_SIZE
                    totalProducts = 0
                    products = emptyList()
                    applyCurrentSort()
                }
                PosApiResult.SessionExpired -> {
                    configStore.clearSession()
                    showEmpty(getString(R.string.pos_session_expired))
                }
                is PosApiResult.Failure -> {
                    if (products.isEmpty()) {
                        showEmpty(result.message)
                    } else {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            setLoading(false, initial)
        }
    }

    private fun applyCurrentSort() {
        val sortedProducts = ProductInfoSort.apply(
            ProductInfoFilter.apply(products, selectedFilter),
            selectedSort
        )
        adapter.submitList(sortedProducts)
        val firstItem = if (sortedProducts.isEmpty()) 0 else ((currentPage - 1) * currentPageSize) + 1
        val lastItem = if (sortedProducts.isEmpty()) 0 else firstItem + sortedProducts.size - 1
        summaryView.text = getString(R.string.product_info_page_count, firstItem, lastItem, totalProducts)
        emptyView.text = getString(
            if (selectedFilter == ProductStockFilter.ALL) {
                R.string.product_info_empty
            } else {
                R.string.product_info_filter_empty
            }
        )
        emptyView.visibility = if (sortedProducts.isEmpty()) View.VISIBLE else View.GONE
        updatePagination()
    }

    private fun updatePagination() {
        val totalPages = totalPages()
        pageView.text = getString(R.string.product_page_indicator, currentPage, totalPages)
        previousButton.isEnabled = currentPage > 1
        nextButton.isEnabled = currentPage < totalPages
        paginationView.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        products = emptyList()
        adapter.submitList(emptyList())
        currentPage = 1
        currentPageSize = PAGE_SIZE
        totalProducts = 0
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        updatePagination()
        setLoading(false, initial = true)
    }

    private fun setLoading(loading: Boolean, initial: Boolean) {
        progressBar.visibility = if (loading && initial) View.VISIBLE else View.GONE
        swipeRefresh.isRefreshing = loading && !initial
        previousButton.isEnabled = !loading && currentPage > 1
        nextButton.isEnabled = !loading && currentPage < totalPages()
        sortButton.isEnabled = !loading
        filterButton.isEnabled = !loading
    }

    private fun totalPages(): Int = if (totalProducts == 0) {
        1
    } else {
        (totalProducts + currentPageSize - 1) / currentPageSize
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

private class ProductInfoAdapter(
    private val onDetail: (PosProduct) -> Unit,
    private val onPrint: (PosProduct) -> Unit
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
        private val print: View = view.findViewById(R.id.btn_print_product)

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
            print.setOnClickListener { onPrint(product) }
        }
    }
}
