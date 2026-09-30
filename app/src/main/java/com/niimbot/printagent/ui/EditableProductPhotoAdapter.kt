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
import com.niimbot.printagent.pos.PosPhotoUpload
import com.niimbot.printagent.pos.PosProductPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface EditableProductPhoto {
    val stableKey: String

    data class Existing(val photo: PosProductPhoto) : EditableProductPhoto {
        override val stableKey: String = "existing:${photo.id ?: photo.downloadReference}"
    }

    data class Pending(
        val localId: Long,
        val upload: PosPhotoUpload,
        val preview: Bitmap
    ) : EditableProductPhoto {
        override val stableKey: String = "pending:$localId"
    }
}

class EditableProductPhotoAdapter(
    private val scope: CoroutineScope,
    private val loadPhoto: suspend (String) -> Bitmap?,
    private val onRemove: (EditableProductPhoto) -> Unit
) : RecyclerView.Adapter<EditableProductPhotoAdapter.PhotoViewHolder>() {
    private var photos: List<EditableProductPhoto> = emptyList()
    private val cache = object : LruCache<String, Bitmap>(PHOTO_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    init {
        setHasStableIds(true)
    }

    fun submitList(items: List<EditableProductPhoto>) {
        photos = items.toList()
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = photos[position].stableKey.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_editable_product_photo, parent, false)
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
        private val image: ImageView = view.findViewById(R.id.iv_editable_product_photo)
        private val progress: ProgressBar = view.findViewById(R.id.progress_editable_product_photo)
        private val badge: TextView = view.findViewById(R.id.tv_editable_product_photo_badge)
        private val remove: View = view.findViewById(R.id.btn_editable_product_photo_remove)
        private var loadJob: Job? = null
        private var boundKey: String? = null

        fun bind(item: EditableProductPhoto, position: Int) {
            loadJob?.cancel()
            boundKey = item.stableKey
            showPlaceholder()
            image.contentDescription = itemView.context.getString(
                R.string.product_photo_item_description,
                position + 1
            )
            remove.setOnClickListener {
                val currentPosition = adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) onRemove(photos[currentPosition])
            }
            when (item) {
                is EditableProductPhoto.Pending -> {
                    badge.setText(R.string.product_photo_pending)
                    badge.visibility = View.VISIBLE
                    showPhoto(item.preview)
                }
                is EditableProductPhoto.Existing -> {
                    badge.setText(R.string.product_photo_primary)
                    badge.visibility = if (item.photo.isPrimary) View.VISIBLE else View.GONE
                    val reference = item.photo.downloadReference ?: return
                    cache.get(reference)?.let(::showPhoto) ?: loadExisting(item, reference)
                }
            }
        }

        fun recycle() {
            loadJob?.cancel()
            loadJob = null
            boundKey = null
            remove.setOnClickListener(null)
            showPlaceholder()
        }

        private fun loadExisting(item: EditableProductPhoto.Existing, reference: String) {
            progress.visibility = View.VISIBLE
            loadJob = scope.launch {
                val bitmap = loadPhoto(reference)
                if (boundKey != item.stableKey || bitmap == null) {
                    progress.visibility = View.GONE
                    return@launch
                }
                cache.put(reference, bitmap)
                showPhoto(bitmap)
            }
        }

        private fun showPhoto(bitmap: Bitmap) {
            image.setPadding(0, 0, 0, 0)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setImageBitmap(bitmap)
            progress.visibility = View.GONE
        }

        private fun showPlaceholder() {
            val padding = (34 * itemView.resources.displayMetrics.density).toInt()
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
