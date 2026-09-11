package com.niimbot.printagent.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.niimbot.printagent.R

class PreviewForegroundLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        isChildrenDrawingOrderEnabled = true
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val previewIndex = indexOfChild(findViewById<View>(R.id.card_label_preview))
        return PreviewForegroundOrder.childIndex(childCount, previewIndex, drawingPosition)
    }
}

internal object PreviewForegroundOrder {
    fun childIndex(childCount: Int, foregroundIndex: Int, drawingPosition: Int): Int {
        if (foregroundIndex !in 0 until childCount || drawingPosition !in 0 until childCount) {
            return drawingPosition
        }
        if (drawingPosition == childCount - 1) return foregroundIndex
        return if (drawingPosition >= foregroundIndex) drawingPosition + 1 else drawingPosition
    }
}
