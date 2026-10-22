package com.niimbot.printagent.ui

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class ProductPhotoFullscreenDialogFragment : DialogFragment() {
    @Inject lateinit var configStore: IntegrationConfigStore
    @Inject lateinit var posApiClient: PosApiClient

    private var originalPhotoBytes: ByteArray? = null
    private var photoPosition: Int = 1

    private val createPhotoDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { destination ->
        if (destination != null) savePhoto(destination)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_product_photo_fullscreen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val image = view.findViewById<ImageView>(R.id.iv_product_photo_fullscreen)
        val progress = view.findViewById<ProgressBar>(R.id.progress_product_photo_fullscreen)
        val error = view.findViewById<TextView>(R.id.tv_product_photo_fullscreen_error)
        val download = view.findViewById<MaterialButton>(R.id.btn_product_photo_fullscreen_download)
        photoPosition = requireArguments().getInt(ARG_POSITION, 1)
        image.contentDescription = getString(R.string.product_photo_fullscreen_description, photoPosition)
        view.findViewById<View>(R.id.btn_product_photo_fullscreen_close).setOnClickListener { dismiss() }
        download.setOnClickListener {
            val bytes = originalPhotoBytes ?: return@setOnClickListener
            createPhotoDocument.launch(suggestedFileName(photoPosition, detectMimeType(bytes)))
        }

        val reference = requireArguments().getString(ARG_REFERENCE).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = posApiClient.downloadProductPhoto(configStore.getBaseUrl(), reference)
            val bytes = when (result) {
                is PosApiResult.Success -> result.value
                else -> null
            }
            val bitmap = bytes?.let {
                withContext(Dispatchers.Default) {
                    ProductPhotoFiles.decodePreview(it, FULLSCREEN_PHOTO_SIZE_PX)
                }
            }
            progress.visibility = View.GONE
            if (bitmap == null) {
                error.visibility = View.VISIBLE
            } else {
                originalPhotoBytes = bytes
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                download.isEnabled = true
                download.alpha = 1f
            }
        }
    }

    private fun savePhoto(destination: Uri) {
        val bytes = originalPhotoBytes ?: return
        val contentResolver = requireContext().contentResolver
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        output.write(bytes)
                    } != null
                }.getOrDefault(false)
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                if (saved) R.string.product_photo_download_success else R.string.product_photo_download_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun detectMimeType(bytes: ByteArray): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outMimeType ?: "image/jpeg"
    }

    private fun suggestedFileName(position: Int, mimeType: String): String {
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return "lithia-foto-barang-${position.coerceAtLeast(1)}.$extension"
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
        }
    }

    companion object {
        const val TAG = "product_photo_fullscreen"
        private const val ARG_REFERENCE = "photo_reference"
        private const val ARG_POSITION = "photo_position"
        private const val FULLSCREEN_PHOTO_SIZE_PX = 2048

        fun newInstance(reference: String, position: Int) = ProductPhotoFullscreenDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REFERENCE, reference)
                putInt(ARG_POSITION, position)
            }
        }
    }
}
