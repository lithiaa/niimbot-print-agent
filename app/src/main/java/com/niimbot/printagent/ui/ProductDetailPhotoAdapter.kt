package com.niimbot.printagent.ui

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.PosProductPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ProductDetailPhotoAdapter(
    private val scope: CoroutineScope,
    private val loadPhoto: suspend (String) -> Bitmap?,
    private val onPhotoClick: (PosProductPhoto) -> Unit
) : RecyclerView.Adapter<ProductDetailPhotoAdapter.PhotoViewHolder>() {
    private var photos: List<PosProductPhoto> = emptyList()
    private val cache = object : LruCache<String, Bitmap>(PHOTO_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun submitList(items: List<PosProductPhoto>) {
        photos = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_detail_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photos[position], position)
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = photos.size

    inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.iv_product_detail_photo_item)
        private val progress: ProgressBar = view.findViewById(R.id.progress_product_detail_photo_item)
        private val primary: TextView = view.findViewById(R.id.tv_product_detail_photo_primary)
        private var loadJob: Job? = null
        private var boundReference: String? = null

        fun bind(photo: PosProductPhoto, position: Int) {
            loadJob?.cancel()
            val reference = photo.downloadReference
            boundReference = reference
            showPlaceholder()
            primary.visibility = if (photo.isPrimary) View.VISIBLE else View.GONE
            image.contentDescription = itemView.context.getString(
                R.string.product_photo_item_description,
                position + 1
            )
            itemView.setOnClickListener {
                if (photo.downloadReference != null) onPhotoClick(photo)
            }
            if (reference == null) return

            cache.get(reference)?.let { bitmap ->
                showPhoto(bitmap)
                return
            }
            progress.visibility = View.VISIBLE
            loadJob = scope.launch {
                val bitmap = loadPhoto(reference)
                if (boundReference != reference || bitmap == null) {
                    progress.visibility = View.GONE
                    return@launch
                }
                cache.put(reference, bitmap)
                showPhoto(bitmap)
            }
        }

        fun recycle() {
            loadJob?.cancel()
            loadJob = null
            boundReference = null
            showPlaceholder()
        }

        private fun showPhoto(bitmap: Bitmap) {
            image.setPadding(0, 0, 0, 0)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setImageBitmap(bitmap)
            progress.visibility = View.GONE
        }

        private fun showPlaceholder() {
            val padding = (58 * itemView.resources.displayMetrics.density).toInt()
            image.setPadding(padding, padding, padding, padding)
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(R.drawable.ic_photo_placeholder)
            progress.visibility = View.GONE
        }
    }

    private companion object {
        const val PHOTO_CACHE_SIZE_KB = 12 * 1024
    }
}
