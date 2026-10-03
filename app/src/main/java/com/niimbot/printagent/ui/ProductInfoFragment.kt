package com.niimbot.printagent.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ProductInfoFragment : Fragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private val savedState: ProductInfoStateViewModel by activityViewModels()

    private lateinit var searchInput: EditText
    private lateinit var addButton: MaterialButton
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
        addButton = view.findViewById(R.id.btn_add_product)
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

        parentFragmentManager.setFragmentResultListener(
            ProductEditFragment.CREATE_RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            resetSavedListPosition()
            loadProducts(1, initial = true)
        }

        restoreStateValues()
        searchInput.setText(savedState.query)
        searchInput.setSelection(searchInput.text.length)

        adapter = ProductInfoAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            loadPhoto = { photoUrl ->
                when (val result = posApiClient.downloadProductPhoto(
                    configStore.getBaseUrl(),
                    configStore.getAccessToken().orEmpty(),
                    photoUrl
                )) {
                    is PosApiResult.Success -> withContext(Dispatchers.Default) {
                        ProductPhotoFiles.decodePreview(result.value, PRODUCT_THUMBNAIL_SIZE_PX)
                    }
                    else -> null
                }
            },
            onDetail = ::openDetail,
            onPrint = ::openLabel
        )
        val spanCount = if (resources.configuration.smallestScreenWidthDp >= 600) 2 else 1
        recyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        recyclerView.adapter = adapter

        updateActionDescriptions()

        swipeRefresh.setOnRefreshListener { loadProducts(currentPage) }
        addButton.setOnClickListener { openCreateProduct() }
        sortButton.setOnClickListener { showSortDialog() }
        filterButton.setOnClickListener { showFilterDialog() }
        previousButton.setOnClickListener { loadProducts(currentPage - 1) }
        nextButton.setOnClickListener { loadProducts(currentPage + 1) }
        searchInput.doAfterTextChanged {
            val query = it?.toString().orEmpty()
            if (query == savedState.query) return@doAfterTextChanged
            savedState.query = query
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(350)
                loadProducts(1, initial = true)
            }
        }
        if (savedState.initialized && !configStore.getAccessToken().isNullOrBlank()) {
            applyCurrentSort()
            restoreListPosition()
        } else {
            loadProducts(1, initial = true)
        }
    }

    override fun onPause() {
        captureState()
        super.onPause()
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
                savedState.selectedFilter = selectedFilter
                resetSavedListPosition()
                updateActionDescriptions()
                dialog.dismiss()
                loadProducts(1, initial = true)
            }
        }
        dialog.showWithBoxedButtons()
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
                savedState.selectedSort = selectedSort
                resetSavedListPosition()
                updateActionDescriptions()
                applyCurrentSort()
                recyclerView.scrollToPosition(0)
                dialog.dismiss()
            }
        }
        dialog.showWithBoxedButtons()
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
        captureState()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProductDetailFragment.newInstance(productId))
            .addToBackStack("product_detail_$productId")
            .commit()
    }

    private fun openLabel(product: PosProduct) {
        captureState()
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
        val resetPositionAfterLoad = initial || requestedPage != currentPage
        savedState.query = searchInput.text.toString()
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
                    if (resetPositionAfterLoad) resetSavedListPosition()
                    saveLoadedState()
                    applyCurrentSort()
                    restoreListPosition()
                }
                PosApiResult.NotFound -> {
                    currentPage = requestedPage
                    currentPageSize = PAGE_SIZE
                    totalProducts = 0
                    products = emptyList()
                    if (resetPositionAfterLoad) resetSavedListPosition()
                    saveLoadedState()
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

    private fun openCreateProduct() {
        if (configStore.getAccessToken().isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.pos_login_required, Toast.LENGTH_LONG).show()
            return
        }
        captureState()
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ProductEditFragment.newCreateInstance())
            .addToBackStack("product_create")
            .commit()
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

    private fun restoreStateValues() {
        products = savedState.products
        totalProducts = savedState.totalProducts
        currentPage = savedState.currentPage
        currentPageSize = savedState.currentPageSize
        selectedFilter = savedState.selectedFilter
        selectedSort = savedState.selectedSort
    }

    private fun saveLoadedState() {
        savedState.products = products
        savedState.totalProducts = totalProducts
        savedState.currentPage = currentPage
        savedState.currentPageSize = currentPageSize
        savedState.selectedFilter = selectedFilter
        savedState.selectedSort = selectedSort
        savedState.initialized = true
    }

    private fun captureState() {
        savedState.query = if (::searchInput.isInitialized) searchInput.text.toString() else savedState.query
        savedState.products = products
        savedState.totalProducts = totalProducts
        savedState.currentPage = currentPage
        savedState.currentPageSize = currentPageSize
        savedState.selectedFilter = selectedFilter
        savedState.selectedSort = selectedSort
        if (!::recyclerView.isInitialized) return
        val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val child = manager.findViewByPosition(position)
        savedState.firstVisiblePosition = position
        savedState.firstVisibleOffset = child?.top ?: 0
    }

    private fun restoreListPosition() {
        if (!savedState.initialized || products.isEmpty()) return
        recyclerView.post {
            val manager = recyclerView.layoutManager as? GridLayoutManager ?: return@post
            val position = savedState.firstVisiblePosition.coerceIn(0, adapter.itemCount - 1)
            manager.scrollToPositionWithOffset(position, savedState.firstVisibleOffset)
        }
    }

    private fun resetSavedListPosition() {
        savedState.firstVisiblePosition = 0
        savedState.firstVisibleOffset = 0
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
        addButton.isEnabled = !loading
    }

    private fun totalPages(): Int = if (totalProducts == 0) {
        1
    } else {
        (totalProducts + currentPageSize - 1) / currentPageSize
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val PRODUCT_THUMBNAIL_SIZE_PX = 256
    }
}

private class ProductInfoAdapter(
    private val scope: CoroutineScope,
    private val loadPhoto: suspend (String) -> Bitmap?,
    private val onDetail: (PosProduct) -> Unit,
    private val onPrint: (PosProduct) -> Unit
) : RecyclerView.Adapter<ProductInfoAdapter.ProductViewHolder>() {
    private var products: List<PosProduct> = emptyList()
    private val currency = NumberFormat.getNumberInstance(Locale("id", "ID"))
    private val photoCache = object : LruCache<String, Bitmap>(PHOTO_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun submitList(items: List<PosProduct>) {
        products = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_product_info, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) = holder.bind(products[position])

    override fun onViewRecycled(holder: ProductViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = products.size

    inner class ProductViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val photo: ImageView = view.findViewById(R.id.iv_product_photo)
        private val photoLoading: ProgressBar = view.findViewById(R.id.progress_product_photo)
        private val name: TextView = view.findViewById(R.id.tv_product_name)
        private val sku: TextView = view.findViewById(R.id.tv_product_sku)
        private val price: TextView = view.findViewById(R.id.tv_product_price)
        private val stock: TextView = view.findViewById(R.id.tv_product_stock)
        private val detail: View = view.findViewById(R.id.btn_edit_product)
        private val print: View = view.findViewById(R.id.btn_print_product)
        private var photoJob: Job? = null
        private var boundPhotoKey: String? = null

        fun bind(product: PosProduct) {
            bindPhoto(product)
            name.text = product.nama
            sku.text = itemView.context.getString(R.string.product_sku_value, product.sku)
            price.text = itemView.context.getString(
                R.string.product_price_summary,
                product.hargaBeliKode?.trim().orEmpty().ifEmpty { "-" },
                currency.format(product.hargaJual)
            )
            stock.text = itemView.context.getString(R.string.product_stock_value, product.stok, product.satuan)
            itemView.setOnClickListener { onDetail(product) }
            detail.setOnClickListener { onDetail(product) }
            print.setOnClickListener { onPrint(product) }
        }

        fun recycle() {
            photoJob?.cancel()
            photoJob = null
            boundPhotoKey = null
            showPhotoPlaceholder()
        }

        private fun bindPhoto(product: PosProduct) {
            photoJob?.cancel()
            val photoUrl = product.fotoUrl?.trim()?.takeIf { it.isNotEmpty() }
            val photoKey = photoUrl?.let { "${product.id ?: product.sku}:$it" }
            boundPhotoKey = photoKey
            showPhotoPlaceholder()
            if (photoUrl == null || photoKey == null) return

            photoCache.get(photoKey)?.let { bitmap ->
                showPhoto(bitmap)
                return
            }
            showPhotoLoading()
            photoJob = scope.launch {
                val bitmap = loadPhoto(photoUrl)
                if (boundPhotoKey != photoKey) return@launch
                if (bitmap == null) {
                    showPhotoPlaceholder()
                    return@launch
                }
                photoCache.put(photoKey, bitmap)
                showPhoto(bitmap)
            }
        }

        private fun showPhoto(bitmap: Bitmap) {
            photoLoading.visibility = View.GONE
            photo.setPadding(0, 0, 0, 0)
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setImageBitmap(bitmap)
        }

        private fun showPhotoLoading() {
            photo.setPadding(0, 0, 0, 0)
            photo.setImageDrawable(null)
            photoLoading.visibility = View.VISIBLE
        }

        private fun showPhotoPlaceholder() {
            photoLoading.visibility = View.GONE
            val padding = (18 * itemView.resources.displayMetrics.density).toInt()
            photo.setPadding(padding, padding, padding, padding)
            photo.scaleType = ImageView.ScaleType.CENTER_INSIDE
            photo.setImageResource(R.drawable.ic_photo_placeholder)
        }
    }

    private companion object {
        const val PHOTO_CACHE_SIZE_KB = 4 * 1024
    }
}
