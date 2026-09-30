package com.niimbot.printagent.ui

import android.content.DialogInterface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.niimbot.printagent.R

internal fun MaterialAlertDialogBuilder.showWithBoxedButtons(): AlertDialog =
    create().also { it.showWithBoxedButtons() }

internal fun AlertDialog.showWithBoxedButtons() {
    show()
    val buttons = listOf(
        DialogInterface.BUTTON_POSITIVE,
        DialogInterface.BUTTON_NEGATIVE,
        DialogInterface.BUTTON_NEUTRAL
    ).mapNotNull(::getButton)
    buttons.forEach(Button::styleAsAlertAction)
    buttons.firstOrNull()?.addAlertButtonRowSpacing()
}

private fun Button.styleAsAlertAction() {
    backgroundTintList = null
    setBackgroundResource(R.drawable.bg_alert_button_outline)
    setTextColor(ContextCompat.getColor(context, R.color.white))
    isAllCaps = false
    minWidth = dp(96)
    minHeight = dp(48)
    (layoutParams as? LinearLayout.LayoutParams)?.let { params ->
        params.marginStart = dp(6)
        params.marginEnd = dp(6)
        params.topMargin = 0
        params.bottomMargin = 0
        layoutParams = params
    }
}

private fun Button.addAlertButtonRowSpacing() {
    (parent as? ViewGroup)?.let { row ->
        row.setPaddingRelative(row.paddingStart, dp(8), row.paddingEnd, dp(12))
    }
}

private fun Button.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
