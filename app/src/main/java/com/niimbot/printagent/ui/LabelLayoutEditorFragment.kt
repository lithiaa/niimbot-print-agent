package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.niimbot.printagent.R
import com.niimbot.printagent.label.LabelElement
import com.niimbot.printagent.label.LabelElementFrame
import com.niimbot.printagent.label.LabelGenerator
import com.niimbot.printagent.label.LabelLayout
import com.niimbot.printagent.label.LabelSize
import com.niimbot.printagent.label.LabelTemplate
import com.niimbot.printagent.label.LabelTemplateCodec
import com.niimbot.printagent.label.LabelTemplatePreferences
import kotlin.math.roundToInt

class LabelLayoutEditorFragment : Fragment() {
    private lateinit var size: LabelSize
    private lateinit var layout: LabelLayout
    private lateinit var template: LabelTemplate
    private var selectedElement = LabelElement.PRODUCT_NAME
    private var applyingControls = false

    private lateinit var content: LinearLayout
    private lateinit var previewCard: MaterialCardView
    private lateinit var controlsCard: MaterialCardView
    private lateinit var previewContainer: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var elementDropdown: AutoCompleteTextView
    private lateinit var sliderX: Slider
    private lateinit var sliderY: Slider
    private lateinit var sliderWidth: Slider
    private lateinit var sliderHeight: Slider
    private lateinit var valueX: TextView
    private lateinit var valueY: TextView
    private lateinit var valueWidth: TextView
    private lateinit var valueHeight: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        size = LabelSize.fromName(args.getString(ARG_SIZE).orEmpty())
        layout = LabelLayout.fromName(args.getString(ARG_LAYOUT).orEmpty())
        template = LabelTemplateCodec.decode(
            savedInstanceState?.getString(STATE_TEMPLATE) ?: args.getString(ARG_TEMPLATE)
        ) ?: LabelTemplate.defaultFor(layout)
        selectedElement = LabelElement.entries.firstOrNull {
            it.name == savedInstanceState?.getString(STATE_ELEMENT)
        } ?: LabelElement.PRODUCT_NAME
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_label_layout_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureResponsiveLayout()
        configureControls()
        view.findViewById<TextView>(R.id.tv_label_layout_size_summary).text = getString(
            R.string.label_layout_size_summary,
            size.displayName,
            layout.displayName
        )
        view.findViewById<View>(R.id.btn_close_label_layout_editor).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_save_label_layout).setOnClickListener {
            LabelTemplatePreferences.save(requireContext(), size, layout, template)
            Toast.makeText(requireContext(), R.string.label_layout_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_reset_layout_editor_element).setOnClickListener {
            template = template.update(LabelTemplate.defaultFor(layout).frame(selectedElement))
            bindControls()
            updatePreview()
        }
        view.findViewById<View>(R.id.btn_reset_layout_editor_all).setOnClickListener {
            template = LabelTemplate.defaultFor(layout)
            bindControls()
            updatePreview()
        }
        updatePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEMPLATE, LabelTemplateCodec.encode(template))
        outState.putString(STATE_ELEMENT, selectedElement.name)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews(view: View) {
        content = view.findViewById(R.id.label_layout_editor_content)
        previewCard = view.findViewById(R.id.card_layout_editor_preview)
        controlsCard = view.findViewById(R.id.card_layout_editor_controls)
        previewContainer = view.findViewById(R.id.layout_editor_preview_container)
        preview = view.findViewById(R.id.iv_layout_editor_preview)
        elementDropdown = view.findViewById(R.id.dropdown_layout_editor_element)
        sliderX = view.findViewById(R.id.slider_layout_editor_x)
        sliderY = view.findViewById(R.id.slider_layout_editor_y)
        sliderWidth = view.findViewById(R.id.slider_layout_editor_width)
        sliderHeight = view.findViewById(R.id.slider_layout_editor_height)
        valueX = view.findViewById(R.id.tv_layout_editor_x_value)
        valueY = view.findViewById(R.id.tv_layout_editor_y_value)
        valueWidth = view.findViewById(R.id.tv_layout_editor_width_value)
        valueHeight = view.findViewById(R.id.tv_layout_editor_height_value)
    }

    private fun configureResponsiveLayout() {
        if (resources.configuration.smallestScreenWidthDp < 600) return
        val gap = dp(16)
        content.orientation = LinearLayout.HORIZONTAL
        previewCard.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .52f).apply {
            marginEnd = gap / 2
        }
        controlsCard.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, .48f).apply {
            marginStart = gap / 2
        }
    }

    private fun configureControls() {
        elementDropdown.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_label_dropdown, LabelElement.entries.map { it.displayName })
        )
        elementDropdown.setText(selectedElement.displayName, false)
        configureSlider(sliderX, 0f, 100f)
        configureSlider(sliderY, 0f, 100f)
        configureSlider(sliderWidth, 5f, 100f)
        configureSlider(sliderHeight, 2f, 80f)
        elementDropdown.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position)?.toString()
            selectedElement = LabelElement.entries.firstOrNull { it.displayName == selected }
                ?: LabelElement.PRODUCT_NAME
            bindControls()
            updatePreview()
        }
        sliderX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateSelectedFrame { copy(centerX = value / 100f) }
        }
        sliderY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateSelectedFrame { copy(centerY = value / 100f) }
        }
        sliderWidth.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateSelectedFrame { copy(width = value / 100f) }
        }
        sliderHeight.addOnChangeListener { _, value, fromUser ->
            if (fromUser) updateSelectedFrame { copy(height = value / 100f) }
        }
        bindControls()
    }

    private fun configureSlider(slider: Slider, minimum: Float, maximum: Float) {
        slider.valueTo = maximum
        slider.value = minimum
        slider.valueFrom = minimum
        // Normalizing overflow may produce half-percent centers, so this must remain continuous.
        slider.stepSize = 0f
    }

    private fun updateSelectedFrame(change: LabelElementFrame.() -> LabelElementFrame) {
        if (applyingControls) return
        template = template.update(template.frame(selectedElement).change())
        bindControls()
        updatePreview()
    }

    private fun bindControls() {
        applyingControls = true
        val frame = template.frame(selectedElement)
        sliderX.value = frame.centerX * 100f
        sliderY.value = frame.centerY * 100f
        sliderWidth.value = frame.width * 100f
        sliderHeight.value = frame.height * 100f
        valueX.text = percent(frame.centerX)
        valueY.text = percent(frame.centerY)
        valueWidth.text = percent(frame.width)
        valueHeight.text = percent(frame.height)
        applyingControls = false
    }

    private fun percent(value: Float): String =
        getString(R.string.label_element_percent, (value * 100f).roundToInt())

    private fun updatePreview() {
        val args = requireArguments()
        preview.setImageBitmap(
            LabelGenerator.generateLabel(
                nama = args.getString(ARG_NAME).orEmpty().ifBlank { getString(R.string.label_preview_name_placeholder) },
                hargaJual = args.getLong(ARG_SALE_PRICE),
                hargaBeli = args.getLong(ARG_PURCHASE_PRICE),
                sku = args.getString(ARG_SKU).orEmpty().ifBlank { "000000" },
                labelSize = size,
                labelLayout = layout,
                kodeHargaBeli = args.getString(ARG_PURCHASE_CODE),
                itemQty = args.getInt(ARG_ITEM_QTY, 1).coerceAtLeast(1),
                supplierCode = args.getString(ARG_SUPPLIER),
                tanggalMasuk = args.getString(ARG_ENTRY_DATE),
                labelTemplate = template,
                highlightedElement = selectedElement
            )
        )
        previewContainer.post {
            val density = resources.displayMetrics.density
            val availableWidth = (previewContainer.width - previewContainer.paddingLeft -
                previewContainer.paddingRight).coerceAtLeast(1)
            val maxHeight = (260 * density).toInt()
            var width = availableWidth
            var height = (width * size.heightMm / size.widthMm.toFloat()).toInt()
            if (height > maxHeight) {
                height = maxHeight
                width = (height * size.widthMm / size.heightMm.toFloat()).toInt()
            }
            preview.layoutParams = FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SIZE = "size"
        private const val ARG_LAYOUT = "layout"
        private const val ARG_TEMPLATE = "template"
        private const val ARG_NAME = "name"
        private const val ARG_PURCHASE_CODE = "purchase_code"
        private const val ARG_PURCHASE_PRICE = "purchase_price"
        private const val ARG_SALE_PRICE = "sale_price"
        private const val ARG_SKU = "sku"
        private const val ARG_ITEM_QTY = "item_qty"
        private const val ARG_SUPPLIER = "supplier"
        private const val ARG_ENTRY_DATE = "entry_date"
        private const val STATE_TEMPLATE = "state_template"
        private const val STATE_ELEMENT = "state_element"

        fun newInstance(
            size: LabelSize,
            layout: LabelLayout,
            template: LabelTemplate,
            name: String,
            purchaseCode: String?,
            purchasePrice: Long,
            salePrice: Long,
            sku: String,
            itemQty: Int,
            supplier: String?,
            entryDate: String?
        ) = LabelLayoutEditorFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SIZE, size.name)
                putString(ARG_LAYOUT, layout.name)
                putString(ARG_TEMPLATE, LabelTemplateCodec.encode(template))
                putString(ARG_NAME, name)
                putString(ARG_PURCHASE_CODE, purchaseCode)
                putLong(ARG_PURCHASE_PRICE, purchasePrice)
                putLong(ARG_SALE_PRICE, salePrice)
                putString(ARG_SKU, sku)
                putInt(ARG_ITEM_QTY, itemQty)
                putString(ARG_SUPPLIER, supplier)
                putString(ARG_ENTRY_DATE, entryDate)
            }
        }
    }
}
