package com.niimbot.printagent.label

import android.content.Context

/** Stores an independently editable layout for every physical label size. */
object LabelTemplatePreferences {
    private const val PREFERENCES = "label_templates_by_size"
    private const val LAYOUT_PREFIX = "layout_"
    private const val TEMPLATE_PREFIX = "template_"

    data class Selection(
        val layout: LabelLayout,
        val template: LabelTemplate
    )

    fun load(context: Context, size: LabelSize): Selection? {
        val preferences = context.getSharedPreferences(PREFERENCES, 0)
        val encodedTemplate = preferences.getString(TEMPLATE_PREFIX + size.name, null) ?: return null
        val template = LabelTemplateCodec.decode(encodedTemplate) ?: return null
        val layout = LabelLayout.fromName(
            preferences.getString(LAYOUT_PREFIX + size.name, LabelLayout.STANDARD.name).orEmpty()
        )
        return Selection(layout, template)
    }

    fun save(
        context: Context,
        size: LabelSize,
        layout: LabelLayout,
        template: LabelTemplate
    ) {
        context.getSharedPreferences(PREFERENCES, 0).edit()
            .putString(LAYOUT_PREFIX + size.name, layout.name)
            .putString(TEMPLATE_PREFIX + size.name, LabelTemplateCodec.encode(template))
            .apply()
    }
}
