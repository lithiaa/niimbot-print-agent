package com.niimbot.printagent.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
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
        val position = requireArguments().getInt(ARG_POSITION, 1)
        image.contentDescription = getString(R.string.product_photo_fullscreen_description, position)
        view.findViewById<View>(R.id.btn_product_photo_fullscreen_close).setOnClickListener { dismiss() }

        val reference = requireArguments().getString(ARG_REFERENCE).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = when (
                val result = posApiClient.downloadProductPhoto(configStore.getBaseUrl(), reference)
            ) {
                is PosApiResult.Success -> withContext(Dispatchers.Default) {
                    ProductPhotoFiles.decodePreview(result.value, FULLSCREEN_PHOTO_SIZE_PX)
                }
                else -> null
            }
            progress.visibility = View.GONE
            if (bitmap == null) {
                error.visibility = View.VISIBLE
            } else {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
            }
        }
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
